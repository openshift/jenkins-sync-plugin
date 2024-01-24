/**
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.jenkins.openshiftsync;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;

public class NamespaceName {
    private final String namespace;
    private final String name;

    public NamespaceName(String namespace, String name) {
        this.namespace = namespace;
        this.name = name;
    }

    public static NamespaceName create(HasMetadata hasMetadata) {
        notNull(hasMetadata, "resource");
        ObjectMeta metadata = hasMetadata.getMetadata();
        notNull(metadata, "metadata");
        String name = metadata.getName();
        String namespace = metadata.getNamespace();
        notNull(name, "metadata.name");
        notNull(namespace, "metadata.namespace");
        return new NamespaceName(namespace, name);
    }

    @Override
    public String toString() {
        return "NamespaceName{" + namespace + ":" + name + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        NamespaceName that = (NamespaceName) o;

        if (!namespace.equals(that.namespace))
            return false;
        return name.equals(that.name);

    }

    @Override
    public int hashCode() {
        int result = namespace.hashCode();
        result = 31 * result + name.hashCode();
        return result;
    }

    public String getName() {
        return name;
    }

    public String getNamespace() {
        return namespace;
    }

    /**
     * Asserts whether the value is not null
     *
     * @param value the value to test
     * @param name  the key that resolved the value
     * @throws IllegalArgumentException is thrown if assertion fails
     */
    public static void notNull(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must be specified");
        }
    }
}
