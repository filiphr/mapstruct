/*
 * Copyright MapStruct Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package org.mapstruct.ap.test.bugs._2348.dto;

public class Source {
    private String test;
    private AnotherSource testToo;

    public String getTest() {
        return this.test;
    }

    public void setTest(final String test) {
        this.test = test;
    }

    public AnotherSource getTestToo() {
        return this.testToo;
    }

    public void setTestToo(final AnotherSource testToo) {
        this.testToo = testToo;
    }
}
