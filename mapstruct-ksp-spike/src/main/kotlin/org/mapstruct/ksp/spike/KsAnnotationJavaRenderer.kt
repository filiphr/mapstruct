/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ksp.spike

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.palantir.javapoet.AnnotationSpec
import com.palantir.javapoet.ClassName
import com.palantir.javapoet.CodeBlock

/**
 * Translates a [KSAnnotation] observed on a Kotlin declaration into a JavaPoet [AnnotationSpec].
 *
 * The generator built on top of this renderer will assemble [AnnotationSpec]s into
 * [com.palantir.javapoet.MethodSpec]s and [com.palantir.javapoet.TypeSpec]s to produce the Java
 * driver interface that shadows a Kotlin `@Mapper`. Using JavaPoet consistently across the
 * annotation, method-signature, and type-declaration layers buys us proper import handling,
 * nullability annotations, generics, and formatting for free at the next step.
 *
 * This class owns only one job: the value-kind dispatch that maps KSP annotation argument values
 * to JavaPoet [CodeBlock]s. Everything else — import bookkeeping, emission — is JavaPoet's.
 */
class KsAnnotationJavaRenderer {

    /**
     * Convenience for tests and simple callers: render the annotation to a standalone Java source
     * fragment. Production use should prefer [toAnnotationSpec] and let JavaPoet compose it with
     * surrounding types.
     */
    fun render(annotation: KSAnnotation): String = toAnnotationSpec(annotation).toString()

    fun toAnnotationSpec(annotation: KSAnnotation): AnnotationSpec {
        val annotationDecl = annotation.annotationType.resolve().declaration as? KSClassDeclaration
            ?: error("Annotation type is not a class declaration: ${annotation.annotationType}")
        val builder = AnnotationSpec.builder(classNameOf(annotationDecl))
        for (arg in annotation.arguments) {
            val value = arg.value ?: continue
            // Java annotations support a positional-style shortcut only for `value`; emit the
            // unnamed KSP arg there so `@Foo("x")` round-trips. Any other unnamed arg is rare
            // enough that an explicit `value = ...` fallback is acceptable.
            val name = arg.name?.asString() ?: "value"
            builder.addMember(name, renderValue(value))
        }
        return builder.build()
    }

    private fun renderValue(value: Any): CodeBlock = when (value) {
        is String -> CodeBlock.of("\$S", value)
        is Boolean -> CodeBlock.of("\$L", value)
        is Byte, is Short, is Int -> CodeBlock.of("\$L", value)
        is Long -> CodeBlock.of("\$L", "${value}L")
        is Float -> CodeBlock.of("\$L", "${value}F")
        is Double -> CodeBlock.of("\$L", "${value}d")
        is Char -> CodeBlock.of("\$L", "'${escapeCharLiteral(value)}'")
        is KSType -> renderTypeValue(value)
        // Real KSP delivers enum entries (and sometimes class literals) directly as KSClassDeclaration.
        // The KSP API contract: `KSValueArgument.value` may be a KSType *or* a KSClassDeclaration.
        is KSClassDeclaration -> renderClassDeclValue(value)
        is KSAnnotation -> CodeBlock.of("\$L", toAnnotationSpec(value))
        is List<*> -> renderArray(value)
        is Array<*> -> renderArray(value.toList())
        else -> error("Unsupported annotation value kind: ${value::class.qualifiedName} -> $value")
    }

    /**
     * Arrays are emitted as a single [CodeBlock] (`{ a, b, c }` or `{}`) rather than by calling
     * [AnnotationSpec.Builder.addMember] multiple times with the same name. Two reasons:
     *  - We control empty-array output (JavaPoet would omit the member entirely otherwise).
     *  - We avoid JavaPoet's automatic multi-line formatting for repeated same-name members,
     *    which inflates diffs in tests and generated-source review.
     */
    private fun renderArray(items: List<*>): CodeBlock {
        if (items.isEmpty()) return CodeBlock.of("{}")
        val inner = items.map { v ->
            requireNotNull(v) { "null inside annotation array is not representable in Java" }
            renderValue(v)
        }
        val builder = CodeBlock.builder().add("{ ")
        inner.forEachIndexed { i, cb ->
            if (i > 0) builder.add(", ")
            builder.add(cb)
        }
        return builder.add(" }").build()
    }

    /**
     * A [KSType] in an annotation argument is either a class literal (`Foo::class` → `Foo.class`)
     * or an enum entry (`Direction.NORTH` → fully qualified `pkg.Direction.NORTH`). Disambiguate
     * through the declaration's [ClassKind].
     */
    private fun renderTypeValue(type: KSType): CodeBlock {
        val decl = type.declaration as? KSClassDeclaration
            ?: error("Unsupported type declaration: ${type.declaration}")
        return renderClassDeclValue(decl)
    }

    private fun renderClassDeclValue(decl: KSClassDeclaration): CodeBlock = when (decl.classKind) {
        ClassKind.ENUM_ENTRY -> {
            val enumDecl = decl.parentDeclaration as? KSClassDeclaration
                ?: error("Enum entry without enclosing enum: $decl")
            CodeBlock.of("\$T.\$L", classNameOf(enumDecl), decl.simpleName.asString())
        }
        else -> CodeBlock.of("\$T.class", classNameOf(decl))
    }

    /**
     * Build a JavaPoet [ClassName] for a declaration. Walks `parentDeclaration` to support
     * nested classes (e.g. `Outer.Inner`), using the top-level declaration's package.
     */
    private fun classNameOf(decl: KSClassDeclaration): ClassName {
        val chain = ArrayDeque<String>()
        var current: KSClassDeclaration? = decl
        while (current != null) {
            chain.addFirst(current.simpleName.asString())
            val parent = current.parentDeclaration
            current = parent as? KSClassDeclaration
                ?: if (parent == null) null
                   else error("Unsupported enclosing declaration: $parent")
        }
        val topLevelDecl = generateSequence(decl) { it.parentDeclaration as? KSClassDeclaration }
            .last()
        val pkg = topLevelDecl.packageName.asString()
        val top = chain.removeFirst()
        val nested = chain.toTypedArray()
        return ClassName.get(pkg, top, *nested)
    }

    private fun escapeCharLiteral(c: Char): String = when (c) {
        '\\' -> "\\\\"
        '\'' -> "\\'"
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        '\b' -> "\\b"
        '\u000C' -> "\\f"
        else -> if (c.code < 0x20) "\\u%04x".format(c.code) else c.toString()
    }
}
