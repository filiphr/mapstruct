/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.itest.ksp;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a Kotlin {@code @Mapper} interface, after running through mapstruct-ksp (KSP)
 * and mapstruct-processor (javac APT), produces an impl that maps a Kotlin data class correctly.
 *
 * <p>Uses the natural {@code Mappers.getMapper(UserMapper.class)} — not the KSP-generated
 * driver type. The processor injects {@code implementationName = "UserMapperImpl"} onto the
 * driver's {@code @Mapper}, so MapStruct emits the impl under the Kotlin interface's name.
 * The impl transitively implements the Kotlin interface via the driver, so this cast succeeds.
 */
class UserMapperIntegrationTest {

    @Test
    void shouldMapKotlinDataClass() {
        UserMapper mapper = Mappers.getMapper( UserMapper.class );

        UserDto dto = mapper.toDto( new User( "Alice", 30 ) );

        assertThat( dto.getFullName() ).isEqualTo( "Alice" );
        assertThat( dto.getAge() ).isEqualTo( 30 );
    }
}
