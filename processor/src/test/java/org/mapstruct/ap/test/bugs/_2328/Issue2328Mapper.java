/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ap.test.bugs._2328;

import java.util.ArrayList;
import java.util.Collection;

import org.mapstruct.CollectionMappingStrategy;
import org.mapstruct.Mapper;

/**
 * @author Filip Hrisafov
 */
@Mapper(collectionMappingStrategy = CollectionMappingStrategy.ADDER_PREFERRED)
public interface Issue2328Mapper {

    Group map(GroupDto source);


    class Group<T> {
        private Collection<Person<T>> persons = new ArrayList<>();

        public Collection<Person<T>> getPersons() { return persons; }
    }

    class GroupDto {
        private final Collection<PersonDto> persons;

        public GroupDto(Collection<PersonDto> persons) {
            this.persons = persons;
        }

        public Collection<PersonDto> getPersons() { return persons; }
    }

    class Person<T> {
        private final T name;

        public Person(T name) {
            this.name = name;
        }

        public T getName() {
            return name;
        }
    }

    class PersonDto {

        private final String name;

        public PersonDto(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }


}
