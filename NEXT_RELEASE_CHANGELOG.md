### Features

* Experimental Kotlin support via KSP: declare `@Mapper` interfaces and abstract classes directly
  in Kotlin. A new `org.mapstruct:mapstruct-ksp` module provides a Kotlin Symbol Processing
  processor that generates a small Java driver for each Kotlin `@Mapper`; the existing javac
  annotation processor then generates the implementation as usual, and `Mappers.getMapper` works
  against the Kotlin type directly. See the _Kotlin with KSP_ section of the setup chapter. Tracks
  https://github.com/mapstruct/mapstruct/issues/2522[#2522].

### Enhancements

### Bugs

### Documentation

### Build

