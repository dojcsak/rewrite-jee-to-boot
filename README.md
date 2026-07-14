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
