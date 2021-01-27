package org.mapstruct.ap.test.bugs._2326;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mapstruct.ap.testutil.WithClasses;
import org.mapstruct.ap.testutil.runner.AnnotationProcessorTestRunner;

/**
 * @author Filip Hrisafov
 */
@RunWith( AnnotationProcessorTestRunner.class )
@WithClasses( {
    Issue2326Mapper.class
} )
public class Issue2326Test {

    @Test
    public void shouldCompile() {

    }
}
