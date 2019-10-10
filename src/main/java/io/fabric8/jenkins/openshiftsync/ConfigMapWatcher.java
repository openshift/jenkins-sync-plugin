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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.client.Watcher.Action;

import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class ConfigMapWatcher extends BaseWatcher {
    private final Logger LOGGER = Logger.getLogger(getClass().getName());

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ConfigMapWatcher(String[] namespaces) {
        super(namespaces);
    }

    @Override
    public int getListIntervalInSeconds() {
        return GlobalPluginConfiguration.get().getConfigMapListInterval();
    }

    public Runnable getStartTimerTask() {
        return new SafeTimerTask() {
            @Override
            public void doRun() {
                if (!CredentialsUtils.hasCredentials()) {
                    LOGGER.fine("No Openshift Token credential defined.");
                    return;
                }
                for (String namespace : namespaces) {
                    ConfigMapList configMaps = null;
                    try {
                        LOGGER.fine("listing ConfigMap resources");
                        configMaps = getAuthenticatedOpenShiftClient()
                                .configMaps().inNamespace(namespace).list();
                        onInitialConfigMaps(configMaps);
                        LOGGER.fine("handled ConfigMap resources");
                    } catch (Exception e) {
                        LOGGER.log(SEVERE, "Failed to load ConfigMaps: " + e, e);
                    }
                    try {
                        String resourceVersion = "0";
                        if (configMaps == null) {
                            LOGGER.warning("Unable to get config map list; impacts resource version used for watch");
                        } else {
                            resourceVersion = configMaps.getMetadata().getResourceVersion();
                        }
                        if (watches.get(namespace) == null) {
                            LOGGER.info("creating ConfigMap watch for namespace "
                                    + namespace
                                    + " and resource version "
                                    + resourceVersion);
                            addWatch(namespace,
                                    getAuthenticatedOpenShiftClient()
                                    .configMaps()
                                    .inNamespace(namespace)
                                    .withResourceVersion(resourceVersion).watch(new WatcherCallback<ConfigMap>(ConfigMapWatcher.this,namespace)));
                        }
                    } catch (Exception e) {
                        LOGGER.log(SEVERE, "Failed to load ConfigMaps: " + e, e);
                    }
                }
            }
        };
    }

    public void start() {
        super.start();
        // lets process the initial state
        LOGGER.info("Now handling startup config maps!!");
    }

    public void eventReceived(Action action, ConfigMap configMap) {
        try {
            List<PodTemplate> slavesFromCM = PodTemplateUtils.podTemplatesFromConfigMap(this, configMap);
            boolean hasSlaves = slavesFromCM.size() > 0;
            String uid = configMap.getMetadata().getUid();
            String cmname = configMap.getMetadata().getName();
            String namespace = configMap.getMetadata().getNamespace();
            switch (action) {
            case ADDED:
                if (hasSlaves) {
                    processSlavesForAddEvent(slavesFromCM, PodTemplateUtils.cmType, uid, cmname, namespace);
                }
                break;
            case MODIFIED:
                processSlavesForModifyEvent(slavesFromCM, PodTemplateUtils.cmType, uid, cmname, namespace);
                break;
            case DELETED:
                this.processSlavesForDeleteEvent(slavesFromCM, PodTemplateUtils.cmType, uid, cmname, namespace);
                break;
            case ERROR:
                LOGGER.warning("watch for configMap " + configMap.getMetadata().getName() + " received error event ");
                break;
            default:
                LOGGER.warning("watch for configMap " + configMap.getMetadata().getName() + " received unknown event " + action);
                break;
            }
        } catch (Exception e) {
            LOGGER.log(WARNING, "Caught: " + e, e);
        }
    }
    @Override
    public <T> void eventReceived(io.fabric8.kubernetes.client.Watcher.Action action, T resource) {
        ConfigMap cfgmap = (ConfigMap)resource;
        eventReceived(action, cfgmap);
    }

    private void onInitialConfigMaps(ConfigMapList configMaps) {
        if (configMaps == null)
            return;
        if (PodTemplateUtils.trackedPodTemplates == null) {
            PodTemplateUtils.trackedPodTemplates = new ConcurrentHashMap<>(configMaps.getItems().size());
        }
        List<ConfigMap> items = configMaps.getItems();
        if (items != null) {
            for (ConfigMap configMap : items) {
                try {
                    if (PodTemplateUtils.configMapContainsSlave(configMap) && !PodTemplateUtils.trackedPodTemplates.containsKey(configMap.getMetadata().getUid())) {
                        List<PodTemplate> templates = PodTemplateUtils.podTemplatesFromConfigMap(this, configMap);
                        PodTemplateUtils.trackedPodTemplates.put(configMap.getMetadata().getUid(), templates);
                        for (PodTemplate podTemplate : templates) {
                          PodTemplateUtils.addPodTemplate(podTemplate);
                        }
                    }
                } catch (Exception e) {
                    LOGGER.log(SEVERE,
                            "Failed to update ConfigMap PodTemplates", e);
                }
            }
        }
    }

}
