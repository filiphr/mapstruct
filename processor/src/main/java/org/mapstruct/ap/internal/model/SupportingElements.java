/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ap.internal.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Bundles all supporting elements (methods, fields, and constructor fragments) that are needed
 * in the generated mapper implementation in addition to the actual mapping methods.
 *
 * <p>This replaces the scattered extraction of fields and constructor fragments from
 * {@link SupportingMappingMethod} sets in {@code MapperCreationProcessor}.</p>
 *
 * @author MapStruct Authors
 */
public class SupportingElements {

    private final Set<SupportingMappingMethod> methods;
    private final Set<Field> fields;
    private final Set<SupportingConstructorFragment> constructorFragments;

    private SupportingElements(Set<SupportingMappingMethod> methods, Set<Field> fields,
                               Set<SupportingConstructorFragment> constructorFragments) {
        this.methods = methods;
        this.fields = fields;
        this.constructorFragments = constructorFragments;
    }

    public Set<SupportingMappingMethod> getMethods() {
        return Collections.unmodifiableSet( methods );
    }

    public Set<Field> getFields() {
        return Collections.unmodifiableSet( fields );
    }

    public Set<SupportingConstructorFragment> getConstructorFragments() {
        return Collections.unmodifiableSet( constructorFragments );
    }

    /**
     * Creates a {@link SupportingElements} by extracting fields and constructor fragments from
     * the given supporting mapping methods, and merging with additional standalone fields.
     *
     * @param supportingMappingMethods the supporting mapping methods accumulated during resolution
     * @param additionalFields standalone fields (e.g. from {@code ConversionProvider} helper fields)
     * @return a new {@link SupportingElements} instance
     */
    public static SupportingElements fromMappings(Set<SupportingMappingMethod> supportingMappingMethods,
                                                   Set<Field> additionalFields) {
        Set<Field> fields = new LinkedHashSet<>( additionalFields );
        SupportingField.addAllFieldsIn( supportingMappingMethods, fields );

        Set<SupportingConstructorFragment> constructorFragments = new LinkedHashSet<>();
        SupportingConstructorFragment.addAllFragmentsIn( supportingMappingMethods, constructorFragments );

        return new SupportingElements( supportingMappingMethods, fields, constructorFragments );
    }
}
