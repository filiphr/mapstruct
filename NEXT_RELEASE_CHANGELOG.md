### Features

* Add `URI` to `String` built-in conversions (#4018)

### Enhancements

* Support `SET_TO_NULL` for overloaded target methods, requiring a cast (#3949)
* Use multi-catch in generated code (#4021)
* Always use factory methods for `LinkedHashMap` and `LinkedHashSet` when targeting `SequencedSet` and `SequencedMap` (#3990)
* Upgrade internal `Visitor6` usages to `Visitor8`
* Improve performance of `Type.describe()` by removing regex matching (#3991)
* Refactor `TypeFactory.getTypeParameters` (#4020)
* Simplify boolean logic in `ValueMappingMethod` by removing inversion (#4007)
* Remove unnecessary `keySet()` invocation (#3989)
* Remove unused methods in `Fields` (#4010)

### Bugs

* Prevent mapper generation from a type with a generic super bound to a type with a generic extends bound (#3994)
* Add missing self reference in `GeneratedTypeBuilder` (#4009)
* Fix self check in `equals` of `Type` (#3995)
* Fix location for Javadoc when generating the distribution zip

### Documentation

* Add `SECURITY.md` and `.github/INCIDENT_RESPONSE.md`

### Build

* Test on JDK 25 and 26, drop integration test on JDK 11
* Add CodeQL custom workflow and set build mode for `java-kotlin` to `none`
* Upgrade integration tests to JUnit 5 (#4023)
* Update Maven compiler plugin (#3972)
* Upgrade Freemarker to 2.3.34
* Update license plugin
* Enforce import order via Checkstyle `CustomImportOrder` (#4024)
* Enforce spaces inside parentheses for control flow statements via Checkstyle
* Simplify `fail` in `assertCheckstyleRules`
* Remove deprecated `Number` API usage from tests
* Use `StandardCharsets.UTF_8` in tests
* Remove obsolete override of AssertJ version in integration tests
* Let GitHub determine whether or not the released version is the latest

