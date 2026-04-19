/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

/*
 * End-to-end integration build for the KSP-backed Kotlin support.
 *
 * Pipeline exercised:
 *   kotlinc + KSP (mapstruct-ksp) → emits a Java driver interface shadowing the Kotlin
 *   @Mapper; javac then runs with mapstruct-processor as a javac annotation processor, sees the
 *   generated driver, and produces the impl. The JUnit test in src/test/java loads the mapper
 *   through `Mappers.getMapper` and asserts the DTO is produced correctly.
 *
 * KotlinKspIntegrationTest drives this via Gradle TestKit and passes -PmapstructRootPath to
 * point at the reactor's built artifacts so this project can resolve mapstruct, the processor
 * and the KSP jar without needing anything in a remote repository.
 */
plugins {
    kotlin("jvm") version "2.3.0"
    id("com.google.devtools.ksp") version "2.3.6"
}

val mapstructRootPath: String = providers.gradleProperty("mapstructRootPath").get()
val mapstructVersion: String = providers.gradleProperty("mapstructVersion").get()

repositories {
    mavenCentral()
    // flatDir keeps the test hermetic: resolve mapstruct straight out of the reactor's target/
    // directories rather than requiring a global `mvn install`.
    flatDir {
        dirs("$mapstructRootPath/core/target")
        dirs("$mapstructRootPath/processor/target")
        dirs("$mapstructRootPath/mapstruct-ksp/target")
    }
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(files("$mapstructRootPath/core/target/mapstruct-$mapstructVersion.jar"))

    // KSP-side: translates Kotlin @Mapper interfaces to Java driver stubs.
    ksp(files("$mapstructRootPath/mapstruct-ksp/target/mapstruct-ksp-$mapstructVersion.jar"))

    // javac-side: MapStruct's own annotation processor, which generates the impl of the driver.
    annotationProcessor(files("$mapstructRootPath/processor/target/mapstruct-processor-$mapstructVersion.jar"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.14.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}
