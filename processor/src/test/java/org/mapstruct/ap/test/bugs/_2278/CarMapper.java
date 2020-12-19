/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ap.test.bugs._2278;

import org.mapstruct.InheritInverseConfiguration;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper
public interface CarMapper {
    CarMapper INSTANCE = Mappers.getMapper( CarMapper.class );

    class Details {
        // CHECKSTYLE:OFF
        public String brand;
        public String model;
        // CHECKSTYLE:ON
    }
    class DetailsDTO {
        // CHECKSTYLE:OFF
        public String brand;
        // CHECKSTYLE:ON
    }
    class Car {
        // CHECKSTYLE:OFF
        public Details details;
        // CHECKSTYLE:ON
    }
    class CarDTO {
        // CHECKSTYLE:OFF
       public DetailsDTO detailsDTO;
        // CHECKSTYLE:ON
    }

    @Mapping(target = "detailsDTO", source = "details")
    CarDTO map(Car in);

    @InheritInverseConfiguration
    @Mapping(target = "details.model", ignore = true)
    Car map(CarDTO in);
}
