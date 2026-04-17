plugins {
    id("org.openrewrite.build.recipe-library-base") version "latest.release"

    // Configures artifact repositories used for dependency resolution to include maven central and nexus snapshots.
    // If you are operating in an environment where public repositories are not accessible, we recommend using a
    // virtual repository which mirrors both maven central and nexus snapshots.
    id("org.openrewrite.build.recipe-repositories") version "latest.release"
}

group = "hu.dojcsak.openrewrite.recipe"
version = "1.0.0-SNAPSHOT"
description = "JEE to Spring Boot rewrite recipes"

recipeDependencies {
    parserClasspath("org.jspecify:jspecify:1.0.0")
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

    // Refaster style recipes need the rewrite-templating annotation processor and dependency for generated recipes
    // https://github.com/openrewrite/rewrite-templating/releases
    annotationProcessor("org.openrewrite:rewrite-templating:latest.release")
    implementation("org.openrewrite:rewrite-templating")
    // The `@BeforeTemplate` and `@AfterTemplate` annotations are needed for refaster style recipes
    compileOnly("com.google.errorprone:error_prone_core:latest.release") {
        exclude("com.google.auto.service", "auto-service-annotations")
        exclude("io.github.eisop","dataflow-errorprone")
    }

    // The RewriteTest class needed for testing recipes
    testImplementation("org.openrewrite:rewrite-test") {
        exclude(group = "org.slf4j", module = "slf4j-nop")
    }
    testImplementation("org.assertj:assertj-core:latest.release")

    // Support for parsing different Java versions
    testRuntimeOnly("org.openrewrite:rewrite-java-17")
    testRuntimeOnly("org.openrewrite:rewrite-java-21")
    testRuntimeOnly("org.openrewrite:rewrite-java-25")

    // Need to have a slf4j binding to see any output enabled from the parser.
    runtimeOnly("ch.qos.logback:logback-classic:1.5.+")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Arewrite.javaParserClasspathFrom=resources")
}
