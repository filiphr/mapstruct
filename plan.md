# Plan: Refactor ConstructorFragment and SupportingMethod (Issue #2449)

## Problem Analysis

The current architecture for managing supporting methods, fields, and constructor fragments in the generated mapper code is overly complex:

1. **Scattered state accumulation**: `MappingResolverImpl` maintains two mutable `Set` fields (`usedSupportedMappings`, `usedSupportedFields`) at the resolver level. These are populated as side-effects during resolution, which is error-prone and hard to reason about.

2. **Complex intermediary: `supportingMethodCandidates`**: The inner `ResolvingAttempt` class maintains a `supportingMethodCandidates` set that acts as a staging area. Candidates are only promoted to `usedSupportedMappings` after a successful match. This two-phase commit is necessary because 2-step resolution can match on step 1 but fail on step 2, but the mechanism is implicit and fragile.

3. **`SupportingMappingMethod` does too much**: It wraps both `BuiltInMethod` and `HelperMethod` but in very different ways — for `BuiltInMethod` it extracts fields and constructor fragments; for `HelperMethod` those are always null. This dual-nature is confusing.

4. **`ConstructorFragment` is tightly coupled to `SupportingMappingMethod`**: The `SupportingConstructorFragment` wrapper requires a reference to its `SupportingMappingMethod` (the `definingMethod` field), creating circular-ish dependencies. It also requires the `SupportingField`'s variable name, coupling these concepts.

5. **Post-processing extraction in `MapperCreationProcessor`**: After all mapping methods are resolved, `MapperCreationProcessor.getMapper()` must separately extract fields (`addAllFieldsIn`) and constructor fragments (`addAllFragmentsIn`) from the `SupportingMappingMethod` set. This scattering of responsibilities makes the flow hard to follow.

6. **`ConversionProvider` can't access existing fields/methods**: When a `ConversionProvider` needs helper fields (e.g., `GetDateTimeFormatterField`), the field deduplication logic lives in `MappingResolverImpl.resolveViaConversion()` rather than being available to the provider itself.

## Proposed Solution

The refactoring should be done incrementally across multiple steps. The core idea is to introduce a **`SupportingElements`** result object that bundles methods, fields, and constructor fragments together, and return it from resolution rather than accumulating state via side-effects.

### Step 1: Introduce `SupportingElements` result object

Create a new class `SupportingElements` that bundles:
- `Set<SupportingMappingMethod> methods`
- `Set<Field> fields`
- `Set<SupportingConstructorFragment> constructorFragments`

This replaces the scattered extraction in `MapperCreationProcessor` — instead of calling `addAllFieldsIn()` and `addAllFragmentsIn()` separately, a single `SupportingElements` is returned from the resolver.

**File**: `processor/src/main/java/org/mapstruct/ap/internal/model/SupportingElements.java`

```java
public class SupportingElements {
    private final Set<SupportingMappingMethod> methods;
    private final Set<Field> fields;
    private final Set<SupportingConstructorFragment> constructorFragments;

    // Constructor, getters, static merge/builder methods

    public static SupportingElements fromMappings(
            Set<SupportingMappingMethod> mappings, Set<Field> additionalFields) {
        // Extract fields and fragments from SupportingMappingMethods
        // + merge with additionalFields (from ConversionProvider helper fields)
    }
}
```

### Step 2: Change `MappingResolver` interface to return `SupportingElements`

Change the `MappingResolver` interface (currently `MappingBuilderContext.MappingResolver`):

```java
public interface MappingResolver {
    Assignment getTargetAssignment(...);
    SupportingElements getUsedSupportingElements();  // replaces getUsedSupportedMappings() + getUsedSupportedFields()
}
```

Update `MappingResolverImpl`:
- Replace `usedSupportedMappings` + `usedSupportedFields` with a method that constructs `SupportingElements` from the accumulated data.
- Keep the internal accumulation for now (step 3 will clean that up).

Update `MappingBuilderContext`:
- Replace `getUsedSupportedMappings()` and `getUsedSupportedFields()` with `getUsedSupportingElements()`.

Update `MapperCreationProcessor.getMapper()`:
- Use `SupportingElements` directly instead of manually extracting fields and fragments.

### Step 3: Encapsulate `supportingMethodCandidates` promotion logic

Refactor the `ResolvingAttempt` inner class so that the pattern of "add to candidates, then promote on success" is encapsulated:

```java
private class ResolvingAttempt {
    private final SupportingMethodAccumulator accumulator;

    // On successful resolution:
    // accumulator.commitCandidates() -> promotes to parent usedSupportedMappings
    // On failure: candidates are discarded automatically (new ResolvingAttempt = new accumulator)
}
```

This makes the two-phase commit explicit rather than relying on scattered `usedSupportedMappings.addAll(supportingMethodCandidates)` calls at 4+ different locations (lines 284, 294, 302, 314).

### Step 4: Decouple `SupportingConstructorFragment` from `SupportingMappingMethod`

Currently `SupportingConstructorFragment` holds a reference to its `definingMethod` (a `SupportingMappingMethod`). This circular reference is unnecessary — the fragment only needs:
- The variable name (from the associated field)
- The template name (from the `ConstructorFragment` class)

Refactor to remove `definingMethod` from `SupportingConstructorFragment`. The fragment should be a standalone model element that just knows its variable name and template.

Similarly, `SupportingField.definingMethod` should be evaluated for removal — it's unclear if anything uses it downstream.

### Step 5: Enhance `ConversionContext` to provide field/method access

Extend `ConversionContext` (or create a richer context) so `ConversionProvider` implementations can:
- Query existing fields (for deduplication)
- Register new fields they need

This moves the field deduplication logic from `MappingResolverImpl.resolveViaConversion()` into a context that the `ConversionProvider` can use directly:

```java
public interface ConversionContext {
    // existing methods...
    Type getTargetType();
    Type getSourceType();
    String getDateFormat();

    // new methods:
    Field getOrCreateField(FieldReference ref);  // handles deduplication
}
```

This change would let `ConversionProvider.getRequiredHelperFields()` be eliminated in favor of the provider directly registering fields via the context during `to()`/`from()` calls.

### Step 6: Consider merging `HelperMethod` and `BuiltInMethod` supporting flows

Currently `SupportingMappingMethod` has two constructors — one for `BuiltInMethod` (which can have fields + constructor fragments) and one for `HelperMethod` (which cannot). Consider:
- Making `HelperMethod` also capable of declaring field references (like `BuiltInMethod`)
- Or splitting `SupportingMappingMethod` into two separate classes

This decision depends on whether future `HelperMethod` implementations might need fields/fragments.

## Implementation Order & Risk Assessment

| Step | Risk | Effort | Standalone? |
|------|------|--------|-------------|
| 1    | Low  | Low    | Yes         |
| 2    | Medium | Medium | Depends on 1 |
| 3    | Low  | Low    | Yes (can be done independently) |
| 4    | Low  | Low    | Yes         |
| 5    | High | High   | Depends on 1+2 |
| 6    | Medium | Medium | Depends on all above |

**Recommended approach**: Implement steps 1-4 first as they have lower risk and can be validated independently. Steps 5-6 are more invasive and should be done only after 1-4 are stable.

## Files to Modify

**Core changes:**
- `processor/src/main/java/org/mapstruct/ap/internal/model/SupportingElements.java` (NEW)
- `processor/src/main/java/org/mapstruct/ap/internal/model/MappingBuilderContext.java` (interface change)
- `processor/src/main/java/org/mapstruct/ap/internal/processor/creation/MappingResolverImpl.java` (major refactor)
- `processor/src/main/java/org/mapstruct/ap/internal/processor/MapperCreationProcessor.java` (simplify)
- `processor/src/main/java/org/mapstruct/ap/internal/model/SupportingMappingMethod.java` (simplify)
- `processor/src/main/java/org/mapstruct/ap/internal/model/SupportingConstructorFragment.java` (decouple)
- `processor/src/main/java/org/mapstruct/ap/internal/model/SupportingField.java` (decouple)

**Context enhancement (step 5):**
- `processor/src/main/java/org/mapstruct/ap/internal/model/common/ConversionContext.java`
- `processor/src/main/java/org/mapstruct/ap/internal/model/common/DefaultConversionContext.java`
- `processor/src/main/java/org/mapstruct/ap/internal/conversion/ConversionProvider.java`
- Various `ConversionProvider` implementations (e.g., `AbstractJavaTimeToStringConversion`)

**Tests:** Existing tests should continue to pass since this is a pure refactoring. No new test cases needed unless behavior changes are introduced.
