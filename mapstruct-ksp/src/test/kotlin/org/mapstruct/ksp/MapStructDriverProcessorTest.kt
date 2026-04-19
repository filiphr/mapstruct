/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ksp

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import com.tschuchort.compiletesting.useKsp2
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

/**
 * End-to-end test: feeds a real Kotlin source containing an `@Mapper` interface to a real KSP
 * round driven by [MapStructDriverProcessor], then asserts on the generated Java driver source.
 *
 * Compiling the generated driver further (via javac with mapstruct-processor on the classpath)
 * is the *next* layer of the hybrid approach and is intentionally out of scope here — this test
 * proves the KSP layer in isolation.
 *
 * Assertions are made on individual tokens (`extends UserMapper`, `target = "fullName"`) rather
 * than larger substrings because:
 *  - real KSP materialises *every* annotation default into `KSAnnotation.arguments`, so the
 *    rendered annotations are intentionally verbose;
 *  - JavaPoet imports nearby types and breaks long argument lists across lines.
 * Substrings that span commas-and-spaces are unreliable; single tokens are robust.
 */
@OptIn(ExperimentalCompilerApi::class)
class MapStructDriverProcessorTest {

    @Test
    @DisplayName("simple @Mapper interface produces a Java driver extending the Kotlin source interface")
    fun simpleMapper() {
        val result = compile(
            SourceFile.kotlin(
                "UserMapper.kt",
                """
                package com.example

                import org.mapstruct.Mapper
                import org.mapstruct.Mapping

                data class User(val name: String, val age: Int)
                data class UserDto(val fullName: String, val age: Int)

                @Mapper
                interface UserMapper {
                    @Mapping(target = "fullName", source = "name")
                    fun toDto(user: User): UserDto
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("com/example/UserMapperDriver.java")
        assertThat(driver)
            .contains("package com.example;")
            .contains("@Mapper")
            .contains("interface UserMapperDriver extends UserMapper")
            .contains("@Override")
            .contains("@Mapping")
            .contains("target = \"fullName\"")
            .contains("source = \"name\"")
            .contains("UserDto toDto(@NotNull User user)")
    }

    @Test
    @DisplayName("multiple methods, each carrying their own @Mapping annotations")
    fun multipleMethods() {
        val result = compile(
            SourceFile.kotlin(
                "OrderMapper.kt",
                """
                package shop

                import org.mapstruct.Mapper
                import org.mapstruct.Mapping
                import org.mapstruct.Mappings

                data class Order(val id: Long, val customerName: String, val total: Double)
                data class OrderDto(val orderId: Long, val customer: String, val amount: Double)

                @Mapper
                interface OrderMapper {
                    @Mappings(
                        Mapping(target = "orderId", source = "id"),
                        Mapping(target = "customer", source = "customerName"),
                        Mapping(target = "amount", source = "total")
                    )
                    fun toDto(order: Order): OrderDto

                    fun toEntity(dto: OrderDto): Order
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("shop/OrderMapperDriver.java")
        assertThat(driver)
            .contains("interface OrderMapperDriver extends OrderMapper")
            .contains("OrderDto toDto(@NotNull Order order)")
            .contains("Order toEntity(@NotNull OrderDto dto)")
            .contains("@Mappings")
            .contains("target = \"orderId\"")
            .contains("target = \"customer\"")
            .contains("target = \"amount\"")
    }

    @Test
    @DisplayName("class-level @Mapper attributes (componentModel, uses, unmappedTargetPolicy) survive translation")
    fun classLevelMapperAttributes() {
        val result = compile(
            SourceFile.kotlin(
                "RichMapper.kt",
                """
                package rich

                import org.mapstruct.Mapper
                import org.mapstruct.Mapping
                import org.mapstruct.ReportingPolicy

                class HelperOne
                class HelperTwo

                data class A(val v: String)
                data class B(val v: String)

                @Mapper(
                    componentModel = "spring",
                    uses = [HelperOne::class, HelperTwo::class],
                    unmappedTargetPolicy = ReportingPolicy.ERROR
                )
                interface RichMapper {
                    fun map(a: A): B
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("rich/RichMapperDriver.java")
        assertThat(driver)
            .contains("componentModel = \"spring\"")
            .contains("HelperOne.class")
            .contains("HelperTwo.class")
            .contains("unmappedTargetPolicy = ReportingPolicy.ERROR")
    }

    @Test
    @DisplayName("driver carries @Generated(value, comments=source:...) like mapstruct-processor's own impls")
    fun generatedAnnotationIsEmitted() {
        val result = compile(
            SourceFile.kotlin(
                "SomeMapper.kt",
                """
                package gen

                import org.mapstruct.Mapper

                data class A(val v: String)
                data class B(val v: String)

                @Mapper
                interface SomeMapper {
                    fun toB(a: A): B
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("gen/SomeMapperDriver.java")
        assertThat(driver)
            // Prefer the JDK 9+ annotation; KSP 2.3.x requires a modern JDK so this should
            // always be the resolved one in realistic builds.
            .contains("import javax.annotation.processing.Generated")
            .contains("value = \"org.mapstruct.ksp.MapStructDriverProcessor\"")
            // Package-relative source path — reproducible across machines (no absolute path).
            .contains("comments = \"source: gen/SomeMapper.kt\"")
    }

    @Test
    @DisplayName("@Mapper(config = KotlinConfigInterface::class) — the class literal survives")
    fun mapperConfigReferenceSurvives() {
        // Regression test. @MapperConfig itself is pure configuration — MapStruct's javac APT
        // only finds it through a @Mapper(config = ...) reference, then reads its annotations
        // off the compiled classpath .class (kotlinc has already produced that by the time
        // javac runs). So our KSP processor only has to faithfully copy the `config` class
        // literal onto the driver — no driver generation for the config type itself. This
        // test locks that contract in.
        val result = compile(
            SourceFile.kotlin(
                "ConfiguredMapper.kt",
                """
                package cfg

                import org.mapstruct.Mapper
                import org.mapstruct.MapperConfig
                import org.mapstruct.ReportingPolicy

                @MapperConfig(unmappedTargetPolicy = ReportingPolicy.ERROR)
                interface SharedConfig

                data class A(val v: String)
                data class B(val v: String)

                @Mapper(config = SharedConfig::class)
                interface ConfiguredMapper {
                    fun toB(a: A): B
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("cfg/ConfiguredMapperDriver.java")
        assertThat(driver)
            .contains("config = SharedConfig.class")
        // And crucially, we do NOT emit a driver for the @MapperConfig type itself.
        assertThat(result.kspSourcesDir.walkTopDown().any { it.name == "SharedConfigDriver.java" })
            .isFalse()
    }

    @Test
    @DisplayName("Kotlin built-ins (String, Int, List<T>) translate to JVM platform types")
    fun kotlinBuiltinsTranslateToJvmTypes() {
        val result = compile(
            SourceFile.kotlin(
                "ListMapper.kt",
                """
                package coll

                import org.mapstruct.Mapper

                data class Item(val name: String)
                data class ItemDto(val name: String)

                @Mapper
                interface ListMapper {
                    fun toDtos(items: List<Item>): List<ItemDto>
                    fun count(items: List<Item>): Int
                    fun describe(item: Item): String
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("coll/ListMapperDriver.java")
        assertThat(driver)
            // List<Item> → java.util.List<Item> (java.util.List imported, Item in same pkg).
            .contains("List<Item> items")
            .contains("List<ItemDto> toDtos(")
            // Non-null `Int` → JVM primitive `int`. Must be `int` (not `Integer`) so the driver's
            // override signature matches what the Kotlin parent method compiles to.
            .contains("int count(")
            .contains("String describe(")
            .contains("import java.util.List")
    }

    @Test
    @DisplayName("nullable Kotlin primitives stay boxed; non-null ones become JVM primitives")
    fun nullabilityControlsBoxing() {
        val result = compile(
            SourceFile.kotlin(
                "NullabilityMapper.kt",
                """
                package nlb

                import org.mapstruct.Mapper

                data class Box(val value: Int)

                @Mapper
                interface NullabilityMapper {
                    fun unboxed(b: Box): Int        // non-null Int -> primitive int
                    fun maybe(b: Box): Int?         // nullable Int -> boxed Integer
                    fun flagged(active: Boolean): Box
                    fun maybeFlagged(active: Boolean?): Box
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("nlb/NullabilityMapperDriver.java")
        assertThat(driver)
            .contains("int unboxed(")
            .contains("Integer maybe(")
            .contains("boolean active")
            // Nullable primitive parameter — must be boxed so `null` fits.
            .contains("Boolean active")
    }

    @Test
    @DisplayName("JetBrains @Nullable / @NotNull are emitted at parameter and return-type positions")
    fun jetbrainsNullabilityAnnotations() {
        val result = compile(
            SourceFile.kotlin(
                "NullAnno.kt",
                """
                package nla

                import org.mapstruct.Mapper

                data class Src(val v: String)
                data class Tgt(val v: String)

                @Mapper
                interface NullAnno {
                    fun mapBoth(src: Src): Tgt
                    fun mapNullable(src: Src?): Tgt?
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("nla/NullAnnoDriver.java")
        assertThat(driver)
            .contains("import org.jetbrains.annotations.NotNull")
            .contains("import org.jetbrains.annotations.Nullable")
            // Non-null param + return — @NotNull on parameter and on the method itself.
            .contains("@NotNull")
            .contains("mapBoth(@NotNull Src src)")
            // Nullable counterpart.
            .contains("mapNullable(@Nullable Src src)")
            .contains("@Nullable")
    }

    @Test
    @DisplayName("primitives never receive null annotations (they can't be null by construction)")
    fun primitivesAreNotAnnotated() {
        val result = compile(
            SourceFile.kotlin(
                "Prim.kt",
                """
                package nla

                import org.mapstruct.Mapper

                data class Src(val v: String)

                @Mapper
                interface Prim {
                    fun count(s: Src): Int
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("nla/PrimDriver.java")
        assertThat(driver)
            .contains("int count(")
            .doesNotContain("@NotNull int")
            .doesNotContain("@Nullable int")
    }

    @Test
    @DisplayName("void return is not annotated")
    fun voidReturnIsNotAnnotated() {
        val result = compile(
            SourceFile.kotlin(
                "Sink.kt",
                """
                package nla

                import org.mapstruct.Mapper

                data class Src(val v: String)
                class Dest

                @Mapper
                interface Sink {
                    fun fill(src: Src, dest: Dest)
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("nla/SinkDriver.java")
        assertThat(driver)
            .contains("void fill(")
            // No @NotNull / @Nullable precedes `void`.
            .doesNotContain("@NotNull void")
            .doesNotContain("@Nullable void")
    }

    @Test
    @DisplayName("primitives in generic-argument positions stay boxed (JVM generics can't hold primitives)")
    fun primitivesInGenericsStayBoxed() {
        val result = compile(
            SourceFile.kotlin(
                "GenericsMapper.kt",
                """
                package gen

                import org.mapstruct.Mapper

                data class Box(val values: List<Int>)

                @Mapper
                interface GenericsMapper {
                    fun sum(values: List<Int>): Int
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("gen/GenericsMapperDriver.java")
        assertThat(driver)
            // List<Int> → List<Integer>, never List<int> (illegal in Java).
            .contains("List<Integer> values")
            // But the direct Int return is still a primitive.
            .contains("int sum(")
    }

    @Test
    @DisplayName("Unit return type maps to void")
    fun unitMapsToVoid() {
        val result = compile(
            SourceFile.kotlin(
                "VoidMapper.kt",
                """
                package v

                import org.mapstruct.Mapper

                data class Src(val s: String)
                class Sink

                @Mapper
                interface VoidMapper {
                    fun fill(src: Src, sink: Sink)
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("v/VoidMapperDriver.java")
        assertThat(driver)
            .contains("void fill(@NotNull Src src, @NotNull Sink sink)")
    }

    @Test
    @DisplayName("bare @Mapper renders without materialized default arguments")
    fun bareMapperHasNoDefaultArgs() {
        val result = compile(
            SourceFile.kotlin(
                "BareMapper.kt",
                """
                package bare

                import org.mapstruct.Mapper

                data class A(val v: String)
                data class B(val v: String)

                @Mapper
                interface BareMapper {
                    fun map(a: A): B
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("bare/BareMapperDriver.java")
        // Synthetic defaults should be dropped. The only member we expect on the copied @Mapper
        // is `implementationName` — injected by the processor so Mappers.getMapper works against
        // the Kotlin interface. All the other 20-odd default fields must not appear.
        assertThat(driver)
            .contains("implementationName = \"BareMapperImpl\"")
            .doesNotContain("componentModel = \"default\"")
            .doesNotContain("ReportingPolicy.IGNORE")
            .doesNotContain("collectionMappingStrategy =")
            .doesNotContain("nullValueMappingStrategy =")
            .doesNotContain("builder = @Builder")
    }

    @Test
    @DisplayName("user-supplied @Mapper attributes are preserved; others are dropped")
    fun userSuppliedMapperAttributesSurviveOnly() {
        val result = compile(
            SourceFile.kotlin(
                "PartialMapper.kt",
                """
                package partial

                import org.mapstruct.Mapper

                data class A(val v: String)
                data class B(val v: String)

                @Mapper(componentModel = "spring")
                interface PartialMapper {
                    fun map(a: A): B
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("partial/PartialMapperDriver.java")
        assertThat(driver)
            .contains("componentModel = \"spring\"")
            // Nothing else under @Mapper should appear — all other fields are still at default.
            .doesNotContain("unmappedSourcePolicy")
            .doesNotContain("typeConversionPolicy")
            .doesNotContain("collectionMappingStrategy")
            .doesNotContain("nullValueMappingStrategy")
    }

    @Test
    @DisplayName("@Mapping defaults collapse too — only user-specified fields survive")
    fun mappingDefaultsAlsoCollapse() {
        val result = compile(
            SourceFile.kotlin(
                "SlimMapping.kt",
                """
                package slim

                import org.mapstruct.Mapper
                import org.mapstruct.Mapping

                data class Src(val name: String)
                data class Tgt(val fullName: String)

                @Mapper
                interface SlimMapping {
                    @Mapping(target = "fullName", source = "name")
                    fun toTgt(src: Src): Tgt
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("slim/SlimMappingDriver.java")
        assertThat(driver)
            .contains("target = \"fullName\"")
            .contains("source = \"name\"")
            // All those `dateFormat = ""`, `ignore = false`, `qualifiedBy = {}` filler defaults
            // from @Mapping should no longer be in the output.
            .doesNotContain("dateFormat = \"\"")
            .doesNotContain("numberFormat = \"\"")
            .doesNotContain("ignore = false")
            .doesNotContain("qualifiedBy = {}")
            .doesNotContain("dependsOn = {}")
    }

    @Test
    @DisplayName("implementationName is injected so Mappers.getMapper(KotlinInterface::class.java) works")
    fun implementationNameInjection() {
        val result = compile(
            SourceFile.kotlin(
                "M.kt",
                """
                package inj

                import org.mapstruct.Mapper

                data class A(val v: String)
                data class B(val v: String)

                @Mapper
                interface UserMapper {
                    fun toB(a: A): B
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("inj/UserMapperDriver.java")
        // MapStruct's APT will emit UserMapperImpl (not UserMapperDriverImpl), which matches
        // the <class>Impl convention against the original Kotlin interface.
        assertThat(driver).contains("implementationName = \"UserMapperImpl\"")
    }

    @Test
    @DisplayName("user-specified implementationName is preserved (not overridden by injection)")
    fun userImplementationNamePreserved() {
        val result = compile(
            SourceFile.kotlin(
                "M.kt",
                """
                package inj

                import org.mapstruct.Mapper

                data class A(val v: String)
                data class B(val v: String)

                @Mapper(implementationName = "CustomImpl")
                interface UserMapper {
                    fun toB(a: A): B
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("inj/UserMapperDriver.java")
        assertThat(driver)
            .contains("implementationName = \"CustomImpl\"")
            .doesNotContain("implementationName = \"UserMapperImpl\"")
    }

    @Test
    @DisplayName("the processor does not reprocess its own generated @Mapper output")
    fun doesNotReprocessGeneratedOutput() {
        val result = compile(
            SourceFile.kotlin(
                "SimpleMapper.kt",
                """
                package recursive

                import org.mapstruct.Mapper

                data class A(val v: String)
                data class B(val v: String)

                @Mapper
                interface SimpleMapper {
                    fun map(a: A): B
                }
                """.trimIndent()
            )
        )

        val allGenerated = result.kspSourcesDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".java") }
            .map { it.name }
            .toList()
        // Expect exactly one generated driver. Without the Origin.KOTLIN filter, later KSP
        // rounds would pick up the generated Java @Mapper and produce SimpleMapperDriverDriver,
        // then SimpleMapperDriverDriverDriver, until the filesystem refuses the filename.
        assertThat(allGenerated).containsExactly("SimpleMapperDriver.java")
    }

    @Test
    @DisplayName("class-level type parameters are mirrored on the driver and flow into `extends`")
    fun classLevelTypeParameters() {
        val result = compile(
            SourceFile.kotlin(
                "GenericMapper.kt",
                """
                package gp

                import org.mapstruct.Mapper

                @Mapper
                interface GenericMapper<S, T> {
                    fun map(s: S): T
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("gp/GenericMapperDriver.java")
        assertThat(driver)
            .contains("interface GenericMapperDriver<S, T> extends GenericMapper<S, T>")
            .contains("T map(S s)")
    }

    @Test
    @DisplayName("upper-bounded type parameters carry the bound through")
    fun boundedTypeParameters() {
        val result = compile(
            SourceFile.kotlin(
                "BoundedMapper.kt",
                """
                package gp

                import org.mapstruct.Mapper

                @Mapper
                interface BoundedMapper<T : Number> {
                    fun describe(t: T): String
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("gp/BoundedMapperDriver.java")
        assertThat(driver)
            .contains("<T extends Number>")
            .contains("extends BoundedMapper<T>")
            .contains("String describe(T t)")
    }

    @Test
    @DisplayName("method-level type parameters are preserved on the driver method")
    fun methodLevelTypeParameters() {
        val result = compile(
            SourceFile.kotlin(
                "MM.kt",
                """
                package gp

                import org.mapstruct.Mapper

                data class A(val v: String)
                data class B(val v: String)

                @Mapper
                interface MM {
                    fun <U> wrap(value: U): List<U>
                    fun simple(a: A): B
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("gp/MMDriver.java")
        assertThat(driver)
            // Type-parameter use positions aren't annotated — KSP's Nullability for a bare
            // type variable reference (declared `<U>` with no bound, meaning `U : Any?`) is not
            // NOT_NULL, and JetBrains @NotNull on an unbounded type variable is misleading anyway.
            .contains("<U> List<U> wrap(U value)")
            .contains("B simple(@NotNull A a)")
    }

    @Test
    @DisplayName("generic abstract-class mapper extends its parameterised parent")
    fun abstractClassWithTypeParameters() {
        val result = compile(
            SourceFile.kotlin(
                "GenericAbstract.kt",
                """
                package gp

                import org.mapstruct.Mapper

                @Mapper
                abstract class GenericAbstract<S, T> {
                    abstract fun map(s: S): T
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("gp/GenericAbstractDriver.java")
        assertThat(driver)
            .contains("class GenericAbstractDriver<S, T> extends GenericAbstract<S, T>")
            .contains("public abstract T map(S s)")  // type-parameter positions stay unannotated
    }

    @Test
    @DisplayName("@Mapper on an abstract class produces an abstract driver class extending it")
    fun abstractClassWithoutCtorArgs() {
        val result = compile(
            SourceFile.kotlin(
                "AbstractMapper.kt",
                """
                package ac

                import org.mapstruct.Mapper
                import org.mapstruct.Mapping

                data class A(val v: String)
                data class B(val v: String)

                @Mapper
                abstract class AbstractMapper {
                    @Mapping(target = "v", source = "v")
                    abstract fun toB(a: A): B
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("ac/AbstractMapperDriver.java")
        assertThat(driver)
            .contains("public abstract class AbstractMapperDriver extends AbstractMapper")
            .contains("@Override")
            .contains("public abstract B toB(@NotNull A a)")
            .contains("implementationName = \"AbstractMapperImpl\"")
    }

    @Test
    @DisplayName("abstract class with primary ctor args gets a forwarding constructor")
    fun abstractClassWithCtorArgs() {
        val result = compile(
            SourceFile.kotlin(
                "InjectableMapper.kt",
                """
                package ac

                import org.mapstruct.Mapper

                class StringHelper
                class DateHelper
                data class A(val v: String)
                data class B(val v: String)

                @Mapper
                abstract class InjectableMapper(
                    protected val strings: StringHelper,
                    protected val dates: DateHelper
                ) {
                    abstract fun toB(a: A): B
                }
                """.trimIndent()
            )
        )

        val driver = result.findGenerated("ac/InjectableMapperDriver.java")
        assertThat(driver)
            .contains("public abstract class InjectableMapperDriver extends InjectableMapper")
            .contains("public InjectableMapperDriver(@NotNull StringHelper strings, @NotNull DateHelper dates)")
            .contains("super(strings, dates)")
    }

    @Test
    @DisplayName("@Mapper on a final or `open` class is rejected with a pointed error")
    fun nonAbstractClassReportsError() {
        val result = compile(
            SourceFile.kotlin(
                "OpenMapper.kt",
                """
                package broken

                import org.mapstruct.Mapper

                data class A(val v: String)
                data class B(val v: String)

                @Mapper
                open class OpenMapper {
                    fun toB(a: A): B = B(a.v)
                }
                """.trimIndent()
            )
        )

        assertThat(result.messages)
            .contains("only supported on interfaces and abstract classes")
        assertThat(result.kspSourcesDir.walkTopDown().any { it.name == "OpenMapperDriver.java" })
            .isFalse()
    }

    private fun compile(vararg sources: SourceFile): Result {
        val compilation = KotlinCompilation().apply {
            this.sources = sources.toList()
            inheritClassPath = true
            messageOutputStream = System.out
            verbose = false
            useKsp2()
            configureKsp {
                symbolProcessorProviders.add(MapStructDriverProcessorProvider())
            }
        }
        val res = compilation.compile()
        return Result(res.exitCode, res.messages, compilation.kspSourcesDir)
    }

    private data class Result(
        val exitCode: KotlinCompilation.ExitCode,
        val messages: String,
        val kspSourcesDir: File
    ) {
        fun findGenerated(relativePath: String): String {
            val expected = File(kspSourcesDir, "java/$relativePath")
            if (expected.exists()) return expected.readText()
            // Fallback: walk and match by tail; KSP layout may differ between runners.
            val match = kspSourcesDir.walkTopDown()
                .firstOrNull { it.isFile && it.absolutePath.endsWith(relativePath.replace('/', File.separatorChar)) }
            requireNotNull(match) {
                "No generated file matching '$relativePath' under $kspSourcesDir.\n" +
                    "Tree:\n" +
                    kspSourcesDir.walkTopDown().joinToString("\n") { it.absolutePath }
            }
            return match.readText()
        }
    }
}
