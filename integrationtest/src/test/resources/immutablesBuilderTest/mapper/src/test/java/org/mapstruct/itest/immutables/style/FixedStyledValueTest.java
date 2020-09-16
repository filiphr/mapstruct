/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.itest.immutables.style;

import org.junit.Test;
import org.mapstruct.itest.immutables.TopLevelDto;
import org.mapstruct.itest.immutables.TopLevelFixture;

import static org.junit.Assert.*;
import static org.mapstruct.itest.immutables.TopLevelFixture.CHILD_VALUE;

// shows support for a @Value.Style example
public class FixedStyledValueTest {
    private final TopLevelDto dto = TopLevelFixture.createDto();

    private final FixedTopLevelWithStyle domain = FixedTopLevelWithStyle.builder()
        .child( FixedChild.builder()
            .string(CHILD_VALUE)
            .build())
        .build();

    @Test
    public void toImmutable() {
        assertEquals(domain, FixedTopLevelMapper.INSTANCE.toImmutable(dto) );
    }

    @Test
    public void fromImmutable() {
        assertEquals(dto, FixedTopLevelMapper.INSTANCE.fromImmutable(domain) );
    }
}
