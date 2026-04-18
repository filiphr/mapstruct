/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ksp.spike

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mapstruct.ksp.spike.FakeKsp.classLiteral
import org.mapstruct.ksp.spike.FakeKsp.enumEntry
import org.mapstruct.ksp.spike.FakeKsp.ksAnnotation

/**
 * Spike goal: prove that every annotation-value shape MapStruct puts on a @Mapper (and nested
 * @Mapping/@BeanMapping/etc.) can be rendered to syntactically valid Java annotation source.
 *
 * The fixtures intentionally mirror real MapStruct annotation shapes (see org.mapstruct.Mapper,
 * org.mapstruct.Mapping, org.mapstruct.BeanMapping) so that once the full hybrid path is built,
 * we know the renderer will cover the annotations actually encountered.
 */
class KsAnnotationJavaRendererTest {

    private val renderer = KsAnnotationJavaRenderer()

    @Test
    @DisplayName("bare annotation without arguments")
    fun bareAnnotation() {
        val out = renderer.render(ksAnnotation("org.mapstruct.Mapper"))
        assertThat(out).isEqualTo("@org.mapstruct.Mapper")
    }

    @Test
    @DisplayName("string argument — named, with escapes")
    fun stringArgument() {
        val out = renderer.render(
            ksAnnotation("org.mapstruct.Mapping", listOf("target" to "full\"name\nwith\\slashes"))
        )
        assertThat(out).isEqualTo(
            """@org.mapstruct.Mapping(target = "full\"name\nwith\\slashes")"""
        )
    }

    @Test
    @DisplayName("primitive arguments across all JVM numeric types")
    fun primitiveArguments() {
        val out = renderer.render(
            ksAnnotation(
                "example.Primitives",
                listOf(
                    "b" to true,
                    "by" to 7.toByte(),
                    "sh" to 300.toShort(),
                    "i" to 42,
                    "l" to 123L,
                    "f" to 1.5f,
                    "d" to 2.5,
                    "c" to 'x'
                )
            )
        )
        assertThat(out).isEqualTo(
            "@example.Primitives(b = true, by = 7, sh = 300, i = 42, l = 123L, f = 1.5F, d = 2.5d, c = 'x')"
        )
    }

    @Test
    @DisplayName("char argument with escape")
    fun charArgumentEscaped() {
        val out = renderer.render(ksAnnotation("example.A", listOf("c" to '\n')))
        assertThat(out).isEqualTo("@example.A(c = '\\n')")
    }

    @Test
    @DisplayName("enum argument resolves to fully qualified enum entry")
    fun enumArgument() {
        val out = renderer.render(
            ksAnnotation(
                "org.mapstruct.Mapper",
                listOf("unmappedTargetPolicy" to enumEntry("org.mapstruct.ReportingPolicy", "ERROR"))
            )
        )
        assertThat(out).isEqualTo(
            "@org.mapstruct.Mapper(unmappedTargetPolicy = org.mapstruct.ReportingPolicy.ERROR)"
        )
    }

    @Test
    @DisplayName("class-literal argument resolves to Foo.class")
    fun classLiteralArgument() {
        val out = renderer.render(
            ksAnnotation(
                "org.mapstruct.BeanMapping",
                listOf("resultType" to classLiteral("com.example.UserDtoImpl"))
            )
        )
        assertThat(out).isEqualTo(
            "@org.mapstruct.BeanMapping(resultType = com.example.UserDtoImpl.class)"
        )
    }

    @Test
    @DisplayName("nested annotation argument (like @Mapping.qualifiedBy)")
    fun nestedAnnotationArgument() {
        val nested = ksAnnotation(
            "com.example.Named",
            listOf("value" to "trim")
        )
        val out = renderer.render(
            ksAnnotation(
                "org.mapstruct.Mapping",
                listOf(
                    "target" to "name",
                    "source" to "fullName",
                    "qualifiedBy" to nested
                )
            )
        )
        assertThat(out).isEqualTo(
            """@org.mapstruct.Mapping(target = "name", source = "fullName", """ +
                """qualifiedBy = @com.example.Named(value = "trim"))"""
        )
    }

    @Test
    @DisplayName("array of strings")
    fun arrayOfStrings() {
        val out = renderer.render(
            ksAnnotation("example.A", listOf("tags" to listOf("a", "b", "c")))
        )
        assertThat(out).isEqualTo("""@example.A(tags = { "a", "b", "c" })""")
    }

    @Test
    @DisplayName("empty array")
    fun emptyArray() {
        val out = renderer.render(ksAnnotation("example.A", listOf("tags" to emptyList<Any>())))
        assertThat(out).isEqualTo("@example.A(tags = {})")
    }

    @Test
    @DisplayName("array of class literals (like @Mapper.uses)")
    fun arrayOfClassLiterals() {
        val out = renderer.render(
            ksAnnotation(
                "org.mapstruct.Mapper",
                listOf(
                    "uses" to listOf(
                        classLiteral("com.example.StringUtils"),
                        classLiteral("com.example.DateUtils")
                    )
                )
            )
        )
        assertThat(out).isEqualTo(
            "@org.mapstruct.Mapper(uses = { com.example.StringUtils.class, com.example.DateUtils.class })"
        )
    }

    @Test
    @DisplayName("array of enums")
    fun arrayOfEnums() {
        val out = renderer.render(
            ksAnnotation(
                "example.A",
                listOf(
                    "policies" to listOf(
                        enumEntry("org.mapstruct.ReportingPolicy", "WARN"),
                        enumEntry("org.mapstruct.ReportingPolicy", "ERROR")
                    )
                )
            )
        )
        assertThat(out).isEqualTo(
            "@example.A(policies = { org.mapstruct.ReportingPolicy.WARN, org.mapstruct.ReportingPolicy.ERROR })"
        )
    }

    @Test
    @DisplayName("array of nested annotations (the @Mappings({...}) case)")
    fun arrayOfNestedAnnotations() {
        val out = renderer.render(
            ksAnnotation(
                "org.mapstruct.Mappings",
                listOf(
                    "value" to listOf(
                        ksAnnotation(
                            "org.mapstruct.Mapping",
                            listOf("target" to "a", "source" to "x")
                        ),
                        ksAnnotation(
                            "org.mapstruct.Mapping",
                            listOf("target" to "b", "source" to "y")
                        )
                    )
                )
            )
        )
        assertThat(out).isEqualTo(
            """@org.mapstruct.Mappings(value = { """ +
                """@org.mapstruct.Mapping(target = "a", source = "x"), """ +
                """@org.mapstruct.Mapping(target = "b", source = "y") })"""
        )
    }

    @Test
    @DisplayName("arguments with null value are skipped")
    fun nullValueArgumentsAreSkipped() {
        val out = renderer.render(
            ksAnnotation(
                "example.A",
                listOf("present" to "v", "absent" to null, "alsoPresent" to 1)
            )
        )
        assertThat(out).isEqualTo("""@example.A(present = "v", alsoPresent = 1)""")
    }

    @Test
    @DisplayName("positional (unnamed) argument — rendered without a name, as Java annotation's value shortcut")
    fun positionalArgument() {
        val out = renderer.render(ksAnnotation("example.Single", listOf(null to "only")))
        assertThat(out).isEqualTo("""@example.Single("only")""")
    }

    @Test
    @DisplayName("full-featured annotation mirroring real @Mapper usage from Kotlin source")
    fun realisticMapperAnnotation() {
        val out = renderer.render(
            ksAnnotation(
                "org.mapstruct.Mapper",
                listOf(
                    "componentModel" to "spring",
                    "uses" to listOf(
                        classLiteral("com.example.StringUtils"),
                        classLiteral("com.example.DateUtils")
                    ),
                    "unmappedTargetPolicy" to enumEntry("org.mapstruct.ReportingPolicy", "ERROR"),
                    "nullValueCheckStrategy" to enumEntry(
                        "org.mapstruct.NullValueCheckStrategy",
                        "ALWAYS"
                    )
                )
            )
        )
        assertThat(out).isEqualTo(
            """@org.mapstruct.Mapper(componentModel = "spring", """ +
                """uses = { com.example.StringUtils.class, com.example.DateUtils.class }, """ +
                """unmappedTargetPolicy = org.mapstruct.ReportingPolicy.ERROR, """ +
                """nullValueCheckStrategy = org.mapstruct.NullValueCheckStrategy.ALWAYS)"""
        )
    }

    @Test
    @DisplayName("unsupported value kind fails loudly rather than silently misrendering")
    fun unsupportedValueThrows() {
        // A raw java.util.Date isn't a valid KSP annotation value shape — renderer should complain.
        val bogus = java.util.Date()
        assertThrows<IllegalStateException> {
            renderer.render(ksAnnotation("example.A", listOf("when" to bogus)))
        }
    }
}
