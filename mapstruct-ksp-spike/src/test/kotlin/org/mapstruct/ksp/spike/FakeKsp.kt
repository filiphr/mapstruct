/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ksp.spike

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueArgument
import java.lang.reflect.Proxy
import kotlin.reflect.KClass

/**
 * Tiny test doubles for the subset of KSP interfaces that [KsAnnotationJavaRenderer] touches.
 *
 * We use [Proxy] rather than hand-stubbing every inherited member (KSAnnotation alone has ~9 via
 * KSNode/KSAnnotated): the renderer only calls a handful of methods per interface, and the proxy
 * approach keeps the harness short enough that the test fixtures stay readable.
 *
 * Unstubbed calls fail loudly so a renderer that accidentally reaches for more surface than it
 * needs will be caught by tests instead of silently returning sentinel values.
 */
internal object FakeKsp {

    private fun <T : Any> proxy(cls: KClass<T>, handlers: Map<String, (Array<Any?>?) -> Any?>): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(cls.java.classLoader, arrayOf(cls.java)) { self, method, args ->
            // containsKey, not lookup — a stub returning null must not fall through to "unstubbed".
            if (handlers.containsKey(method.name)) {
                handlers.getValue(method.name).invoke(args)
            } else when (method.name) {
                "toString" -> "<fake ${cls.simpleName}>"
                "hashCode" -> System.identityHashCode(self)
                "equals" -> args?.get(0) === self
                else -> throw NotImplementedError(
                    "Unstubbed call on fake ${cls.simpleName}: ${method.name}"
                )
            }
        } as T
    }

    fun ksName(value: String): KSName = proxy(KSName::class, mapOf(
        "asString" to { value },
        "getQualifier" to { value.substringBeforeLast('.', "") },
        "getShortName" to { value.substringAfterLast('.') }
    ))

    fun ksClassDecl(
        fqn: String,
        kind: ClassKind = ClassKind.CLASS,
        parent: KSClassDeclaration? = null
    ): KSClassDeclaration {
        val simple = fqn.substringAfterLast('.')
        return proxy(KSClassDeclaration::class, mapOf(
            "getQualifiedName" to { ksName(fqn) },
            "getSimpleName" to { ksName(simple) },
            "getClassKind" to { kind },
            "getParentDeclaration" to { parent }
        ))
    }

    fun ksType(decl: KSDeclaration): KSType = proxy(KSType::class, mapOf(
        "getDeclaration" to { decl }
    ))

    fun ksTypeRef(decl: KSDeclaration): KSTypeReference = proxy(KSTypeReference::class, mapOf(
        "resolve" to { ksType(decl) }
    ))

    fun ksValueArg(name: String?, value: Any?): KSValueArgument = proxy(KSValueArgument::class, mapOf(
        "getName" to { name?.let(::ksName) },
        "getValue" to { value },
        "isSpread" to { false }
    ))

    fun ksAnnotation(fqn: String, args: List<Pair<String?, Any?>> = emptyList()): KSAnnotation {
        val decl = ksClassDecl(fqn, ClassKind.ANNOTATION_CLASS)
        val ksArgs = args.map { (n, v) -> ksValueArg(n, v) }
        return proxy(KSAnnotation::class, mapOf(
            "getAnnotationType" to { ksTypeRef(decl) },
            "getArguments" to { ksArgs },
            "getDefaultArguments" to { emptyList<KSValueArgument>() },
            "getShortName" to { ksName(fqn.substringAfterLast('.')) },
            "getUseSiteTarget" to { null }
        ))
    }

    /** A [KSType] value suitable for a `Foo::class` annotation argument. */
    fun classLiteral(fqn: String, kind: ClassKind = ClassKind.CLASS): KSType =
        ksType(ksClassDecl(fqn, kind))

    /** A [KSType] value suitable for an enum-entry annotation argument (e.g. `Policy.WARN`). */
    fun enumEntry(enumFqn: String, entryName: String): KSType {
        val enumDecl = ksClassDecl(enumFqn, ClassKind.ENUM_CLASS)
        val entry = ksClassDecl("$enumFqn.$entryName", ClassKind.ENUM_ENTRY, parent = enumDecl)
        return ksType(entry)
    }
}
