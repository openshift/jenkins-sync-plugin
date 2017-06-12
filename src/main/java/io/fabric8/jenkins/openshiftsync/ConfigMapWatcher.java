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

import com.thoughtworks.xstream.XStreamException;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.triggers.SafeTimerTask;
import hudson.util.XStream2;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.client.Watcher;

import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;
import org.csanchez.jenkins.plugins.kubernetes.PodVolumes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class ConfigMapWatcher extends BaseWatcher implements Watcher<ConfigMap> {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private Map<String, List<PodTemplate>> trackedConfigMaps;


    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ConfigMapWatcher(String[] namespaces) {
        super(namespaces);
        this.trackedConfigMaps = new ConcurrentHashMap<>();
    }
    
    public Runnable getStartTimerTask() {
        return new SafeTimerTask() {
            @Override
            public void doRun() {
                if (!CredentialsUtils.hasCredentials()) {
                    logger.fine("No Openshift Token credential defined.");
                    return;
                }
                for(String namespace:namespaces) {
                    try {
                        logger.fine("listing ConfigMap resources");
                        final ConfigMapList configMaps = getAuthenticatedOpenShiftClient().configMaps().inNamespace(namespace).list();
                        onInitialConfigMaps(configMaps);
                        logger.fine("handled ConfigMap resources");
                        if (watches.get(namespace) == null) {
                            watches.put(namespace,getAuthenticatedOpenShiftClient().configMaps().inNamespace(namespace).withResourceVersion(configMaps.getMetadata().getResourceVersion()).watch(ConfigMapWatcher.this));
                        }
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to load ConfigMaps: " + e, e);
                    }
                }
            }
        };
    }

    public synchronized void start() {
        super.start();
        // lets process the initial state
        logger.info("Now handling startup config maps!!");
    }

    @Override
    public void eventReceived(Action action, ConfigMap configMap) {
        try {
            switch (action) {
                case ADDED:
                    if(containsSlave(configMap)){
                        List<PodTemplate> templates = podTemplatesFromConfigMap(configMap);
                        trackedConfigMaps.put(configMap.getMetadata().getUid(), templates);
                        for( PodTemplate podTemplate : templates) {
                            JenkinsUtils.addPodTemplate(podTemplate);
                        }
                    }
                    break;

                case MODIFIED:
                    boolean alreadyTracked = trackedConfigMaps.containsKey(configMap.getMetadata().getUid());

                    if(alreadyTracked) {
                        if (containsSlave(configMap)) {
                            // Since the user could have change the immutable image that a PodTemplate uses, we just
                            // recreate the PodTemplate altogether. This makes it so that any changes from within
                            // Jenkins is undone.
                            for( PodTemplate podTemplate : trackedConfigMaps.get(configMap.getMetadata().getUid())) {
                                JenkinsUtils.removePodTemplate(podTemplate);
                            }

                            for( PodTemplate podTemplate : podTemplatesFromConfigMap(configMap)) {
                                JenkinsUtils.addPodTemplate(podTemplate);
                            }
                        } else {
                            // The user modified the configMap to no longer be a jenkins-slave.
                            for( PodTemplate podTemplate : trackedConfigMaps.get(configMap.getMetadata().getUid())) {
                                JenkinsUtils.removePodTemplate(podTemplate);
                            }

                            trackedConfigMaps.remove(configMap.getMetadata().getUid());
                        }
                    } else {
                        if(containsSlave(configMap)) {
                            // The user modified the configMap to be a jenkins-slave

                            List<PodTemplate> templates = podTemplatesFromConfigMap(configMap);
                            trackedConfigMaps.put(configMap.getMetadata().getUid(), templates);
                            for( PodTemplate podTemplate : templates) {
                                JenkinsUtils.addPodTemplate(podTemplate);
                            }
                        }
                    }
                    break;

                case DELETED:
                    if(trackedConfigMaps.containsKey(configMap.getMetadata().getUid())) {
                        for(PodTemplate podTemplate : trackedConfigMaps.get(configMap.getMetadata().getUid())) {
                            JenkinsUtils.removePodTemplate(podTemplate);
                        }
                        trackedConfigMaps.remove(configMap.getMetadata().getUid());
                    }
                    break;
                 // pedantic mvn:findbugs complaint   
                 default:
                     break;

            }
        } catch (Exception e) {
            logger.log(WARNING, "Caught: " + e, e);
        }
    }

    private synchronized void onInitialConfigMaps(ConfigMapList configMaps) {
        if(trackedConfigMaps == null) {
            trackedConfigMaps = new ConcurrentHashMap<>(configMaps.getItems().size());
        }
        List<ConfigMap> items = configMaps.getItems();
        if (items != null) {
            for (ConfigMap configMap : items) {
                try {
                    if(containsSlave(configMap) && !trackedConfigMaps.containsKey(configMap.getMetadata().getUid())) {
                        List<PodTemplate> templates = podTemplatesFromConfigMap(configMap);
                        trackedConfigMaps.put(configMap.getMetadata().getUid(), templates);
                        for( PodTemplate podTemplate : templates) {
                            JenkinsUtils.addPodTemplate(podTemplate);
                        }
                    }
                } catch (Exception e) {
                    logger.log(SEVERE, "Failed to update ConfigMap PodTemplates", e);
                }
            }
        }
    }

    private boolean containsSlave(ConfigMap configMap) {
        return hasSlaveLabelOrAnnotation(configMap.getMetadata().getLabels());
    }

    // podTemplatesFromConfigMap takes every key from a ConfigMap and tries to create a PodTemplate from the contained
    // XML.
    public List<PodTemplate> podTemplatesFromConfigMap(ConfigMap configMap) {
        List<PodTemplate> results = new ArrayList<>();
        Map<String, String> data = configMap.getData();

        XStream2 xStream2 = new XStream2();

        for(Entry<String, String> entry : data.entrySet()) {
            Object podTemplate;
            try {
                podTemplate = xStream2.fromXML(entry.getValue());

                if( podTemplate instanceof PodTemplate ) {
                    results.add((PodTemplate) podTemplate);
                } else {
                    logger.warning("Content of key '" + entry.getKey() + "' in ConfigMap '" + configMap.getMetadata().getName() + "' is not a PodTemplate");
                }
            } catch (XStreamException xse) {
                logger.warning(new IOException("Unable to read key '" + entry.getKey() + "' from ConfigMap '" + configMap.getMetadata().getName() + "'", xse).getMessage());
            } catch (Error e) {
                logger.warning(new IOException("Unable to read key '" + entry.getKey() + "' from ConfigMap '" + configMap.getMetadata().getName() + "'", e).getMessage());
            }
        }

        return results;
    }
}