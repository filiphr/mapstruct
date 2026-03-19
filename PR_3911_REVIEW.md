## Review of PR #3911: Add SPI for Implementation Naming (#3619)

### Architecture & Design Issues

#### 1. SPI should be loaded in `AnnotationProcessorContext`, not statically in `Mapper`/`Decorator` (Critical)

The PR loads the SPI via a `static final` field directly in `Mapper.java` and `Decorator.java`:

```java
static final ImplementationNamingStrategy IMPLEMENTATION_NAMING_STRATEGY =
    Services.get( ImplementationNamingStrategy.class, new DefaultImplementationNamingStrategy() );
```

**This breaks the established pattern.** Every other SPI in the project is loaded lazily in `AnnotationProcessorContext.initialize()`. The reasons for this pattern are documented in the codebase:

> *"The reason why we do this is due to the fact that when custom SPI implementations are done and users don't set `proc:none` then our processor would be triggered. And this context will always get initialized and the SPI won't be found."*

Loading statically in `Mapper`/`Decorator` bypasses this lazy initialization safety. The strategy should be loaded in `AnnotationProcessorContext`, stored as a field, and passed through to `Mapper.Builder` / `Decorator.Builder`.

#### 2. Missing `init(MapStructProcessingEnvironment)` method (Critical)

Every SPI interface in MapStruct provides a default `init()` method:

```java
default void init(MapStructProcessingEnvironment processingEnvironment) { }
```

This is present in `AccessorNamingStrategy`, `BuilderProvider`, `EnumMappingStrategy`, etc. The new `ImplementationNamingStrategy` interface is missing this. SPI implementations may need access to `Elements`/`Types` utilities (e.g., to do more sophisticated naming based on type hierarchy), and omitting `init()` would require a breaking change to add it later.

#### 3. Missing verbose logging (Minor)

All other SPIs log which implementation is active when `verbose` mode is enabled:

```java
if (verbose) {
    messager.printMessage(Diagnostic.Kind.NOTE,
        "MapStruct: Using implementation naming strategy: " + ...);
}
```

This is absent from the PR.

#### 4. Duplicate `Services.get()` calls (Bug)

Both `Mapper.java` and `Decorator.java` independently call `Services.get()`. Since `Services.get()` creates a new `ServiceLoader` each time, this is wasteful and could theoretically produce inconsistent results if the classpath changes between calls. The strategy should be loaded once and shared.

---

### Interface Design Issues

#### 5. Confusing parameter semantics (Important)

The interface methods take `(String className, String implementationName)` where:
- `className` = the flat name of the mapper interface (e.g., `"CarMapper"`)
- `implementationName` = the already-resolved implementation name (e.g., `"CarMapperImpl"`)

This means the SPI receives the *result* of the `<CLASS_NAME>` placeholder replacement. The SPI user cannot access the raw `implementationName` expression. The parameter names and Javadoc should be clearer about what has already been resolved vs. what hasn't.

Suggestion: rename `implementationName` to something like `resolvedImplementationName` or clarify in Javadoc that placeholder resolution has already occurred.

#### 6. Missing `@since` and `@author` tags (Minor)

All existing SPI interfaces include `@author` and `@since` tags in their Javadoc. Both the interface and the default implementation should include these.

#### 7. Consider `@Experimental` annotation (Minor)

`MappingExclusionProvider` uses `@Experimental("This SPI can have its signature changed in subsequent releases")`. Since this is a new SPI whose API may evolve, it would be worth considering the same annotation here.

---

### Implementation Issues

#### 8. `hasCustomName` logic is fragile (Important)

The PR changes how `customName` is determined in `Mapper.java`. The original code checks whether the annotation attribute differs from the default:
```java
this.customName = !DEFAULT_IMPLEMENTATION_CLASS.equals(this.implName);
```

The PR replaces this with a post-hoc check that compares the *SPI-transformed* name against the *default pre-SPI* name. If a user has a custom `ImplementationNamingStrategy` but uses the default `implementationName` annotation attribute, `hasCustomName` will return `true` even though the user didn't set a custom name via `@Mapper(implementationName=...)`. This affects `hasCustomImplementation()` which is used for `Mappers.getMapper()` lookup logic. The "custom name" concept should reflect whether the *annotation* specifies a custom name, not whether the SPI changed it.

#### 9. Decorator naming logic is unclear (Important)

In the current codebase, `Decorator.build()` generates the decorator class name and delegate field name (`implementationName + "_"`). The PR introduces `generateDecoratorImplementationName()` and `generateMapperImplementationName()` in `Decorator.Builder`, which is confusing - the Decorator builder shouldn't need to generate "mapper" implementation names. The naming of these methods within `Decorator` needs rethinking.

---

### Test Issues

#### 10. Package name uses uppercase (Style)

```java
package org.mapstruct.ap.test.naming.spi.ImplementationNamingStrategy;
```

Java package names should be all lowercase. This should be something like:
```java
package org.mapstruct.ap.test.naming.spi.implementationnamingstrategy;
```

#### 11. Test doesn't verify generated source code (Important)

Other SPI tests use `GeneratedSource` to verify the actual generated code. The new tests only verify runtime behavior (`getClass().getSimpleName()`). They should also verify the generated `.java` file has the correct class name.

#### 12. Test hardcodes naming logic (Minor)

The test creates a `CustomImplementationNamingStrategy` and calls its methods to compute the expected name. It would be cleaner to hardcode the expected result directly, making the test more readable and independent.

---

### Documentation Issues

#### 13. Documentation example doesn't match a realistic use case (Minor)

The example `CustomImplementationNamingStrategy` appends `"MapperImpl"` to the already-resolved name, producing names like `CarMapperImplMapperImpl`. The issue #3619 describes a more practical use case (e.g. stripping a prefix). The documentation example should demonstrate the actual motivating use case.

---

### Summary of Required Changes

| Priority | Issue |
|----------|-------|
| Critical | Load SPI in `AnnotationProcessorContext`, not statically in model classes |
| Critical | Add `default void init(MapStructProcessingEnvironment)` to the interface |
| Important | Fix `hasCustomName` logic to not conflate SPI naming with annotation-based naming |
| Important | Clarify decorator vs. mapper naming in `Decorator.Builder` |
| Important | Add generated source verification to tests |
| Important | Clarify parameter semantics in the SPI interface |
| Style | Fix uppercase package name in tests |
| Minor | Add `@since`, `@author`, verbose logging, consider `@Experimental` |
| Minor | Improve documentation example to match the motivating use case |

Overall this is a good start at addressing a real user need, but the integration approach needs to follow the established SPI patterns in the codebase before it's ready to merge.
