package org.mapstruct.ap.test.imports.nested;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mapstruct.ap.test.imports.nested.other.Source;
import org.mapstruct.ap.testutil.IssueKey;
import org.mapstruct.ap.testutil.WithClasses;
import org.mapstruct.ap.testutil.runner.AnnotationProcessorTestRunner;
import org.mapstruct.ap.testutil.runner.GeneratedSource;

/**
 * @author Filip Hrisafov
 */
@WithClasses({
    Source.class,
    SourceTargetMapper.class,
    Target.class
})
@IssueKey( "1386" )
@RunWith( AnnotationProcessorTestRunner.class )
public class NestedImportsTest {

    @Rule
    public final GeneratedSource generatedSource =
        new GeneratedSource().addComparisonToFixtureFor( SourceTargetMapper.class );

    @Test
    public void shouldGenerateNestedInnerClasses() {
//        generatedSource.forMapper( SourceTargetMapper.class )
//            .containsImportFor( Source.class );
    }
}
