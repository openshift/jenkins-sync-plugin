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
