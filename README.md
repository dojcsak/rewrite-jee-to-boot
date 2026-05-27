# rewrite-jee-to-boot

OpenRewrite recipes for migrating stateless EJB components to Spring Boot 2.7.x.

## Recipe: `hu.dojcsak.openrewrite.recipe.MigrateStatelessEjb`

A composite recipe that automates the migration of stateless EJB components to Spring Boot 2.7.x.
It runs the following steps in order:

### 1. Replace `@EJB` injection with `@Autowired`

Replaces `@EJB` on fields and setter methods with Spring `@Autowired`.

- If `beanName` is a string literal, a corresponding `@Qualifier("name")` is added.
- The following cases cannot be automatically migrated — a `// TODO:` comment is added instead and `@Autowired` is **not** emitted:
  - `lookup` or `beanInterface` is set
  - `name`, `mappedName`, or non-empty `description` is set
  - `beanName`, `lookup`, or `mappedName` is a constant reference (non-literal)
- Constructor-level `@EJB` annotations are not processed (EJB does not support constructor injection).

### 2. Replace session bean annotations with `@Service`

- `@Stateless` and `@Singleton` → `@Service` (the `name` string-literal attribute is preserved as `@Service("name")`).
- Removes `@Local`, `@LocalBean`, and `@Startup` from bean classes.
- Removes `@Local` and `@LocalBean` from business interfaces.
- Flags the following with a search result comment for manual review:
  - `mappedName` (vendor JNDI binding — no Spring equivalent)
  - `description` (informational only — no Spring equivalent)
  - `name` when it is a constant reference (non-literal)
  - `@Startup` removal (Spring `@Service` is lazy by default; `@Lazy(false)` needed for eager init)
- **Skips** beans annotated with `@Remote` or implementing a `@Remote` interface (directly, through a superclass, or via superinterface inheritance) — marks them with a search result comment requesting manual migration.

### 3. Replace `javax.inject.@Inject` with `@Autowired`

A straight type replacement with no conditional logic.

### 4. Add `@Transactional` to `@Service` classes

EJBs get Container-Managed Transactions (CMT) by default.
This step adds `@Transactional` to every `@Service` class that doesn't already have it, replicating that behaviour in Spring.

### 5. Remove EJB Maven packaging configuration

- Removes `<packaging>ejb</packaging>` from EJB module POMs. `jar` is the Maven default, so the element is simply omitted.
- Removes `<type>ejb</type>` from `<dependency>` and `<dependencyManagement>` declarations. After migration, the referenced modules produce standard JARs, so the explicit type is no longer needed.

### 6. Remove EJB build dependencies

Removes the following from the build descriptor:

- `javax:javaee-api`
- `javax.ejb:javax.ejb-api`
- `org.jboss.spec.javax.ejb:jboss-ejb-api_3*`
- `com.oracle.weblogic:javax.javaee-api`

### 7. Add Spring Boot core starter

`org.springframework.boot:spring-boot-starter:2.7.18` — added only if `javax.ejb.*` is in use.

### 8. Add `spring-tx` for non-JPA modules

`org.springframework:spring-tx` — added only if the module contains `@Stateless`/`@Singleton` EJB session beans **and** does not use `javax.persistence.*` types.

The `<version>` tag is **always omitted**. BOM-managed projects (e.g. those importing `spring-boot-dependencies`) need no explicit version. Projects without a BOM will receive OpenRewrite's built-in "no version provided" marker on the generated dependency, prompting manual version selection appropriate for the target Spring Framework generation (5.x for Spring Boot 2.x, 6.x for Spring Boot 3.x).

EJB Container-Managed Transactions (CMT) do not imply JPA usage. A service bean that is transactional but has no persistence types (e.g. an email-sending service) needs `spring-tx` on the classpath, but adding the full `spring-boot-starter-data-jpa` stack would be excessive. Modules that do use JPA already get `spring-tx` transitively through step 9, so the two steps are mutually exclusive.

The decision is made **per module**: in a multi-module Maven project, JPA usage in one module does not prevent `spring-tx` from being added to an unrelated non-JPA module.

| Module type | `spring-tx` added directly | via `spring-boot-starter-data-jpa` |
|---|---|---|
| Non-JPA (e.g. email, messaging) | yes (this step) | — |
| JPA | — | yes (transitively, next step) |

### 9. Add Spring Boot JPA starter

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
