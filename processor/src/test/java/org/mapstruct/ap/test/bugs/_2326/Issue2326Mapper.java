/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ap.test.bugs._2326;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * @author Filip Hrisafov
 */
@Mapper
public interface Issue2326Mapper {

    @Mapping( target = "maxScore", expression = "java(detail.get( 0 ))")
    @Mapping( target = "detail", source = "detail")
    MaxScore map(List<Integer> detail);

    class MaxScore {

        private Integer maxScore;
        private List<Integer> detail;

        public Integer getMaxScore() {
            return maxScore;
        }

        public void setMaxScore(Integer maxScore) {
            this.maxScore = maxScore;
        }

        public List<Integer> getDetail() {
            return detail;
        }

        public void setDetail(List<Integer> detail) {
            this.detail = detail;
        }
    }
}
