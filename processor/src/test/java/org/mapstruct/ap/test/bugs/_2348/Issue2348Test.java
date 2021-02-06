/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ap.test.bugs._2348;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mapstruct.ap.test.bugs._2348.dto.AnotherSource;
import org.mapstruct.ap.test.bugs._2348.dto.Source;
import org.mapstruct.ap.test.bugs._2348.dto.YetAnotherSource;
import org.mapstruct.ap.test.bugs._2348.entities.NestedTarget;
import org.mapstruct.ap.test.bugs._2348.entities.Target;
import org.mapstruct.ap.test.bugs._2348.mapper.SourceTargetMapper;
import org.mapstruct.ap.testutil.IssueKey;
import org.mapstruct.ap.testutil.WithClasses;
import org.mapstruct.ap.testutil.runner.AnnotationProcessorTestRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Filip Hrisafov
 */
@IssueKey("2348")
@RunWith(AnnotationProcessorTestRunner.class)
@WithClasses( {
    AnotherSource.class,
    Source.class,
    YetAnotherSource.class,
    NestedTarget.class,
    Target.class,
    SourceTargetMapper.class,
} )
public class Issue2348Test {

    @Test
    public void shouldCorrectlyMap() {
        Source source = new Source();
        source.setTest( "test" );
        AnotherSource anotherSource = new AnotherSource();
        anotherSource.setTest( "another test" );
        source.setTestToo( anotherSource );

        Target target = SourceTargetMapper.MAPPER.toTarget( source );

        assertThat( target ).isNotNull();
        assertThat( target.getNestedTarget() ).isNotNull();
        assertThat( target.getNestedTarget().getTesting() ).isEqualTo("test");
        assertThat( target.getNestedTarget().getTestingToo() ).isEqualTo("another test");
    }
}
