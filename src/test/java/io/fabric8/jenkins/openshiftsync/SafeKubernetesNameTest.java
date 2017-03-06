/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.jenkins.openshiftsync;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 */
public class SafeKubernetesNameTest {
    @Test
    public void testSafeNames() throws Exception {
        assertSafeKubernetesName("Foo-Bar", "foo-bar");
        assertSafeKubernetesName("-Foo-Bar", "foo-bar");
        assertSafeKubernetesName(".-Foo-Bar", "foo-bar");
        assertSafeKubernetesName("foo-bar/whatnot", "foo-bar.whatnot");
        assertSafeKubernetesName("foo-bar123/whatnot1234", "foo-bar123.whatnot1234");
        assertSafeKubernetesName("foo-bar//whatnot", "foo-bar.whatnot");
        assertSafeKubernetesName("foo-bar/whatnot_", "foo-bar.whatnot");
        assertSafeKubernetesName("_*foo-bar/*wh!atnot)", "foo-bar.wh-atnot");
    }

    public static void assertSafeKubernetesName(String text, String expected) {
        String actual = OpenShiftUtils.convertNameToValidResourceName(text);
        //System.out.println("Converted `" + text + "` => `" + actual + "`");
        assertEquals("Safe name for `" + text + "`", expected, actual);
    }

}
