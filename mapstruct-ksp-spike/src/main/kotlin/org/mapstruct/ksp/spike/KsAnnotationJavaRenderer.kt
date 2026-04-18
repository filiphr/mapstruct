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
import com.google.devtools.ksp.symbol.KSValueArgument

/**
 * Renders a [KSAnnotation] observed on a Kotlin declaration as a Java-source annotation usage.
 *
 * The goal of this renderer is faithfulness, not prettiness: the output must be syntactically
 * valid Java that, when placed on a declaration in a generated Java stub, produces an identical
 * annotation at bytecode level to what the original Kotlin source expressed.
 *
 * All type references are emitted as fully-qualified names to avoid import bookkeeping.
 */
class KsAnnotationJavaRenderer {

    fun render(annotation: KSAnnotation): String = buildString { appendAnnotation(annotation) }

    private fun StringBuilder.appendAnnotation(annotation: KSAnnotation) {
        val annotationDecl = annotation.annotationType.resolve().declaration
        val fqn = annotationDecl.qualifiedName?.asString()
            ?: error("Cannot render annotation without qualified name: $annotationDecl")
        append('@').append(fqn)

        val args = annotation.arguments.filter { shouldEmit(it) }
        if (args.isEmpty()) return

        append('(')
        args.forEachIndexed { i, arg ->
            if (i > 0) append(", ")
            appendArgument(arg)
        }
        append(')')
    }

    /**
     * A value of null typically means "no value supplied" — Kotlin annotations don't allow nulls.
     * Some KSP back-ends represent an unset argument as null; skip those.
     */
    private fun shouldEmit(arg: KSValueArgument): Boolean = arg.value != null

    private fun StringBuilder.appendArgument(arg: KSValueArgument) {
        val name = arg.name?.asString()
        if (name != null) append(name).append(" = ")
        appendValue(arg.value!!)
    }

    private fun StringBuilder.appendValue(value: Any) {
        when (value) {
            is String -> append('"').append(escapeStringLiteral(value)).append('"')
            is Boolean -> append(value.toString())
            is Char -> append('\'').append(escapeCharLiteral(value)).append('\'')
            is Byte, is Short, is Int -> append(value.toString())
            is Long -> append(value.toString()).append('L')
            is Float -> append(value.toString()).append('F')
            is Double -> append(value.toString()).append('d')
            is KSType -> appendTypeValue(value)
            is KSAnnotation -> appendAnnotation(value)
            is List<*> -> appendArray(value)
            is Array<*> -> appendArray(value.toList())
            else -> error("Unsupported annotation value kind: ${value::class.qualifiedName} -> $value")
        }
    }

    private fun StringBuilder.appendArray(items: List<*>) {
        append('{')
        if (items.isNotEmpty()) append(' ')
        items.forEachIndexed { i, v ->
            if (i > 0) append(", ")
            requireNotNull(v) { "null inside annotation array is not representable in Java" }
            appendValue(v)
        }
        if (items.isNotEmpty()) append(' ')
        append('}')
    }

    /**
     * A [KSType] in an annotation argument may represent either a class literal (`Foo::class` → `Foo.class`)
     * or an enum entry (`Direction.NORTH` → fully qualified `pkg.Direction.NORTH`). KSP models both
     * through the same [KSType] wrapper; disambiguate via the declaration's [ClassKind].
     */
    private fun StringBuilder.appendTypeValue(type: KSType) {
        val decl = type.declaration
        if (decl !is KSClassDeclaration) {
            val fqn = decl.qualifiedName?.asString()
                ?: error("Cannot render type without qualified name: $decl")
            append(fqn).append(".class")
            return
        }

        when (decl.classKind) {
            ClassKind.ENUM_ENTRY -> {
                val enumDecl = decl.parentDeclaration as? KSClassDeclaration
                    ?: error("Enum entry without enclosing enum: $decl")
                val enumFqn = enumDecl.qualifiedName?.asString()
                    ?: error("Enum without qualified name: $enumDecl")
                append(enumFqn).append('.').append(decl.simpleName.asString())
            }
            else -> {
                val fqn = decl.qualifiedName?.asString()
                    ?: error("Class without qualified name: $decl")
                append(fqn).append(".class")
            }
        }
    }

    private fun escapeStringLiteral(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
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
