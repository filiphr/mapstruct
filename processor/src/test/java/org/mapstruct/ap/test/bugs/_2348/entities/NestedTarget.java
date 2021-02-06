/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ap.test.bugs._2348.entities;

public final class NestedTarget {
    private final Long testing;
    private final Long testingToo;

    NestedTarget(final Long testing, final Long testingToo) {
        this.testing = testing;
        this.testingToo = testingToo;
    }

    public static class NestedTargetBuilder {
        private Long testing;
        private Long testingToo;

        public NestedTargetBuilder testing(final Long testing) {
            this.testing = testing;
            return this;
        }

        public NestedTargetBuilder testingToo(final Long testingToo) {
            this.testingToo = testingToo;
            return this;
        }

        public NestedTarget build() {
            return new NestedTarget( this.testing, this.testingToo );
        }
    }

    public static NestedTargetBuilder builder() {
        return new NestedTargetBuilder();
    }

    public Long getTesting() {
        return this.testing;
    }

    public Long getTestingToo() {
        return this.testingToo;
    }
}
