/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ap.test.bugs._2278;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mapstruct.ap.testutil.IssueKey;
import org.mapstruct.ap.testutil.WithClasses;
import org.mapstruct.ap.testutil.runner.AnnotationProcessorTestRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Filip Hrisafov
 */
@IssueKey("2278")
@RunWith(AnnotationProcessorTestRunner.class)
@WithClasses( {
    CarMapper.class,
} )
public class Issue2278Test {

    @Test
    public void shouldCorrectlyInheritNestedTarget() {
        CarMapper.Car car = new CarMapper.Car();
        car.details = new CarMapper.Details();
        car.details.model = "S";
        car.details.brand = "Tesla";

        CarMapper.CarDTO dto = CarMapper.INSTANCE.map( car );
        assertThat( dto.detailsDTO ).isNotNull();
        assertThat( dto.detailsDTO.brand ).isEqualTo( "Tesla" );

        car = CarMapper.INSTANCE.map( dto );
        assertThat( car.details ).isNotNull();
        assertThat( car.details.model ).isNull();
        assertThat( car.details.brand ).isEqualTo( "Tesla" );
    }
}
