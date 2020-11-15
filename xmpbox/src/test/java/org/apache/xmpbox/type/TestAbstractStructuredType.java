/*****************************************************************************
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 ****************************************************************************/

package org.apache.xmpbox.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Calendar;
import org.apache.xmpbox.XMPMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestAbstractStructuredType
{

    private static class MyStructuredType extends AbstractStructuredType
    {

        @PropertyType(type = Types.Text, card = Cardinality.Simple)
        public static final String MYTEXT = "my-text";

        @PropertyType(type = Types.Date, card = Cardinality.Simple)
        public static final String MYDATE = "my-date";

        public MyStructuredType(XMPMetadata metadata, String namespaceURI, String fieldPrefix)
        {
            super(metadata, namespaceURI, fieldPrefix, "structuredPN");
        }

    }

    protected MyStructuredType st;

    public static final String MY_NS = "http://www.apache.org/test#";

    public static final String MY_PREFIX = "test";

    @BeforeEach
    public void before() throws Exception
    {
        XMPMetadata xmp = XMPMetadata.createXMPMetadata();
        st = new MyStructuredType(xmp, MY_NS, MY_PREFIX);
    }

    @Test
    public void validate() throws Exception
    {
        assertEquals(MY_NS, st.getNamespace());
        assertEquals(MY_PREFIX, st.getPrefix());
        assertEquals(MY_PREFIX, st.getPrefix());
    }

    @Test
    public void testNonExistingProperty() throws Exception
    {
        assertNull(st.getProperty("NOT_EXISTING"));
    }

    @Test
    public void testNotValuatedPropertyProperty() throws Exception
    {
        assertNull(st.getProperty(MyStructuredType.MYTEXT));
    }

    @Test
    public void testValuatedTextProperty() throws Exception
    {
        String s = "my value";
        st.addSimpleProperty(MyStructuredType.MYTEXT, s);
        assertEquals(s, st.getPropertyValueAsString(MyStructuredType.MYTEXT));
        assertNull(st.getPropertyValueAsString(MyStructuredType.MYDATE));
        assertNotNull(st.getProperty(MyStructuredType.MYTEXT));
    }

    @Test
    public void testValuatedDateProperty() throws Exception
    {
        Calendar c = Calendar.getInstance();
        st.addSimpleProperty(MyStructuredType.MYDATE, c);
        assertEquals(c, st.getDatePropertyAsCalendar(MyStructuredType.MYDATE));
        assertNull(st.getDatePropertyAsCalendar(MyStructuredType.MYTEXT));
        assertNotNull(st.getProperty(MyStructuredType.MYDATE));
    }

}
