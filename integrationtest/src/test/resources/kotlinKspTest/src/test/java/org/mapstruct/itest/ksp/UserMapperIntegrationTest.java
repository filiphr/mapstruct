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
 * Verifies that a Kotlin-side {@code @Mapper} interface, after running through
 * mapstruct-ksp (KSP) and mapstruct-processor (javac APT), produces an impl that
 * maps a Kotlin data class correctly at runtime.
 *
 * Note: the user writes {@link UserMapper} in Kotlin, but the {@code Mappers.getMapper}
 * lookup targets the KSP-generated Java driver {@code UserMapperMapStruct} since that's
 * what the javac processor produced the impl for. Smoothing this to
 * {@code Mappers.getMapper(UserMapper.class)} is a follow-up decision.
 */
class UserMapperIntegrationTest {

    @Test
    void shouldMapKotlinDataClass() {
        UserMapperMapStruct mapper = Mappers.getMapper( UserMapperMapStruct.class );

        UserDto dto = mapper.toDto( new User( "Alice", 30 ) );

        assertThat( dto.getFullName() ).isEqualTo( "Alice" );
        assertThat( dto.getAge() ).isEqualTo( 30 );
    }
}
