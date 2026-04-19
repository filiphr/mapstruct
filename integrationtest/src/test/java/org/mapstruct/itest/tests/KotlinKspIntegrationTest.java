/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.itest.tests;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end integration test for the KSP-backed Kotlin support.
 *
 * <p>Drives a Gradle build against the sub-project at
 * {@code integrationtest/src/test/resources/kotlinKspTest/}. That build:
 * <ol>
 * <li>runs kotlinc + KSP (with {@code mapstruct-ksp} as the KSP processor) to emit a Java
 *     driver interface shadowing the Kotlin {@code @Mapper};</li>
 * <li>runs javac on that driver with {@code mapstruct-processor} as a javac annotation processor
 *     to generate the impl;</li>
 * <li>runs a JUnit test ({@code UserMapperIntegrationTest}) that loads the mapper via
 *     {@link org.mapstruct.factory.Mappers#getMapper(Class)} and exercises it against a Kotlin
 *     data class.</li>
 * </ol>
 *
 * <p>Gradle (not Maven) because KSP's first-party plugin lives on the Gradle Plugin Portal; the
 * Maven community bridge ({@code com.dyescape:kotlin-maven-symbol-processing}) hasn't been
 * updated for Kotlin 2.x. The pattern mirrors {@link GradleIncrementalCompilationTest}.
 *
 * <p>Requires: {@code mapstruct-ksp}, {@code mapstruct-processor} and {@code mapstruct} to
 * be built into their respective {@code target/} directories before this test runs. Enable the
 * {@code ksp} profile and run {@code mvn -Pksp -DskipTests package} at the reactor root first.
 */
@EnabledForJreRange(min = JRE.JAVA_21)
class KotlinKspIntegrationTest {

    private static final String PROJECT_DIR = "integrationtest/src/test/resources/kotlinKspTest";
    /** KSP 2.3.x and Kotlin 2.3.0 both want Gradle 8.2+. 8.10.2 is a known-good combination. */
    private static final String GRADLE_VERSION = "8.10.2";

    private static Path rootPath;

    @TempDir
    File testProjectDir;

    @BeforeAll
    static void setupClass() {
        rootPath = Paths.get( System.getProperty( "mapstruct_root", "." ) ).toAbsolutePath();
    }

    @Test
    void shouldCompileAndExecuteKotlinMapper() throws IOException {
        // Skip early if the caller forgot to build the spike module. Failing inside Gradle here
        // is noisy and the error message is far from the cause; surface the precondition plainly.
        assumeTrue(
            findJar( rootPath.resolve( "mapstruct-ksp/target" ), "mapstruct-ksp-" ) != null,
            "mapstruct-ksp jar not built. Run with -Pksp and -am so the module is in the "
                + "reactor:\n  ./mvnw -Pksp -DskipTests -am package"
        );
        assumeTrue(
            findJar( rootPath.resolve( "processor/target" ), "mapstruct-processor-" ) != null,
            "mapstruct-processor jar not built — run `./mvnw -pl processor -am package` first"
        );
        assumeTrue(
            findJar( rootPath.resolve( "core/target" ), "mapstruct-" ) != null,
            "mapstruct (core) jar not built — run `./mvnw -pl core -am package` first"
        );

        FileUtils.copyDirectory( rootPath.resolve( PROJECT_DIR ).toFile(), testProjectDir );

        String version = readReactorVersion();

        BuildResult result = GradleRunner.create()
            .withGradleVersion( GRADLE_VERSION )
            .withProjectDir( testProjectDir )
            .withArguments(
                "test",
                "--stacktrace",
                "--no-daemon",
                "-PmapstructRootPath=" + rootPath,
                "-PmapstructVersion=" + version
            )
            .forwardOutput()
            .build();

        assertThat( result.task( ":test" ).getOutcome() )
            .isIn( TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE );

        // Strong assertion: the KSP processor actually produced the driver we expected. Without
        // this, a silent scope regression (processor finding no mappers) would still pass because
        // the test sub-project's JUnit test would fail to compile — which would also fail here,
        // but with a less-pointed error. The positive existence check is more diagnostic.
        Path generated = testProjectDir.toPath().resolve(
            "build/generated/ksp/main/java/org/mapstruct/itest/ksp/UserMapperDriver.java" );
        assertThat( Files.exists( generated ) )
            .as( "expected KSP to emit driver at %s", generated )
            .isTrue();
    }

    /** Parse the <version> from the reactor's parent POM. */
    private static String readReactorVersion() throws IOException {
        String pom = Files.readString( rootPath.resolve( "parent/pom.xml" ) );
        // Match the first <version> tag that appears after <artifactId>mapstruct-parent —
        // this avoids matching <version> in the <parent> block at the top.
        int anchor = pom.indexOf( "mapstruct-parent" );
        int open = pom.indexOf( "<version>", anchor );
        int close = pom.indexOf( "</version>", open );
        return pom.substring( open + "<version>".length(), close ).trim();
    }

    private static Path findJar(Path directory, String namePrefix) throws IOException {
        if ( !Files.isDirectory( directory ) ) {
            return null;
        }
        try ( Stream<Path> entries = Files.list( directory ) ) {
            return entries
                .filter( p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith( namePrefix )
                        && name.endsWith( ".jar" )
                        && !name.endsWith( "-sources.jar" )
                        && !name.endsWith( "-javadoc.jar" );
                } )
                .findFirst()
                .orElse( null );
        }
    }
}
