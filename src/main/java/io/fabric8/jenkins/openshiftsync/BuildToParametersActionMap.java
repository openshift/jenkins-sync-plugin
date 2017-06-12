/**
 * Copyright (C) 2017 Red Hat, Inc.
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import hudson.model.ParametersAction;

public class BuildToParametersActionMap {
    
    private static Map<String, ParametersAction> buildToParametersMap;

    private BuildToParametersActionMap() {
    }
    
    static synchronized void initialize() {
        if (buildToParametersMap == null) {
            buildToParametersMap = new ConcurrentHashMap<String, ParametersAction>();
        }
    }
    
    static synchronized void add(String buildId, ParametersAction params) {
        buildToParametersMap.put(buildId, params);
    }
    
    static synchronized ParametersAction remove(String buildId) {
        return buildToParametersMap.remove(buildId);
    }

}
