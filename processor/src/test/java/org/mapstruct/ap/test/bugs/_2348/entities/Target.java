/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ap.test.bugs._2348.entities;

public final class Target {
    private final NestedTarget nestedTarget;

    Target(final NestedTarget nestedTarget) {
        this.nestedTarget = nestedTarget;
    }

    public static class TargetBuilder {
        private NestedTarget nestedTarget;

        public TargetBuilder nestedTarget(final NestedTarget nestedTarget) {
            this.nestedTarget = nestedTarget;
            return this;
        }

        public Target build() {
            return new Target( this.nestedTarget );
        }
    }

    public static TargetBuilder builder() {
        return new TargetBuilder();
    }

    public NestedTarget getNestedTarget() {
        return this.nestedTarget;
    }
}
