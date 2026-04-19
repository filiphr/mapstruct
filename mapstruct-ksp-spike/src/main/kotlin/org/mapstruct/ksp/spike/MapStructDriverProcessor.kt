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
import com.google.devtools.ksp.symbol.Modifier as KSModifier
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.validate
import com.palantir.javapoet.AnnotationSpec
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
        // Only process @Mapper declarations originating from Kotlin source. Our own generated
        // driver interfaces also carry @Mapper (on purpose — javac's MapStruct processor needs
        // to see it); without this filter, KSP would pick them up in the next round and emit
        // FooMapStructMapStruct, unbounded. Declarations from .class files on the classpath and
        // from other generated Java are also skipped — only Kotlin sources in the compilation
        // unit are in scope for this processor.
        val kotlinSourceMappers = resolver.getSymbolsWithAnnotation(MAPPER_ANNOTATION_FQN)
            .filter { it.origin == Origin.KOTLIN }
            .toList()

        // Defer symbols whose dependencies (referenced types in parameters/returns) aren't yet
        // resolvable — typically because they're being generated in the same round.
        val (ready, deferred) = kotlinSourceMappers.partition { it.validate() }
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
        val shape = resolveShape(mapper) ?: return
        if (mapper.typeParameters.isNotEmpty()) {
            logger.error(
                "mapstruct-ksp: @Mapper on ${mapper.qualifiedName?.asString()} — generic mapper " +
                    "declarations are not supported in this iteration.",
                mapper
            )
            return
        }

        val pkg = mapper.packageName.asString()
        val originalSimpleName = mapper.simpleName.asString()
        val driverSimpleName = originalSimpleName + DRIVER_SUFFIX
        val originalType = ClassName.get(pkg, originalSimpleName)

        val typeBuilder = when (shape) {
            Shape.INTERFACE -> TypeSpec.interfaceBuilder(driverSimpleName)
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(originalType)
            Shape.ABSTRACT_CLASS -> TypeSpec.classBuilder(driverSimpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .superclass(originalType)
                .also { addForwardingConstructorIfNeeded(it, mapper) }
        }
        typeBuilder.addJavadoc(
            "Generated driver for {@link \$T}. Do not edit.\n" +
                "MapStruct's javac processor will produce an implementation of this type\n" +
                "which transitively satisfies the Kotlin source ${shape.noun}.\n",
            originalType
        )

        mapper.annotations
            .filter { isMapStructAnnotation(it) }
            .forEach { typeBuilder.addAnnotation(classLevelAnnotationSpec(it, originalSimpleName)) }

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

    /**
     * Classify the Kotlin declaration or reject it with a pointed error. Anything other than an
     * `interface` or an `abstract class` is out of scope — final classes can't be extended, open
     * classes usually mean "oops, meant to write abstract", and sealed/enum/object/etc. don't
     * fit the MapStruct model at all.
     */
    private fun resolveShape(mapper: KSClassDeclaration): Shape? = when {
        mapper.classKind == ClassKind.INTERFACE -> Shape.INTERFACE
        mapper.classKind == ClassKind.CLASS && KSModifier.ABSTRACT in mapper.modifiers ->
            Shape.ABSTRACT_CLASS
        else -> {
            logger.error(
                "mapstruct-ksp: @Mapper on ${mapper.qualifiedName?.asString()} is only supported " +
                    "on interfaces and abstract classes. Got: ${mapper.classKind}" +
                    if (mapper.modifiers.isNotEmpty()) " with modifiers ${mapper.modifiers}" else "",
                mapper
            )
            null
        }
    }

    /**
     * If the Kotlin abstract class declares a primary constructor with parameters, our driver
     * must declare a matching constructor that forwards to `super(...)`. Without this, javac
     * rejects the driver because there's no implicit `super()` on the Kotlin parent.
     *
     * Secondary Kotlin constructors are out of scope for this iteration; only the primary is
     * mirrored. A parameter-less primary constructor needs no forwarding constructor — Java's
     * implicit `super()` takes care of it.
     */
    private fun addForwardingConstructorIfNeeded(
        builder: TypeSpec.Builder,
        mapper: KSClassDeclaration
    ) {
        val primary = mapper.primaryConstructor ?: return
        if (primary.parameters.isEmpty()) return

        val ctor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC)
        val paramNames = mutableListOf<String>()
        primary.parameters.forEach { p ->
            val pname = p.name?.asString()
                ?: error("Unnamed constructor parameter in ${mapper.qualifiedName?.asString()}")
            val ptype = p.type.resolve()
            ctor.addParameter(typeTranslator.toTypeName(ptype), pname)
            paramNames.add(pname)
        }
        val placeholders = paramNames.joinToString(", ") { "\$L" }
        ctor.addStatement("super($placeholders)", *paramNames.toTypedArray())
        builder.addMethod(ctor.build())
    }

    private fun isMapStructAnnotation(annotation: KSAnnotation): Boolean {
        val fqn = annotation.annotationType.resolve().declaration.qualifiedName?.asString()
            ?: return false
        return fqn.startsWith("org.mapstruct.")
    }

    /**
     * Copies a class-level MapStruct annotation from the Kotlin source onto the driver.
     *
     * For `@Mapper` specifically, inject `implementationName = "<original>Impl"` unless the user
     * already supplied their own value. This makes `Mappers.getMapper(UserMapper::class.java)`
     * resolve: MapStruct will emit `UserMapperImpl` rather than the default
     * `UserMapperMapStructImpl`, and `Mappers.getMapper` looks up `UserMapperImpl` by its
     * `<class-name>Impl` convention. The impl extends the driver, which extends the Kotlin
     * interface, so the type cast inside `Mappers.getMapper` succeeds transitively.
     */
    private fun classLevelAnnotationSpec(
        annotation: KSAnnotation,
        originalSimpleName: String
    ): AnnotationSpec {
        val spec = annotationRenderer.toAnnotationSpec(annotation)
        val fqn = annotation.annotationType.resolve().declaration.qualifiedName?.asString()
        if (fqn != MAPPER_ANNOTATION_FQN) return spec
        if (userSpecifiedImplementationName(annotation)) return spec
        return spec.toBuilder()
            .addMember("implementationName", "\$S", "${originalSimpleName}Impl")
            .build()
    }

    /**
     * Did the user explicitly write `implementationName = ...` on the `@Mapper`? KSP2 marks
     * synthesised default arguments as [Origin.SYNTHETIC]; anything else is user-written.
     */
    private fun userSpecifiedImplementationName(annotation: KSAnnotation): Boolean =
        annotation.arguments.any {
            it.name?.asString() == IMPLEMENTATION_NAME_MEMBER && it.origin != Origin.SYNTHETIC
        }

    private enum class Shape(val noun: String) {
        INTERFACE("interface"),
        ABSTRACT_CLASS("abstract class")
    }

    companion object {
        const val MAPPER_ANNOTATION_FQN = "org.mapstruct.Mapper"
        const val DRIVER_SUFFIX = "MapStruct"
        private const val IMPLEMENTATION_NAME_MEMBER = "implementationName"
    }
}

class MapStructDriverProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        MapStructDriverProcessor(environment.codeGenerator, environment.logger)
}
