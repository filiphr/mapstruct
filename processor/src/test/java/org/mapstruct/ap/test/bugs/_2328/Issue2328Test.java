/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ap.test.bugs._2328;

import java.util.Collections;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mapstruct.ap.testutil.WithClasses;
import org.mapstruct.ap.testutil.runner.AnnotationProcessorTestRunner;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * @author Filip Hrisafov
 */
@RunWith(AnnotationProcessorTestRunner.class)
@WithClasses({
    Issue2328Mapper.class
})
public class Issue2328Test {

    @Test
    public void shouldCompile() {

        Issue2328Mapper mapper = Mappers.getMapper( Issue2328Mapper.class );

        Issue2328Mapper.PersonDto tester = new Issue2328Mapper.PersonDto( "Tester" );
        Set<Issue2328Mapper.PersonDto> people = Collections.singleton( tester );
        Issue2328Mapper.GroupDto groupDto = new Issue2328Mapper.GroupDto( people );

        Issue2328Mapper.Group group = mapper.map( groupDto );

        assertThat( group ).isNotNull();
        assertThat( group.getPersons() )
            .extracting( p -> ( (Issue2328Mapper.Person) p ).getName() )
            .containsExactly( "Tester" );

    }
}
