/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.itest.ksp

data class User(val name: String, val age: Int)
data class UserDto(val fullName: String, val age: Int)
