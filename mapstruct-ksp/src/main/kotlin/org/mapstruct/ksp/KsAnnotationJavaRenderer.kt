/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ksp

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueArgument
import com.google.devtools.ksp.symbol.Origin
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

        val defaultsByName = annotation.defaultArguments
            .mapNotNull { it.name?.asString()?.let { n -> n to it.value } }
            .toMap()

        for (arg in annotation.arguments) {
            val value = arg.value ?: continue
            if (!isUserSupplied(arg, value, defaultsByName)) continue
            // Java annotations support a positional-style shortcut only for `value`; emit the
            // unnamed KSP arg there so `@Foo("x")` round-trips. Any other unnamed arg is rare
            // enough that an explicit `value = ...` fallback is acceptable.
            val name = arg.name?.asString() ?: "value"
            builder.addMember(name, renderValue(value))
        }
        return builder.build()
    }

    /**
     * Decide whether [arg] was written by the user or synthesized by KSP from the annotation
     * type's declared defaults. Two signals, used in order:
     *
     *  1. [KSValueArgument.origin]: KSP2 marks materialized defaults as [Origin.SYNTHETIC]. Fast
     *     and unambiguous when present. (KSP1 only returned user-supplied args at all, so this
     *     path is redundant but harmless there.)
     *  2. Value-level comparison against [KSAnnotation.defaultArguments]. Belt-and-braces for
     *     implementations that don't set `SYNTHETIC` — also drops the rare case where the user
     *     explicitly wrote a value that matches the default, which is semantically lossless.
     *
     *  If neither signal fires (no default declared for this name), emit the argument.
     */
    private fun isUserSupplied(
        arg: KSValueArgument,
        value: Any,
        defaultsByName: Map<String, Any?>
    ): Boolean {
        if (arg.origin == Origin.SYNTHETIC) return false
        val name = arg.name?.asString() ?: return true
        if (!defaultsByName.containsKey(name)) return true
        return !valuesEqual(value, defaultsByName[name])
    }

    /**
     * Semantic equality across the value shapes KSP passes through [KSValueArgument.value]:
     * primitives compare via `equals`; KSP symbol references compare by qualified name; nested
     * annotations compare structurally; arrays compare pairwise.
     *
     * We intentionally don't rely on `KSType.equals` / `KSClassDeclaration.equals` — KSP doesn't
     * guarantee those are value-based, and implementations have been observed to return distinct
     * instances for "the same" type resolved twice.
     */
    private fun valuesEqual(a: Any?, b: Any?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false

        // Cross-shape: a class reference may arrive as KSType on one side and KSClassDeclaration
        // on the other (see KSValueArgument.value contract). Canonicalise both to a FQN.
        val aName = classReferenceFqn(a)
        val bName = classReferenceFqn(b)
        if (aName != null && bName != null) return aName == bName

        return when (a) {
            is KSAnnotation -> b is KSAnnotation && annotationsEqual(a, b)
            is List<*> -> b is List<*> && a.size == b.size &&
                a.zip(b).all { (x, y) -> valuesEqual(x, y) }
            is Array<*> -> b is Array<*> && a.size == b.size &&
                a.zip(b).all { (x, y) -> valuesEqual(x, y) }
            else -> a == b
        }
    }

    private fun classReferenceFqn(v: Any): String? = when (v) {
        is KSClassDeclaration -> v.qualifiedName?.asString()
        is KSType -> (v.declaration as? KSClassDeclaration)?.qualifiedName?.asString()
        is Enum<*> -> v.declaringJavaClass.name + "." + v.name
        else -> null
    }

    private fun enumClassName(value: Enum<*>): ClassName {
        val cls = value.declaringJavaClass
        val pkg = cls.getPackage()?.name ?: ""
        val name = if (pkg.isEmpty()) cls.name else cls.name.removePrefix("$pkg.")
        return ClassName.get(pkg, name)
    }

    private fun annotationsEqual(a: KSAnnotation, b: KSAnnotation): Boolean {
        val aType = a.annotationType.resolve().declaration.qualifiedName?.asString()
        val bType = b.annotationType.resolve().declaration.qualifiedName?.asString()
        if (aType == null || aType != bType) return false
        val aArgs = a.arguments.associate { (it.name?.asString() ?: "value") to it.value }
        val bArgs = b.arguments.associate { (it.name?.asString() ?: "value") to it.value }
        if (aArgs.keys != bArgs.keys) return false
        return aArgs.all { (name, v) -> valuesEqual(v, bArgs[name]) }
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
        // When KSP sees an annotation on an already-compiled classpath class (.class bytecode),
        // enum-typed arguments materialise as the actual Enum instance rather than a KSP symbol.
        // Guard that shape defensively — rare in normal source-only runs, common in multi-round
        // processing over generated files.
        is Enum<*> -> CodeBlock.of("\$T.\$L", enumClassName(value), value.name)
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
