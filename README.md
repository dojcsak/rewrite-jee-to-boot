# rewrite-jee-to-boot

OpenRewrite recipes for migrating stateless EJB components to Spring Boot 2.7.x.

## Recipe: `hu.dojcsak.openrewrite.recipe.MigrateStatelessEjb`

A composite recipe that automates the migration of stateless EJB components to Spring Boot 2.7.x.
It runs the following steps in order:

### 1. Replace `@EJB` injection with `@Autowired`

Replaces `@EJB` on fields and setter methods with Spring `@Autowired`.

- If `beanName` is a string literal, a corresponding `@Qualifier("name")` is added.
- The following cases cannot be automatically migrated — a `// TODO:` comment is added instead and `@Autowired` is **not** emitted:
  - `beanInterface` is set
  - `name`, `mappedName`, or non-empty `description` is set
  - `beanName`, `lookup`, or `mappedName` is a constant reference (non-literal)
- `lookup` is flagged with a `// TODO:` comment too, but `@Autowired` is still added, provided the declared
  field/parameter type is not a `@Remote` business interface (directly or via superinterface) or otherwise
  unresolvable. Spring resolves `@Autowired` by type regardless of the original JNDI lookup name; a `@Remote`
  target needs a manual decision (REST client, messaging, etc.) instead.
- Constructor-level `@EJB` annotations are not processed (EJB does not support constructor injection).

### 2. Inline single-implementor `@Local` business interfaces

Inlines `@Local` EJB business interfaces that have exactly one concrete, non-`@Remote` implementor:

- Retypes every field/parameter/return-type reference to the interface — including nested inside a
  generic type argument or array, and a class-level `@Local({FooLocal.class})` value on an abstract
  base class — to the implementor class.
- Removes the `implements` entry from every class that declares it directly, not just the sole
  concrete implementor (e.g. an abstract intermediate class the implementor extends).
- Deletes the interface source file once it has zero remaining references anywhere in the analyzed
  source set, and no string literal elsewhere mentions its simple name (a best-effort heuristic for
  JNDI-style lookups).
- **Must run before step 3**, which unconditionally strips the `@Local` annotations this step's
  candidate identification depends on.
- A reference site whose compilation unit cannot resolve the implementor class (e.g. a split
  interface/implementation module layout where the referencing module lacks a dependency on the
  implementation module) is left untouched and flagged with a `// TODO:` comment instead, and the
  interface is **not** deleted in that case.
- **Skips** interfaces with more than one implementor, interfaces extended by another interface, and
  implementors that are themselves skipped by step 3's `@Remote` check.
- Must run across the full multi-module reactor in one pass; always rebuild afterwards to catch
  anything its cross-module visibility heuristics could not see.

### 3. Replace session bean annotations with `@Service`

- `@Stateless` and `@Singleton` → `@Service` (the `name` string-literal attribute is preserved as `@Service("name")`).
- Removes `@Local`, `@LocalBean`, and `@Startup` from bean classes.
- Also removes `@Local`/`@LocalBean` (but not `@Startup`, which only ever applies to a `@Singleton` bean itself) from classes that carry them without `@Stateless`/`@Singleton` themselves (e.g. an abstract base class carrying `@Local({FooLocal.class})` purely as bean metadata) — but only once none of a class-level `@Local({...})`'s listed values still resolve to a genuine interface; if `InlineLocalBeanInterfaces` deliberately left that interface un-inlined, the annotation is left untouched too, since it's still accurate.
- Removes `@Local` and `@LocalBean` from business interfaces.
- Flags the following with a search result comment for manual review:
  - `mappedName` (vendor JNDI binding — no Spring equivalent)
  - `description` (informational only — no Spring equivalent)
  - `name` when it is a constant reference (non-literal)
  - `@Startup` removal (Spring `@Service` is lazy by default; `@Lazy(false)` needed for eager init)
- **Skips** beans annotated with `@Remote` or implementing a `@Remote` interface (directly, through a superclass, or via superinterface inheritance) — marks them with a search result comment requesting manual migration.

### 4. Replace `javax.inject.@Inject` with `@Autowired`

A straight type replacement with no conditional logic.

### 5. Add `@Transactional` to `@Service` classes

EJBs get Container-Managed Transactions (CMT) by default.
This step adds `@Transactional` to every `@Service` class that doesn't already have it, replicating that behaviour in Spring.

### 6. Remove EJB Maven packaging configuration

- Removes `<packaging>ejb</packaging>` from EJB module POMs. `jar` is the Maven default, so the element is simply omitted.
- Removes `<type>ejb</type>` from `<dependency>` and `<dependencyManagement>` declarations. After migration, the referenced modules produce standard JARs, so the explicit type is no longer needed.

### 7. Remove EJB build dependencies

Removes the following from the build descriptor:

- `javax:javaee-api`
- `javax.ejb:javax.ejb-api`
- `org.jboss.spec.javax.ejb:jboss-ejb-api_3*`
- `com.oracle.weblogic:javax.javaee-api`

### 8. Add Spring Boot core starter

`org.springframework.boot:spring-boot-starter:2.7.18` — added only if `javax.ejb.*` is in use.

### 9. Add `spring-tx` for non-JPA modules

`org.springframework:spring-tx` — added only if the module contains `@Stateless`/`@Singleton` EJB session beans **and** does not use `javax.persistence.*` types.

The `<version>` tag is **always omitted**. BOM-managed projects (e.g. those importing `spring-boot-dependencies`) need no explicit version. Projects without a BOM will receive OpenRewrite's built-in "no version provided" marker on the generated dependency, prompting manual version selection appropriate for the target Spring Framework generation (5.x for Spring Boot 2.x, 6.x for Spring Boot 3.x).

EJB Container-Managed Transactions (CMT) do not imply JPA usage. A service bean that is transactional but has no persistence types (e.g. an email-sending service) needs `spring-tx` on the classpath, but adding the full `spring-boot-starter-data-jpa` stack would be excessive. Modules that do use JPA already get `spring-tx` transitively through step 10, so the two steps are mutually exclusive.

The decision is made **per module**: in a multi-module Maven project, JPA usage in one module does not prevent `spring-tx` from being added to an unrelated non-JPA module.

| Module type | `spring-tx` added directly | via `spring-boot-starter-data-jpa` |
|---|---|---|
| Non-JPA (e.g. email, messaging) | yes (this step) | — |
| JPA | — | yes (transitively, next step) |

### 10. Add Spring Boot JPA starter

`org.springframework.boot:spring-boot-starter-data-jpa:2.7.18` — added only if `javax.persistence.*` is in use.

---

### What is not handled automatically

The following scenarios require manual migration and are either flagged with a comment or skipped entirely:

- Distributed (`@Remote`) EJBs — skipped with a search result comment
- `@EJB(lookup = ...)` — JNDI lookup, flagged with a TODO comment
- `@EJB(beanInterface = ...)` — interface narrowing, flagged with a TODO comment
- `@EJB(name = ...)` / `@EJB(mappedName = ...)` / non-empty `@EJB(description = ...)` — flagged with a TODO comment
- Non-literal `beanName`, `lookup`, or `mappedName` (constant reference) — flagged with a TODO comment
- `@Stateless(mappedName = ...)` / `@Singleton(mappedName = ...)` — flagged with a search result comment
- Message-driven beans (MDBs)
- EJB timers

## Recipe: `hu.dojcsak.openrewrite.recipe.MigrateLog4j1ToSpringBootLogging`

A composite recipe that migrates a Log4j 1.x based project to Spring Boot's default logging
stack (SLF4J + Logback). It runs the following steps in order:

### 1. Migrate Log4j 1.x API usage to SLF4J

Runs `org.openrewrite.java.logging.slf4j.Log4j1ToSlf4j1`, which replaces Log4j 1.x API calls
(`Logger`, `MDC`, `Appender`, ...) with their SLF4J equivalents. Internally this chains
Log4j1→Log4j2 (adds `log4j-api` + `log4j-core` to the POM as an intermediate step) followed by
Log4j2→SLF4J (migrates the Java code but does **not** remove the Log4j 2 POM dependencies).

### 2. Rename loggers for their enclosing class

Runs `org.openrewrite.java.logging.slf4j.LoggersNamedForEnclosingClass` to ensure every SLF4J
logger is named after its actual enclosing class.

### 3. Fix leftover `Throwable`-as-message calls

Runs the custom `hu.dojcsak.openrewrite.recipe.logging.FixSlf4jLoggerObjectThrowable` recipe.
Log4j 1.x accepted `Object` as the message argument and had no single-arg `Throwable` overload
restriction, while SLF4J requires a `String` message. Rewrites `logger.level(throwable)` to
`logger.level(throwable.getMessage())` and `logger.level(throwable, throwable)` to
`logger.level(throwable.getMessage(), throwable)` for all standard log levels (error, warn,
info, debug, trace).

### 4. Remove Log4j and bridge dependencies

Removes the following from `<dependencies>`:

- `log4j:log4j` (kept as a safety net — already handled by step 1 in most cases)
- `org.apache.logging.log4j:log4j-api`, `log4j-core`, `log4j-slf4j-impl` (added as an
  intermediate step by Log4j1→Log4j2, not cleaned up by Log4j2→SLF4J)
- `org.slf4j:log4j-over-slf4j` (the bridge shim, no longer needed once the real SLF4J API is used)

### 5. Remove Log4j and bridge dependencies from dependency management

Removes the same coordinates as step 4 from `<dependencyManagement>`, since `RemoveDependency`
only touches `<dependencies>`.

---

Spring Boot's `spring-boot-starter` transitively provides `slf4j-api` and `logback-classic` via
`spring-boot-starter-logging`, so no replacement dependency needs to be added.

**Note:** `log4j.properties` / `log4j.xml` configuration files are not migrated automatically —
convert them to `logback-spring.xml` or `application.properties` entries by hand.

## Recipe: `hu.dojcsak.openrewrite.recipe.ConvertPropertiesToYaml`

Converts `application*.properties` (e.g. `application.properties`, `application-dev.properties`)
into the equivalent nested `application*.yaml`, which is the idiomatic configuration format for
Spring Boot projects.

### How it works

1. Scans the project for every `application*.properties` file and for any `.yml`/`.yaml` files
   that already exist alongside them.
2. Builds a nested YAML mapping from each file's dotted property keys (e.g.
   `spring.datasource.url` and `spring.datasource.username` are merged under a shared
   `spring.datasource` mapping), preserving the original key order. Comment lines directly
   preceding a property are carried over as YAML comments above the corresponding key.
3. Writes the generated `application*.yaml` file and deletes the original `.properties` file.

```properties
# Datasource URL
spring.datasource.url=jdbc:h2:mem:testdb
spring.datasource.username=sa
```

becomes:

```yaml
spring:
  datasource:
    # Datasource URL
    url: jdbc:h2:mem:testdb
    username: sa
```

### What is not handled automatically

- A comment block at the **end of a file** with no property following it is dropped, since there
  is nothing to attach it to.
- **Key conflicts**, such as `a=1` together with `a.b=2`, are resolved last-one-wins with no
  validation; these are rare and require manual review regardless of representation.
- If a `.yml`/`.yaml` file with the target name **already exists**, the conversion for that file
  is skipped entirely and the `.properties` file is left untouched, to avoid clobbering
  hand-written YAML.

## Individual recipes

| Recipe | Type | Description |
|--------|------|-------------|
| `hu.dojcsak.openrewrite.recipe.jee.ejb.MigrateEjbAnnotations` | Imperative Java | Replaces `@EJB` injection with `@Autowired` / `@Qualifier` |
| `hu.dojcsak.openrewrite.recipe.jee.ejb.InlineLocalBeanInterfaces` | Imperative Java (`ScanningRecipe`) | Inlines single-implementor `@Local` business interfaces into their implementor class and deletes them |
| `hu.dojcsak.openrewrite.recipe.jee.ejb.MigrateStatelessSessionBeans` | Imperative Java | Replaces `@Stateless`/`@Singleton` with `@Service`, removes EJB-specific annotations |
| `hu.dojcsak.openrewrite.recipe.jee.ejb.AddTransactionalToServiceBeans` | Imperative Java | Adds `@Transactional` to `@Service` classes as a CMT replacement |
| `hu.dojcsak.openrewrite.recipe.jee.ejb.RemoveEjbMavenPackaging` | Imperative Java | Removes `<packaging>ejb</packaging>` from module POMs and `<type>ejb</type>` from dependency references |
| `hu.dojcsak.openrewrite.recipe.jee.ejb.AddSpringTxUnlessJpaPresent` | Imperative Java (`ScanningRecipe`) | Adds `spring-tx` dependency per module when `@Stateless`/`@Singleton` is present but `javax.persistence.*` is not |
| `hu.dojcsak.openrewrite.recipe.MigrateStatelessEjb` | Declarative YAML | Composite recipe that runs all of the above plus dependency management |
| `hu.dojcsak.openrewrite.recipe.logging.FixSlf4jLoggerObjectThrowable` | Imperative Java | Rewrites leftover `logger.level(throwable)` / `logger.level(throwable, throwable)` calls to use `throwable.getMessage()` |
| `hu.dojcsak.openrewrite.recipe.MigrateLog4j1ToSpringBootLogging` | Declarative YAML | Composite recipe that migrates Log4j 1.x usage and dependencies to SLF4J + Logback |
| `hu.dojcsak.openrewrite.recipe.boot.MigrateApplicationPropertiesToYaml` | Imperative Java (`ScanningRecipe`) | Converts `application*.properties` to nested `application*.yaml`, deleting the original file |
| `hu.dojcsak.openrewrite.recipe.ConvertPropertiesToYaml` | Declarative YAML | Wraps `MigrateApplicationPropertiesToYaml` as a discoverable, top-level recipe |

## Local publishing for testing

Build and install the recipe JAR to your local Maven repository:

```bash
./gradlew publishToMavenLocal
```

This publishes to `~/.m2/repository` under the coordinates `hu.dojcsak.openrewrite.recipe:rewrite-jee-to-boot:1.0.0-SNAPSHOT`.

### Apply with the Gradle rewrite plugin

Add the following to the `build.gradle.kts` (or `build.gradle`) of the project you want to migrate:

```kotlin
plugins {
    id("org.openrewrite.rewrite") version("latest.release")
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    rewrite("hu.dojcsak.openrewrite.recipe:rewrite-jee-to-boot:1.0.0-SNAPSHOT")
}

rewrite {
    activeRecipe("hu.dojcsak.openrewrite.recipe.MigrateStatelessEjb")
}
```

Then run:

```bash
./gradlew rewriteRun
```

### Apply with the Maven rewrite plugin

Add the following to the `pom.xml` of the project you want to migrate:

```xml
<plugin>
    <groupId>org.openrewrite.maven</groupId>
    <artifactId>rewrite-maven-plugin</artifactId>
    <version>RELEASE</version>
    <configuration>
        <activeRecipes>
            <recipe>hu.dojcsak.openrewrite.recipe.MigrateStatelessEjb</recipe>
        </activeRecipes>
    </configuration>
    <dependencies>
        <dependency>
            <groupId>hu.dojcsak.openrewrite.recipe</groupId>
            <artifactId>rewrite-jee-to-boot</artifactId>
            <version>1.0.0-SNAPSHOT</version>
        </dependency>
    </dependencies>
</plugin>
```

Then run:

```bash
mvn rewrite:run
```

## Applying OpenRewrite recipe development best practices

```bash
./gradlew --init-script init.gradle rewriteRun -Drewrite.activeRecipe=org.openrewrite.recipes.rewrite.OpenRewriteRecipeBestPractices
```
