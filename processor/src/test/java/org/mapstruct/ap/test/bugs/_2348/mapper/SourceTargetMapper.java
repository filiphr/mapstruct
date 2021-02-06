/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ap.test.bugs._2348.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ap.test.bugs._2348.dto.Source;
import org.mapstruct.ap.test.bugs._2348.entities.Target;
import org.mapstruct.factory.Mappers;

@Mapper
public interface SourceTargetMapper {

    SourceTargetMapper MAPPER = Mappers.getMapper( SourceTargetMapper.class );

    @Mapping(target = "nestedTarget.testing", source = "test")
    @Mapping(target = "nestedTarget.testingToo", source = "testToo.test")
    Target toTarget(Source source);
}
