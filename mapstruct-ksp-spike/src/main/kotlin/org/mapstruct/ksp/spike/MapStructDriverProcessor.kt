/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ksp.spike

import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.validate
import com.palantir.javapoet.ClassName
import com.palantir.javapoet.JavaFile
import com.palantir.javapoet.MethodSpec
import com.palantir.javapoet.ParameterSpec
import com.palantir.javapoet.TypeSpec
import javax.lang.model.element.Modifier

/**
 * KSP processor that scans for Kotlin `@org.mapstruct.Mapper` interfaces and emits a Java
 * "driver" interface for each. The driver:
 *  - extends the original Kotlin interface (so its impl satisfies the Kotlin contract),
 *  - carries the same MapStruct annotations (rendered via [KsAnnotationJavaRenderer]),
 *  - mirrors each abstract method with Java-typed parameter and return types.
 *
 * The driver file is written with extension `"java"`, which per KSP's `CodeGenerator` contract
 * means it participates in subsequent javac compilation. MapStruct's existing javac annotation
 * processor then sees a perfectly normal `@Mapper` Java interface and generates the impl as
 * usual — the impl, transitively, satisfies the original Kotlin interface.
 *
 * Scope of this iteration: interfaces (no abstract classes), abstract methods only, no type
 * parameters, no nullability propagation. Classes that hit those limits are reported via the
 * KSP logger; nothing is silently dropped.
 */
class MapStructDriverProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    private val annotationRenderer = KsAnnotationJavaRenderer()
    private val typeTranslator = KsTypeToJavaPoet()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val mappers = resolver.getSymbolsWithAnnotation(MAPPER_ANNOTATION_FQN).toList()

        // Defer symbols whose dependencies (referenced types in parameters/returns) aren't yet
        // resolvable — typically because they're being generated in the same round.
        val (ready, deferred) = mappers.partition { it.validate() }
        ready.filterIsInstance<KSClassDeclaration>().forEach { tryGenerate(it) }
        return deferred
    }

    private fun tryGenerate(mapper: KSClassDeclaration) {
        try {
            generateDriver(mapper)
        } catch (e: Exception) {
            logger.error(
                "mapstruct-ksp: failed to generate driver for " +
                    "${mapper.qualifiedName?.asString()}: ${e.message}",
                mapper
            )
        }
    }

    private fun generateDriver(mapper: KSClassDeclaration) {
        if (mapper.classKind != ClassKind.INTERFACE) {
            logger.error(
                "mapstruct-ksp: @Mapper on ${mapper.qualifiedName?.asString()} — only interfaces " +
                    "are supported in this iteration. Abstract-class @Mapper support is TODO.",
                mapper
            )
            return
        }
        if (mapper.typeParameters.isNotEmpty()) {
            logger.error(
                "mapstruct-ksp: @Mapper on ${mapper.qualifiedName?.asString()} — generic mapper " +
                    "interfaces are not supported in this iteration.",
                mapper
            )
            return
        }

        val pkg = mapper.packageName.asString()
        val driverSimpleName = mapper.simpleName.asString() + DRIVER_SUFFIX
        val originalType = ClassName.get(pkg, mapper.simpleName.asString())

        val typeBuilder = TypeSpec.interfaceBuilder(driverSimpleName)
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(originalType)
            .addJavadoc(
                "Generated driver for {@link \$T}. Do not edit.\n" +
                    "MapStruct's javac processor will produce an implementation of this interface\n" +
                    "which transitively implements the Kotlin source interface.\n",
                originalType
            )

        mapper.annotations
            .filter { isMapStructAnnotation(it) }
            .forEach { typeBuilder.addAnnotation(annotationRenderer.toAnnotationSpec(it)) }

        mapper.declarations
            .filterIsInstance<KSFunctionDeclaration>()
            .filter { it.isAbstract }
            .forEach { fn -> typeBuilder.addMethod(createDriverMethod(fn)) }

        val javaFile = JavaFile.builder(pkg, typeBuilder.build())
            .skipJavaLangImports(true)
            .indent("    ")
            .build()

        val containingFile = mapper.containingFile
        val deps = if (containingFile != null) {
            Dependencies(aggregating = false, containingFile)
        } else {
            Dependencies(aggregating = false)
        }

        codeGenerator.createNewFile(deps, pkg, driverSimpleName, "java")
            .bufferedWriter(Charsets.UTF_8)
            .use { javaFile.writeTo(it) }
    }

    private fun createDriverMethod(fn: KSFunctionDeclaration): MethodSpec {
        val builder = MethodSpec.methodBuilder(fn.simpleName.asString())
            .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
            .addAnnotation(Override::class.java)

        fn.annotations
            .filter { isMapStructAnnotation(it) }
            .forEach { builder.addAnnotation(annotationRenderer.toAnnotationSpec(it)) }

        val returnType = fn.returnType?.resolve()
            ?: error("Cannot resolve return type of ${fn.qualifiedName?.asString()}")
        builder.returns(typeTranslator.toReturnTypeName(returnType))

        fn.parameters.forEach { p ->
            val pname = p.name?.asString()
                ?: error("Unnamed parameter in ${fn.qualifiedName?.asString()}")
            val ptype = p.type.resolve()
            val ps = ParameterSpec.builder(typeTranslator.toTypeName(ptype), pname)
            p.annotations
                .filter { isMapStructAnnotation(it) }
                .forEach { ps.addAnnotation(annotationRenderer.toAnnotationSpec(it)) }
            builder.addParameter(ps.build())
        }

        return builder.build()
    }

    private fun isMapStructAnnotation(annotation: KSAnnotation): Boolean {
        val fqn = annotation.annotationType.resolve().declaration.qualifiedName?.asString()
            ?: return false
        return fqn.startsWith("org.mapstruct.")
    }

    companion object {
        const val MAPPER_ANNOTATION_FQN = "org.mapstruct.Mapper"
        const val DRIVER_SUFFIX = "MapStruct"
    }
}

class MapStructDriverProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        MapStructDriverProcessor(environment.codeGenerator, environment.logger)
}
