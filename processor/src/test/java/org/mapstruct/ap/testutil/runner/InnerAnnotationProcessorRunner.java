/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ap.testutil.runner;

import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestClass;

/**
 * Internal test runner that runs the tests of one class for one specific compiler implementation.
 *
 * @author Andreas Gudian
 */
class InnerAnnotationProcessorRunner extends BlockJUnit4ClassRunner {
    static final ModifiableURLClassLoader TEST_CLASS_LOADER = new ModifiableURLClassLoader();
    private final Class<?> klass;
    private final Compiler compiler;
    private final CompilationCache compilationCache;
    private Class<?> klassToUse;
    private ReplacableTestClass replacableTestClass;

    /**
     * @param klass the test class
     *
     * @throws Exception see {@link BlockJUnit4ClassRunner#BlockJUnit4ClassRunner(Class)}
     */
    InnerAnnotationProcessorRunner(Class<?> klass, Compiler compiler) throws Exception {
        super( klass );
        this.klass = klass;
        this.compiler = compiler;
        this.compilationCache = new CompilationCache();
    }

    /**
     * newly loads the class with the test class loader and sets that loader as context class loader of the thread
     *
     * @param klass the class to replace
     *
     * @return the class loaded with the test class loader
     */
    private static Class<?> replaceClassLoaderAndClass(Class<?> klass) {
        replaceContextClassLoader( klass );

        try {
            return Thread.currentThread().getContextClassLoader().loadClass( klass.getName() );
        }
        catch ( ClassNotFoundException e ) {
            throw new RuntimeException( e );
        }

    }

    private static void replaceContextClassLoader(Class<?> klass) {
        ModifiableURLClassLoader testClassLoader = new ModifiableURLClassLoader().withOriginOf( klass );

        Thread.currentThread().setContextClassLoader( testClassLoader );
    }

    @Override
    protected boolean isIgnored(FrameworkMethod child) {
        return super.isIgnored( child ) || isIgnoredForCompiler( child );
    }

    protected boolean isIgnoredForCompiler(FrameworkMethod child) {
        EnabledOnCompiler enabledOnCompiler = child.getAnnotation( EnabledOnCompiler.class );
        if ( enabledOnCompiler != null ) {
            return enabledOnCompiler.value() != compiler;
        }

        DisabledOnCompiler disabledOnCompiler = child.getAnnotation( DisabledOnCompiler.class );
        if ( disabledOnCompiler != null ) {
            return disabledOnCompiler.value() == compiler;
        }

        return false;
    }

    @Override
    protected TestClass createTestClass(final Class<?> testClass) {
        replacableTestClass = new ReplacableTestClass( testClass );
        return replacableTestClass;
    }

    private FrameworkMethod replaceFrameworkMethod(FrameworkMethod m) {
        try {
            return new FrameworkMethod(
                klassToUse.getDeclaredMethod( m.getName(), m.getMethod().getParameterTypes() ) );
        }
        catch ( NoSuchMethodException e ) {
            throw new RuntimeException( e );
        }
    }

    @Override
    protected Statement methodBlock(FrameworkMethod method) {
        CompilingStatement statement = createCompilingStatement( method );
        if ( statement.needsRecompilation() ) {
            klassToUse = replaceClassLoaderAndClass( klass );

            replacableTestClass.replaceClass( klassToUse );
        }

        method = replaceFrameworkMethod( method );

        Statement next = super.methodBlock( method );

        statement.setNextStatement( next );

        return statement;
    }

    private CompilingStatement createCompilingStatement(FrameworkMethod method) {
        if ( compiler == Compiler.JDK ) {
            return new JdkCompilingStatement( method, compilationCache );
        }
        else if ( compiler == Compiler.JDK11 ) {
            return new Jdk11CompilingStatement( method, compilationCache );
        }
        else {
            return new EclipseCompilingStatement( method, compilationCache );
        }
    }

    @Override
    protected String getName() {
        return "[" + compiler.name().toLowerCase() + "]";
    }

    @Override
    protected String testName(FrameworkMethod method) {
        return method.getName() + getName();
    }
}
