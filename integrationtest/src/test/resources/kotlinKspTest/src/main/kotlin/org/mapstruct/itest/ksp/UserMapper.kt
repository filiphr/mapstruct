/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.itest.ksp

import org.mapstruct.Mapper
import org.mapstruct.Mapping

@Mapper
interface UserMapper {

    @Mapping(target = "fullName", source = "name")
    fun toDto(user: User): UserDto
}
