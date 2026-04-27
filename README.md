# rewrite-jee-to-boot

OpenRewrite recipes for migrating stateless EJB components to Spring Boot 2.7.x.

## Recipe: `hu.dojcsak.openrewrite.recipe.MigrateStatelessEjb`

A composite recipe that automates the migration of stateless EJB components to Spring Boot 2.7.x.
It runs the following steps in order:

### 1. Replace `@EJB` injection with `@Autowired`

Replaces `@EJB` on fields and setter methods with Spring `@Autowired`.

- If `beanName` is set on `@EJB`, a corresponding `@Qualifier("name")` is added.
- If `lookup` is set, the annotation cannot be automatically migrated — a `// TODO:` comment is added instead.

### 2. Replace session bean annotations with `@Service`

- `@Stateless` and `@Singleton` → `@Service` (the `name` attribute is preserved as `@Service("name")`).
- Removes `@Local`, `@LocalBean`, and `@Startup` from bean classes.
- Removes `@Local` from business interfaces.
- **Skips** beans annotated with `@Remote` or implementing a `@Remote` interface — marks them with a search result comment requesting manual migration.

### 3. Replace `javax.inject.@Inject` with `@Autowired`

A straight type replacement with no conditional logic.

### 4. Add `@Transactional` to `@Service` classes

EJBs get Container-Managed Transactions (CMT) by default.
This step adds `@Transactional` to every `@Service` class that doesn't already have it, replicating that behaviour in Spring.

### 5. Remove EJB build dependencies

Removes the following from the build descriptor:

- `javax:javaee-api`
- `javax.ejb:javax.ejb-api`
- `org.jboss.spec.javax.ejb:jboss-ejb-api_3*`
- `com.oracle.weblogic:javax.javaee-api`

### 6. Add Spring Boot starters

- `org.springframework.boot:spring-boot-starter:2.7.18` — added only if `javax.ejb.*` is in use.
- `org.springframework.boot:spring-boot-starter-data-jpa:2.7.18` — added only if `javax.persistence.*` is in use.

---

### What is not handled automatically

The following scenarios require manual migration and are either flagged with a comment or skipped entirely:

- Distributed (`@Remote`) EJBs
- JNDI lookups (`@EJB(lookup = ...)`)
- Message-driven beans (MDBs)
- EJB timers

## Individual recipes

| Recipe | Type | Description |
|--------|------|-------------|
| `hu.dojcsak.openrewrite.recipe.jee.ejb.MigrateEjbAnnotations` | Imperative Java | Replaces `@EJB` injection with `@Autowired` / `@Qualifier` |
| `hu.dojcsak.openrewrite.recipe.jee.ejb.MigrateStatelessSessionBeans` | Imperative Java | Replaces `@Stateless`/`@Singleton` with `@Service`, removes EJB-specific annotations |
| `hu.dojcsak.openrewrite.recipe.jee.ejb.AddTransactionalToServiceBeans` | Imperative Java | Adds `@Transactional` to `@Service` classes as a CMT replacement |
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
