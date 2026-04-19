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
        assumeAllJarsBuilt();
        FileUtils.copyDirectory( rootPath.resolve( PROJECT_DIR ).toFile(), testProjectDir );

        BuildResult result = configuredRunner().build();

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

    /**
     * Verifies that the KSP processor plays nicely with Gradle's incremental-build machinery.
     *
     * <p>We declare per-file dependencies when emitting a driver
     * ({@code Dependencies(aggregating = false, containingFile)}), meaning the generated output
     * is tied to a single input Kotlin source. Two concrete consequences:
     *
     * <ol>
     * <li>A rebuild with no source changes must leave the {@code :kspKotlin} task
     *     {@link TaskOutcome#UP_TO_DATE}. If we got the dependency declaration wrong (e.g. used
     *     {@code aggregating = true} or omitted the containing file), Gradle would conservatively
     *     re-run KSP every build, which is correct but unnecessarily slow.</li>
     * <li>A change to the {@code @Mapper} source file must invalidate the driver's cache entry,
     *     re-running KSP. If it didn't, the generated driver could go stale — annotations on
     *     methods, renamed methods, etc. wouldn't propagate to the impl.</li>
     * </ol>
     *
     * <p>The three successive builds share the same project directory so Gradle's on-disk
     * fingerprints survive between them.
     */
    @Test
    void kspIncrementalBuildBehaves() throws IOException {
        assumeAllJarsBuilt();
        FileUtils.copyDirectory( rootPath.resolve( PROJECT_DIR ).toFile(), testProjectDir );

        GradleRunner runner = configuredRunner();

        // 1. Fresh build: driver is generated, KSP ran.
        BuildResult first = runner.build();
        assertThat( first.task( ":kspKotlin" ).getOutcome() )
            .as( "first build should run kspKotlin" )
            .isEqualTo( TaskOutcome.SUCCESS );

        // 2. Nothing changed → KSP's inputs and outputs fingerprint identically → UP-TO-DATE.
        BuildResult second = runner.build();
        assertThat( second.task( ":kspKotlin" ).getOutcome() )
            .as( "rebuild with no changes should leave kspKotlin UP_TO_DATE" )
            .isEqualTo( TaskOutcome.UP_TO_DATE );

        // 3. Touch the @Mapper source → isolating dependency must invalidate the driver.
        Path mapperSource = testProjectDir.toPath().resolve(
            "src/main/kotlin/org/mapstruct/itest/ksp/UserMapper.kt" );
        String original = Files.readString( mapperSource );
        // Append a harmless comment. Modifying Kotlin file content is enough to bump the
        // fingerprint; we avoid changing the semantic content so the rest of the build stays
        // green and the test only measures the invalidation signal we care about.
        Files.writeString( mapperSource, original + "\n// touched to force KSP re-run\n" );

        BuildResult third = runner.build();
        assertThat( third.task( ":kspKotlin" ).getOutcome() )
            .as( "editing the @Mapper source should re-run kspKotlin" )
            .isEqualTo( TaskOutcome.SUCCESS );
    }

    private GradleRunner configuredRunner() throws IOException {
        String version = readReactorVersion();
        return GradleRunner.create()
            .withGradleVersion( GRADLE_VERSION )
            .withProjectDir( testProjectDir )
            .withArguments(
                "test",
                "--stacktrace",
                "--no-daemon",
                "-PmapstructRootPath=" + rootPath,
                "-PmapstructVersion=" + version
            )
            .forwardOutput();
    }

    /**
     * Skip early if the caller forgot to build the required reactor modules. Failing inside
     * Gradle here is noisy and the error message is far from the cause; surface the precondition
     * plainly instead.
     */
    private static void assumeAllJarsBuilt() throws IOException {
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
