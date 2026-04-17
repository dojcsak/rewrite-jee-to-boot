plugins {
    id("org.openrewrite.build.recipe-library-base") version "latest.release"

    // Configures artifact repositories used for dependency resolution to include maven central and nexus snapshots.
    // If you are operating in an environment where public repositories are not accessible, we recommend using a
    // virtual repository which mirrors both maven central and nexus snapshots.
    id("org.openrewrite.build.recipe-repositories") version "latest.release"

    id("maven-publish")
}

group = "hu.dojcsak.openrewrite.recipe"
version = "1.0.0-SNAPSHOT"
description = "JEE to Spring Boot rewrite recipes"

recipeDependencies {
    parserClasspath("org.jspecify:jspecify:1.0.0")
    parserClasspath("javax.ejb:javax.ejb-api:3.2.2")
    parserClasspath("javax.inject:javax.inject:1")
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    // The bom version can also be set to a specific version
    // https://github.com/openrewrite/rewrite-recipe-bom/releases
    implementation(platform("org.openrewrite.recipe:rewrite-recipe-bom:latest.release"))

    implementation("org.openrewrite:rewrite-java")
    implementation("org.openrewrite.recipe:rewrite-java-dependencies")
    implementation("org.openrewrite:rewrite-yaml")
    implementation("org.openrewrite:rewrite-xml")
    implementation("org.openrewrite.meta:rewrite-analysis")

    // Provides JavaTemplate.builder() used at runtime in imperative recipes.
    // No Refaster templates (@BeforeTemplate/@AfterTemplate) in this project, so no annotationProcessor needed.
    implementation("org.openrewrite:rewrite-templating")

    // The RewriteTest class needed for testing recipes
    testImplementation("org.openrewrite:rewrite-test") {
        exclude(group = "org.slf4j", module = "slf4j-nop")
    }

    // JEE types needed by the parser in tests
    testRuntimeOnly("javax.ejb:javax.ejb-api:3.2.2")
    testRuntimeOnly("javax.inject:javax.inject:1")

    // Spring 5.3.x types needed by JavaTemplate at recipe runtime — targets Spring Boot 2.7.x
    // (runtimeOnly covers test runtime too)
    runtimeOnly("org.springframework:spring-beans:5.3.+")
    runtimeOnly("org.springframework:spring-context:5.3.+")
    runtimeOnly("org.springframework:spring-tx:5.3.+")

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
