/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ksp.spike

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Nullability
import com.palantir.javapoet.ClassName
import com.palantir.javapoet.ParameterizedTypeName
import com.palantir.javapoet.TypeName

/**
 * Translates a [KSType] (a Kotlin-source-level type) into a JavaPoet [TypeName] suitable for
 * appearing in the generated Java driver interface that javac will compile.
 *
 * The translation rule is "what would Kotlin emit at the JVM level for this position":
 *  - Non-null Kotlin primitive types in direct method-signature positions become JVM primitives
 *    (`kotlin.Int` → `int`, `kotlin.Boolean` → `boolean`, …). This matches the JVM signature
 *    Kotlin itself produces, so our driver's override signature lines up with the parent method's.
 *  - Nullable primitives and primitives nested in generic arguments become their boxed form
 *    (`kotlin.Int?` → `java.lang.Integer`; `List<Int>` → `java.util.List<java.lang.Integer>`),
 *    because the JVM can't nullable-box primitives and can't hold primitives in generics.
 *  - Kotlin built-ins and collection types map to their JVM platform equivalents
 *    (`kotlin.String` → `java.lang.String`, `kotlin.collections.List` → `java.util.List`, …).
 *  - `kotlin.Unit` returns become Java `void`.
 *  - User-defined classes pass through with their `qualifiedName`.
 *  - Generic type arguments recurse; variance is dropped for the spike.
 *
 * Out of scope (will fail loudly): type parameters, function types, intersection types, dynamic,
 * specialised array types (`IntArray`, `Array<T>` etc. — they compile to JVM `int[]`/`T[]`).
 */
class KsTypeToJavaPoet {

    fun toReturnTypeName(type: KSType): TypeName {
        val fqn = (type.declaration as? KSClassDeclaration)?.qualifiedName?.asString()
        if (fqn == "kotlin.Unit") return TypeName.VOID
        return toTypeName(type, boxPrimitives = false)
    }

    fun toTypeName(type: KSType): TypeName = toTypeName(type, boxPrimitives = false)

    /**
     * @param boxPrimitives when true, force boxed types even for non-null Kotlin primitives.
     *   Used for generic type arguments, where the JVM can't hold primitives.
     */
    private fun toTypeName(type: KSType, boxPrimitives: Boolean): TypeName {
        val decl = type.declaration as? KSClassDeclaration
            ?: error("Unsupported type declaration kind: ${type.declaration} (${type.declaration::class.simpleName})")
        val fqn = decl.qualifiedName?.asString()
            ?: error("Type without qualified name: $decl")

        // Non-null primitive in a direct position: use JVM primitive so override signatures line
        // up with the Kotlin parent's bytecode. `T?` and `List<T>` both must stay boxed.
        if (!boxPrimitives && type.nullability == Nullability.NOT_NULL) {
            KOTLIN_PRIMITIVES[fqn]?.let { return it }
        }

        val baseClassName = baseClassNameFor(fqn, decl)
        val args = type.arguments
        if (args.isEmpty()) return baseClassName

        val typeArgs = args.map { arg ->
            val argRef = arg.type
                ?: error("Star projections are not yet supported: $type")
            toTypeName(argRef.resolve(), boxPrimitives = true)
        }
        return ParameterizedTypeName.get(baseClassName, *typeArgs.toTypedArray())
    }

    private fun baseClassNameFor(fqn: String, decl: KSClassDeclaration): ClassName {
        // Kotlin built-in? Use the JVM equivalent rather than `decl.packageName`/`decl.simpleName`,
        // which for `kotlin.String` are "kotlin"/"String" — wrong for Java consumers.
        KOTLIN_TO_JAVA[fqn]?.let { return classNameFromFqn(it) }

        // User type: use the declaration's package directly so nested classes (rare for mappers
        // but possible for nullable parameter types) round-trip correctly.
        return ClassName.get(decl.packageName.asString(), decl.simpleName.asString())
    }

    private fun classNameFromFqn(fqn: String): ClassName {
        val pkg = fqn.substringBeforeLast('.', "")
        val simple = fqn.substringAfterLast('.')
        return ClassName.get(pkg, simple)
    }

    private companion object {
        /**
         * Kotlin primitive FQNs → JavaPoet [TypeName] primitives. Used when the position allows
         * a primitive (direct method return / parameter, non-null).
         */
        val KOTLIN_PRIMITIVES: Map<String, TypeName> = mapOf(
            "kotlin.Boolean" to TypeName.BOOLEAN,
            "kotlin.Byte" to TypeName.BYTE,
            "kotlin.Short" to TypeName.SHORT,
            "kotlin.Int" to TypeName.INT,
            "kotlin.Long" to TypeName.LONG,
            "kotlin.Float" to TypeName.FLOAT,
            "kotlin.Double" to TypeName.DOUBLE,
            "kotlin.Char" to TypeName.CHAR
        )

        /**
         * Mapping from Kotlin built-in / collection types to their JVM platform equivalents.
         * Mutable variants intentionally collapse into the same Java type — the generated driver
         * interface only ever appears in JVM signature positions where the distinction is gone.
         * Primitive types are listed here in their boxed form for nullable / generic-arg positions.
         */
        val KOTLIN_TO_JAVA: Map<String, String> = mapOf(
            "kotlin.String" to "java.lang.String",
            "kotlin.CharSequence" to "java.lang.CharSequence",
            "kotlin.Any" to "java.lang.Object",
            "kotlin.Number" to "java.lang.Number",
            "kotlin.Throwable" to "java.lang.Throwable",
            "kotlin.Comparable" to "java.lang.Comparable",
            "kotlin.Boolean" to "java.lang.Boolean",
            "kotlin.Byte" to "java.lang.Byte",
            "kotlin.Short" to "java.lang.Short",
            "kotlin.Int" to "java.lang.Integer",
            "kotlin.Long" to "java.lang.Long",
            "kotlin.Float" to "java.lang.Float",
            "kotlin.Double" to "java.lang.Double",
            "kotlin.Char" to "java.lang.Character",

            "kotlin.collections.Iterable" to "java.lang.Iterable",
            "kotlin.collections.MutableIterable" to "java.lang.Iterable",
            "kotlin.collections.Collection" to "java.util.Collection",
            "kotlin.collections.MutableCollection" to "java.util.Collection",
            "kotlin.collections.List" to "java.util.List",
            "kotlin.collections.MutableList" to "java.util.List",
            "kotlin.collections.Set" to "java.util.Set",
            "kotlin.collections.MutableSet" to "java.util.Set",
            "kotlin.collections.Map" to "java.util.Map",
            "kotlin.collections.MutableMap" to "java.util.Map",
            "kotlin.collections.Map.Entry" to "java.util.Map.Entry",
            "kotlin.collections.MutableMap.MutableEntry" to "java.util.Map.Entry"
        )
    }
}
