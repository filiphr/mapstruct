/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ap.test.imports.nested;

import javax.annotation.Generated;
import org.mapstruct.ap.test.imports.nested.other.Source;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2018-08-19T19:13:35+0200",
    comments = "version: , compiler: javac, environment: Java 1.8.0_161 (Oracle Corporation)"
)
public class SourceTargetMapperImpl implements SourceTargetMapper {

    @Override
    public Target map(Source source) {
        if ( source == null ) {
            return null;
        }

        Target target = new Target();

        target.setValue( sourceNestedToTargetNested( source.getValue() ) );

        return target;
    }

    protected Target.Nested.Inner sourceNestedInnerToTargetNestedInner(Source.Nested.Inner sourceNestedInner) {
        if ( sourceNestedInner == null ) {
            return null;
        }

        Target.Nested.Inner targetNestedInner = new Target.Nested.Inner();

        targetNestedInner.setValue( sourceNestedInner.getValue() );

        return targetNestedInner;
    }

    protected Target.Nested sourceNestedToTargetNested(Source.Nested sourceNested) {
        if ( sourceNested == null ) {
            return null;
        }

        Target.Nested targetNested = new Target.Nested();

        targetNested.setInner( sourceNestedInnerToTargetNestedInner( sourceNested.getInner() ) );

        return targetNested;
    }
}
