plugins {
    // Pinned to 2.23.1 instead of "latest.release": 2.23.4's own POM declares a hard
    // dependency on org.openrewrite:rewrite-java/rewrite-core/rewrite-maven:8.91.4, a version
    // that was never published to Maven Central or the Gradle Plugin Portal (upstream release
    // defect). Revert to "latest.release" once a fixed plugin version is published.
    id("org.openrewrite.build.recipe-library-base") version "2.23.1"

    // Configures artifact repositories used for dependency resolution to include maven central and nexus snapshots.
    // If you are operating in an environment where public repositories are not accessible, we recommend using a
    // virtual repository which mirrors both maven central and nexus snapshots.
    id("org.openrewrite.build.recipe-repositories") version "2.23.1"

    id("maven-publish")
}

group = "hu.dojcsak.openrewrite.recipe"
version = "1.0.0-SNAPSHOT"
description = "JEE to Spring Boot rewrite recipes"

recipeDependencies {
    parserClasspath("org.jspecify:jspecify:1.0.0")
    parserClasspath("javax.annotation:javax.annotation-api:1.3.2")
    parserClasspath("javax.ejb:javax.ejb-api:3.2.2")
    parserClasspath("javax.inject:javax.inject:1")
    // Spring types needed by JavaTemplate at recipe runtime — embedded in META-INF/rewrite/classpath/
    // so JavaParser.dependenciesFromClasspath() finds them regardless of Maven plugin version.
    parserClasspath("org.springframework:spring-beans:5.3.39")
    parserClasspath("org.springframework:spring-context:5.3.39")
    parserClasspath("org.springframework:spring-tx:5.3.39")
    parserClasspath("javax.persistence:javax.persistence-api:2.2")
    parserClasspath("org.springframework.boot:spring-boot-autoconfigure:2.7.+")
    parserClasspath("org.springframework.boot:spring-boot:2.7.+")
    parserClasspath("org.springframework.boot:spring-boot-test:2.7.+")
    parserClasspath("org.junit.jupiter:junit-jupiter-api:5.+")
    parserClasspath("log4j:log4j:1.2.17")
    // Needed by JavaTemplate at recipe runtime to synthesize @RequiredArgsConstructor with
    // correct type attribution (ConvertFieldInjectionToLombokConstructorInjection).
    parserClasspath("org.projectlombok:lombok:1.18.+")
    // Note: CXF/JAX-WS types are NOT needed here. ConvertFieldInjectionToLombokConstructorInjection
    // only matches (AnnotationMatcher/MethodMatcher) against already-parsed, already-typed
    // source — it never synthesizes CXF/JAX-WS code via JavaTemplate — so those types only need
    // to be on the *target project's own* build classpath (which Moderne/mod resolves
    // independently) and on this project's *test* classpath (see testRuntimeOnly below).
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    // The bom version can also be set to a specific version
    // https://github.com/openrewrite/rewrite-recipe-bom/releases
    implementation(platform("org.openrewrite.recipe:rewrite-recipe-bom:latest.release"))

    implementation("org.openrewrite:rewrite-java")
    implementation("org.openrewrite:rewrite-maven")
    implementation("org.openrewrite.recipe:rewrite-java-dependencies")
    implementation("org.openrewrite.recipe:rewrite-logging-frameworks")
    implementation("org.openrewrite:rewrite-yaml")
    implementation("org.openrewrite:rewrite-xml")
    implementation("org.openrewrite:rewrite-properties")
    implementation("org.openrewrite:rewrite-maven")
    implementation("org.openrewrite.meta:rewrite-analysis")

    // Provides JavaTemplate.builder() used at runtime in imperative recipes.
    // No Refaster templates (@BeforeTemplate/@AfterTemplate) in this project, so no annotationProcessor needed.
    implementation("org.openrewrite:rewrite-templating")

    // The RewriteTest class needed for testing recipes
    testImplementation("org.openrewrite:rewrite-test") {
        exclude(group = "org.slf4j", module = "slf4j-nop")
    }

    // JEE types needed by the parser in tests
    testRuntimeOnly("javax.annotation:javax.annotation-api:1.3.2")
    testRuntimeOnly("javax.ejb:javax.ejb-api:3.2.2")
    testRuntimeOnly("javax.inject:javax.inject:1")
    testRuntimeOnly("javax.persistence:javax.persistence-api:2.2")

    // Spring 5.3.x types needed by JavaTemplate classpath() lookup in tests
    // (parserClasspath in recipeDependencies covers production runtime via META-INF/rewrite/classpath/)
    testRuntimeOnly("org.springframework:spring-beans:5.3.39")
    testRuntimeOnly("org.springframework:spring-context:5.3.39")
    testRuntimeOnly("org.springframework:spring-tx:5.3.39")
    testRuntimeOnly("log4j:log4j:1.2.17")

    // @SpringBootApplication type needed by classpath() lookup in AddJpaStarterDependencies tests
    testRuntimeOnly("org.springframework.boot:spring-boot-autoconfigure:2.7.18")

    // Lombok types needed by JavaTemplate/JavaParser classpath() lookup in
    // ConvertFieldInjectionToLombokConstructorInjection tests
    testRuntimeOnly("org.projectlombok:lombok:1.18.+")

    // JAX-WS/CXF types needed by ConvertFieldInjectionToLombokConstructorInjection tests
    // (both namespaces, mirroring the parserClasspath entries above)
    testRuntimeOnly("org.apache.cxf:cxf-rt-frontend-jaxws:4.1.+")
    testRuntimeOnly("jakarta.xml.ws:jakarta.xml.ws-api:4.0.+")
    testRuntimeOnly("jakarta.jws:jakarta.jws-api:3.0.+")
    testRuntimeOnly("javax.xml.ws:jaxws-api:2.3.1")
    testRuntimeOnly("javax.jws:javax.jws-api:1.1")

    // Support for parsing different Java versions
    testRuntimeOnly("org.openrewrite:rewrite-java-17")
    testRuntimeOnly("org.openrewrite:rewrite-java-21")
    testRuntimeOnly("org.openrewrite:rewrite-java-25")

    // SLF4J API needed at compile time for @Slf4j (Lombok); binding provided at runtime
    compileOnly("org.slf4j:slf4j-api:latest.release")
    runtimeOnly("ch.qos.logback:logback-classic:latest.release")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            versionMapping {
                usage("java-api") {
                    fromResolutionOf("runtimeClasspath")
                }
                usage("java-runtime") {
                    fromResolutionResult()
                }
            }
        }
    }
    repositories {
        // ./gradlew publishToMavenLocal
        mavenLocal()
        // ./gradlew publish -PnexusUrl=https://nexus.example.com/repository/snapshots
        if (project.hasProperty("nexusUrl")) {
            maven {
                name = "nexus"
                url = uri(project.property("nexusUrl") as String)
                if (project.hasProperty("nexusUsername") && project.hasProperty("nexusPassword")) {
                    credentials {
                        username = project.property("nexusUsername") as String
                        password = project.property("nexusPassword") as String
                    }
                }
            }
        }
    }
}

tasks.named<Delete>("clean") {
    delete("src/main/resources/META-INF/rewrite/classpath")
}

tasks.named("processResources") {
    dependsOn(tasks.named("downloadRecipeDependencies"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.named<JavaCompile>("compileJava") {
    // Suppress "source/target value 8 is obsolete" from the recipe-library-base plugin's --release 8.
    options.compilerArgs.add("-Xlint:-options")
}
