/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ap.internal.model;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

import org.mapstruct.ap.internal.model.common.ConstructorFragment;
import org.mapstruct.ap.internal.model.common.ModelElement;
import org.mapstruct.ap.internal.model.common.Type;

/**
 * A constructor fragment that is added to the generated mapper's constructor to initialize
 * supporting fields (e.g. {@code DatatypeFactory}).
 *
 * @author Sjaak Derksen
 */
public class SupportingConstructorFragment extends ModelElement {

    private final String variableName;
    private final String templateName;
    private final SupportingMappingMethod definingMethod;
    private final Field supportingField;
    private final Set<Type> methodImportTypes;

    public SupportingConstructorFragment(SupportingMappingMethod definingMethod,
                                         ConstructorFragment constructorFragment, String variableName) {
        this.templateName = getTemplateNameForClass( constructorFragment.getClass() );
        this.definingMethod = definingMethod;
        this.variableName = variableName;
        this.supportingField = definingMethod != null ? definingMethod.getSupportingField() : null;
        this.methodImportTypes = definingMethod != null ? definingMethod.getImportTypes() : Collections.emptySet();
    }

    @Override
    public String getTemplateName() {
        return templateName;
    }

    @Override
    public Set<Type> getImportTypes() {
        return Collections.emptySet();
    }

    /**
     * @deprecated Use {@link #getSupportingField()} and {@link #findType(String)} instead.
     *             Will be removed in a future version.
     */
    @Deprecated
    public SupportingMappingMethod getDefiningMethod() {
        return definingMethod;
    }

    public String getVariableName() {
        return variableName;
    }

    /**
     * Returns the supporting field associated with this constructor fragment.
     *
     * @return the supporting field, or {@code null} if there is none
     */
    public Field getSupportingField() {
        return supportingField;
    }

    /**
     * Finds a {@link Type} by name from the import types of the method that defined this fragment.
     *
     * @param name fully-qualified or simple name of the type
     * @return the found type
     * @throws IllegalArgumentException if no type was found for the given name
     */
    public Type findType(String name) {
        for ( Type type : methodImportTypes ) {
            if ( type.getFullyQualifiedName().contentEquals( name ) ) {
                return type;
            }
            if ( type.getName().contentEquals( name ) ) {
                return type;
            }
        }
        throw new IllegalArgumentException( "No type for given name '" + name + "' found in import types." );
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ( ( variableName == null ) ? 0 : variableName.hashCode() );
        result = prime * result + ( ( templateName == null ) ? 0 : templateName.hashCode() );
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if ( this == obj ) {
            return true;
        }
        if ( obj == null ) {
            return false;
        }
        if ( getClass() != obj.getClass() ) {
            return false;
        }
        SupportingConstructorFragment other = (SupportingConstructorFragment) obj;

        if ( !Objects.equals( variableName, other.variableName ) ) {
            return false;
        }
        if ( !Objects.equals( templateName, other.templateName ) ) {
            return false;
        }
        return true;
    }

    public static void addAllFragmentsIn(Set<SupportingMappingMethod> supportingMappingMethods,
                                         Set<SupportingConstructorFragment> targets) {
        for ( SupportingMappingMethod supportingMappingMethod : supportingMappingMethods ) {
            SupportingConstructorFragment fragment = supportingMappingMethod.getSupportingConstructorFragment();
            if ( fragment != null ) {
                targets.add( supportingMappingMethod.getSupportingConstructorFragment() );
            }
        }
    }

    public static SupportingConstructorFragment getSafeConstructorFragment(SupportingMappingMethod method,
                                                                           ConstructorFragment fragment,
                                                                           Field supportingField) {
        if ( fragment == null ) {
            return null;
        }

        return new SupportingConstructorFragment(
            method,
            fragment,
            supportingField != null ? supportingField.getVariableName() : null );
    }
}
