/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ksp.spike

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import com.tschuchort.compiletesting.configureKsp
import com.tschuchort.compiletesting.kspSourcesDir
import com.tschuchort.compiletesting.useKsp2
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mapstruct.ap.MappingProcessor
import org.mapstruct.factory.Mappers
import java.io.File
import java.net.URLClassLoader
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.StandardLocation
import javax.tools.ToolProvider

/**
 * End-to-end proof of the hybrid KSP → javac → MapStruct-APT pipeline.
 *
 * The test stitches two compilers together inside a single JVM:
 *   1. kctfork runs kotlinc + KSP on a Kotlin fixture. Our [MapStructDriverProcessor] emits a
 *      Java driver interface into [KotlinCompilation.kspSourcesDir]. kotlinc also compiles the
 *      Kotlin fixture to .class files.
 *   2. We then drive `javax.tools.JavaCompiler` directly on the generated Java driver with a
 *      classpath made of (a) kotlinc's output, (b) the mapstruct runtime jar, (c) the kotlin
 *      stdlib, and install a live [MappingProcessor] instance as the annotation processor. The
 *      MapStruct impl is generated, compiled, and written alongside the driver class.
 *   3. A [URLClassLoader] over (kotlinc output + javac output) lets the test reflectively get
 *      the mapper via [Mappers.getMapper] and invoke it against Kotlin data-class instances.
 *
 * Driving javac ourselves (rather than letting kctfork do it) keeps the test honest: we see
 * exactly what the real MapStruct processor sees when javac presents it with the KSP output.
 */
@OptIn(ExperimentalCompilerApi::class)
class EndToEndIntegrationTest {

    @Test
    @DisplayName("Kotlin @Mapper → generated driver → MapStruct impl actually maps a Kotlin data class")
    fun simpleOneToOneMapping() {
        val artefacts = runPipeline(
            SourceFile.kotlin(
                "UserMapper.kt",
                """
                package e2e

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

        val loader = artefacts.classLoader
        val driverCls = loader.loadClass("e2e.UserMapperMapStruct")
        val userCls = loader.loadClass("e2e.User")
        val userDtoCls = loader.loadClass("e2e.UserDto")

        // Sanity: MapStruct's APT actually produced the impl of our driver.
        val implCls = loader.loadClass("e2e.UserMapperMapStructImpl")
        assertThat(driverCls.isAssignableFrom(implCls)).isTrue()

        val mapper = Mappers.getMapper(driverCls)
        val user = userCls.getConstructor(String::class.java, Int::class.javaPrimitiveType)
            .newInstance("Alice", 30)
        val dto = driverCls.getMethod("toDto", userCls).invoke(mapper, user)

        assertThat(userDtoCls.getMethod("getFullName").invoke(dto)).isEqualTo("Alice")
        assertThat(userDtoCls.getMethod("getAge").invoke(dto)).isEqualTo(30)
    }

    @Test
    @DisplayName("bidirectional mapping via @InheritInverseConfiguration survives the pipeline")
    fun bidirectionalMapping() {
        val artefacts = runPipeline(
            SourceFile.kotlin(
                "OrderMapper.kt",
                """
                package e2e.bi

                import org.mapstruct.InheritInverseConfiguration
                import org.mapstruct.Mapper
                import org.mapstruct.Mapping

                data class Order(val id: Long, val customerName: String)
                data class OrderDto(val orderId: Long, val customer: String)

                @Mapper
                interface OrderMapper {
                    @Mapping(target = "orderId", source = "id")
                    @Mapping(target = "customer", source = "customerName")
                    fun toDto(order: Order): OrderDto

                    @InheritInverseConfiguration
                    fun toEntity(dto: OrderDto): Order
                }
                """.trimIndent()
            )
        )

        val loader = artefacts.classLoader
        val driverCls = loader.loadClass("e2e.bi.OrderMapperMapStruct")
        val orderCls = loader.loadClass("e2e.bi.Order")
        val orderDtoCls = loader.loadClass("e2e.bi.OrderDto")
        val mapper = Mappers.getMapper(driverCls)

        val order = orderCls.getConstructor(Long::class.javaPrimitiveType, String::class.java)
            .newInstance(42L, "Bob")
        val dto = driverCls.getMethod("toDto", orderCls).invoke(mapper, order)
        assertThat(orderDtoCls.getMethod("getOrderId").invoke(dto)).isEqualTo(42L)
        assertThat(orderDtoCls.getMethod("getCustomer").invoke(dto)).isEqualTo("Bob")

        val dto2 = orderDtoCls.getConstructor(Long::class.javaPrimitiveType, String::class.java)
            .newInstance(99L, "Carol")
        val order2 = driverCls.getMethod("toEntity", orderDtoCls).invoke(mapper, dto2)
        assertThat(orderCls.getMethod("getId").invoke(order2)).isEqualTo(99L)
        assertThat(orderCls.getMethod("getCustomerName").invoke(order2)).isEqualTo("Carol")
    }

    @Test
    @DisplayName("collection mapping: List<Kotlin> → List<Kotlin> via MapStruct's built-in handling")
    fun listMapping() {
        val artefacts = runPipeline(
            SourceFile.kotlin(
                "ListMapper.kt",
                """
                package e2e.list

                import org.mapstruct.Mapper
                import org.mapstruct.Mapping

                data class Item(val name: String)
                data class ItemDto(val label: String)

                @Mapper
                interface ItemMapper {
                    @Mapping(target = "label", source = "name")
                    fun toDto(item: Item): ItemDto
                    fun toDtos(items: List<Item>): List<ItemDto>
                }
                """.trimIndent()
            )
        )

        val loader = artefacts.classLoader
        val driverCls = loader.loadClass("e2e.list.ItemMapperMapStruct")
        val itemCls = loader.loadClass("e2e.list.Item")
        val itemDtoCls = loader.loadClass("e2e.list.ItemDto")
        val mapper = Mappers.getMapper(driverCls)

        val items = listOf(
            itemCls.getConstructor(String::class.java).newInstance("a"),
            itemCls.getConstructor(String::class.java).newInstance("b")
        )
        @Suppress("UNCHECKED_CAST")
        val dtos = driverCls.getMethod("toDtos", java.util.List::class.java)
            .invoke(mapper, items) as List<Any>
        assertThat(dtos).hasSize(2)
        assertThat(itemDtoCls.getMethod("getLabel").invoke(dtos[0])).isEqualTo("a")
        assertThat(itemDtoCls.getMethod("getLabel").invoke(dtos[1])).isEqualTo("b")
    }

    private data class Artefacts(val classLoader: ClassLoader, val javacOutputDir: File)

    /**
     * Stage 1 — kotlinc + KSP via kctfork.
     * Stage 2 — javac with live [MappingProcessor] on the generated Java driver.
     * Stage 3 — classloader over both outputs.
     */
    private fun runPipeline(vararg sources: SourceFile): Artefacts {
        // 1. Kotlin + KSP
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
        val kspResult = compilation.compile()
        check(kspResult.exitCode == KotlinCompilation.ExitCode.OK) {
            "kotlinc+KSP stage failed:\n${kspResult.messages}"
        }

        val kotlincClasses = File(compilation.workingDir, "classes").also {
            check(it.isDirectory) { "kotlinc did not produce a classes dir at $it" }
        }

        // 2. Run javac with MapStruct APT on the KSP-generated Java driver.
        val generatedJava = compilation.kspSourcesDir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .toList()
        check(generatedJava.isNotEmpty()) {
            "KSP produced no Java sources under ${compilation.kspSourcesDir}"
        }

        val javacOut = File(compilation.workingDir, "javac-out").apply { mkdirs() }
        runJavac(
            javaSources = generatedJava,
            outputDir = javacOut,
            classpath = listOf(kotlincClasses) + testRuntimeClasspath()
        )

        // 3. Compose a classloader that sees both kotlinc and javac outputs.
        val loader = URLClassLoader(
            arrayOf(kotlincClasses.toURI().toURL(), javacOut.toURI().toURL()),
            javaClass.classLoader
        )
        return Artefacts(loader, javacOut)
    }

    private fun runJavac(javaSources: List<File>, outputDir: File, classpath: List<File>) {
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: error("No system Java compiler — run tests on a JDK, not a JRE.")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val fileManager = compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8)
        fileManager.setLocation(StandardLocation.CLASS_PATH, classpath)
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, listOf(outputDir))

        val units = fileManager.getJavaFileObjectsFromFiles(javaSources)
        val task = compiler.getTask(
            /* out */ null,
            fileManager,
            diagnostics,
            /* options */ listOf("-proc:full", "-source", "21", "-target", "21"),
            /* classes */ null,
            units
        )
        // Install a live instance of MapStruct's processor — bypasses classpath discovery so
        // we know exactly which processor ran.
        task.setProcessors(listOf(MappingProcessor()))

        val ok = task.call()
        if (!ok) {
            val msg = diagnostics.diagnostics.joinToString("\n") { d -> d.toString() }
            error("javac + MapStruct APT failed:\n$msg")
        }
        fileManager.close()
    }

    /** Every entry on the test JVM's classpath — jars for mapstruct, kotlin-stdlib, junit, etc. */
    private fun testRuntimeClasspath(): List<File> =
        System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map(::File)
            .filter { it.exists() }
}
