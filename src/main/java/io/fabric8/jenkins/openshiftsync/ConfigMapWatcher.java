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
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.openshift.api.model.ImageStreamTag;

import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;

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

public class ConfigMapWatcher extends BaseWatcher {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private ConcurrentHashMap<String, List<PodTemplate>> trackedConfigMaps;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ConfigMapWatcher(String[] namespaces) {
        super(namespaces);
        this.trackedConfigMaps = new ConcurrentHashMap<>();
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
                    logger.fine("No Openshift Token credential defined.");
                    return;
                }
                for (String namespace : namespaces) {
                    ConfigMapList configMaps = null;
                    try {
                        logger.fine("listing ConfigMap resources");
                        configMaps = getAuthenticatedOpenShiftClient()
                                .configMaps().inNamespace(namespace).list();
                        onInitialConfigMaps(configMaps);
                        logger.fine("handled ConfigMap resources");
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to load ConfigMaps: " + e, e);
                    }
                    try {
                        String resourceVersion = "0";
                        if (configMaps == null) {
                            logger.warning("Unable to get config map list; impacts resource version used for watch");
                        } else {
                            resourceVersion = configMaps.getMetadata()
                                    .getResourceVersion();
                        }
                        if (watches.get(namespace) == null) {
                            logger.info("creating ConfigMap watch for namespace "
                                    + namespace
                                    + " and resource version "
                                    + resourceVersion);
                            watches.put(
                                    namespace,
                                    getAuthenticatedOpenShiftClient()
                                    .configMaps()
                                    .inNamespace(namespace)
                                    .withResourceVersion(
                                            resourceVersion)
                                            .watch(new WatcherCallback<ConfigMap>(ConfigMapWatcher.this,namespace)));
                        }
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to load ConfigMaps: " + e, e);
                    }
                }
            }
        };
    }

    public void start() {
        super.start();
        // lets process the initial state
        logger.info("Now handling startup config maps!!");
    }

    public void eventReceived(Action action, ConfigMap configMap) {
        try {
            switch (action) {
            case ADDED:
                if (containsSlave(configMap)) {
                    List<PodTemplate> templates = podTemplatesFromConfigMap(configMap);
                    trackedConfigMaps.put(configMap.getMetadata().getUid(),
                            templates);
                    for (PodTemplate podTemplate : templates) {
                        JenkinsUtils.addPodTemplate(podTemplate);
                    }
                }
                break;

            case MODIFIED:
                boolean alreadyTracked = trackedConfigMaps
                        .containsKey(configMap.getMetadata().getUid());

                if (alreadyTracked) {
                    if (containsSlave(configMap)) {
                        // Since the user could have change the immutable image
                        // that a PodTemplate uses, we just
                        // recreate the PodTemplate altogether. This makes it so
                        // that any changes from within
                        // Jenkins is undone.
                        for (PodTemplate podTemplate : trackedConfigMaps
                                .get(configMap.getMetadata().getUid())) {
                            JenkinsUtils.removePodTemplate(podTemplate);
                        }

                        for (PodTemplate podTemplate : podTemplatesFromConfigMap(configMap)) {
                            JenkinsUtils.addPodTemplate(podTemplate);
                        }
                    } else {
                        // The user modified the configMap to no longer be a
                        // jenkins-slave.
                        for (PodTemplate podTemplate : trackedConfigMaps
                                .get(configMap.getMetadata().getUid())) {
                            JenkinsUtils.removePodTemplate(podTemplate);
                        }

                        trackedConfigMaps.remove(configMap.getMetadata()
                                .getUid());
                    }
                } else {
                    if (containsSlave(configMap)) {
                        // The user modified the configMap to be a jenkins-slave

                        List<PodTemplate> templates = podTemplatesFromConfigMap(configMap);
                        trackedConfigMaps.put(configMap.getMetadata().getUid(),
                                templates);
                        for (PodTemplate podTemplate : templates) {
                            JenkinsUtils.addPodTemplate(podTemplate);
                        }
                    }
                }
                break;

            case DELETED:
                if (trackedConfigMaps.containsKey(configMap.getMetadata()
                        .getUid())) {
                    for (PodTemplate podTemplate : trackedConfigMaps
                            .get(configMap.getMetadata().getUid())) {
                        JenkinsUtils.removePodTemplate(podTemplate);
                    }
                    trackedConfigMaps.remove(configMap.getMetadata().getUid());
                }
                break;
            case ERROR:
                logger.warning("watch for configMap " + configMap.getMetadata().getName() + " received error event ");
                break;
            default:
                logger.warning("watch for configMap " + configMap.getMetadata().getName() + " received unknown event " + action);
                break;

            }
        } catch (Exception e) {
            logger.log(WARNING, "Caught: " + e, e);
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
        if (trackedConfigMaps == null) {
            trackedConfigMaps = new ConcurrentHashMap<>(configMaps.getItems()
                    .size());
        }
        List<ConfigMap> items = configMaps.getItems();
        if (items != null) {
            for (ConfigMap configMap : items) {
                try {
                    if (containsSlave(configMap)
                            && !trackedConfigMaps.containsKey(configMap
                                    .getMetadata().getUid())) {
                        List<PodTemplate> templates = podTemplatesFromConfigMap(configMap);
                        trackedConfigMaps.put(configMap.getMetadata().getUid(),
                                templates);
                        for (PodTemplate podTemplate : templates) {
                            JenkinsUtils.addPodTemplate(podTemplate);
                        }
                    }
                } catch (Exception e) {
                    logger.log(SEVERE,
                            "Failed to update ConfigMap PodTemplates", e);
                }
            }
        }
    }

    private boolean containsSlave(ConfigMap configMap) {
        return hasSlaveLabelOrAnnotation(configMap.getMetadata().getLabels());
    }

    private boolean hasOneAndOnlyOneWithSomethingAfter(String str, String substr) {
        return str.contains(substr)
                && str.indexOf(substr) == str.lastIndexOf(substr)
                && str.indexOf(substr) < str.length();
    }

    private static final String SPECIAL_IST_PREFIX = "imagestreamtag:";
    private static final int SPECIAL_IST_PREFIX_IDX = SPECIAL_IST_PREFIX
            .length();

    // podTemplatesFromConfigMap takes every key from a ConfigMap and tries to
    // create a PodTemplate from the contained
    // XML.
    public List<PodTemplate> podTemplatesFromConfigMap(ConfigMap configMap) {
        List<PodTemplate> results = new ArrayList<>();
        Map<String, String> data = configMap.getData();

        XStream2 xStream2 = new XStream2();

        for (Entry<String, String> entry : data.entrySet()) {
            Object podTemplate;
            try {
                podTemplate = xStream2.fromXML(entry.getValue());

                String warningPrefix = "Content of key '" + entry.getKey()
                        + "' in ConfigMap '"
                        + configMap.getMetadata().getName();
                if (podTemplate instanceof PodTemplate) {
                    PodTemplate pt = (PodTemplate) podTemplate;

                    String image = pt.getImage();
                    try {
                        // if requested via special prefix, convert this images
                        // entry field, if not already fully qualified, as if
                        // it were an IST
                        // IST of form [optional_namespace]/imagestreamname:tag
                        // checks based on ParseImageStreamTagName in
                        // https://github.com/openshift/origin/blob/master/pkg/image/apis/image/helper.go
                        if (image.startsWith(SPECIAL_IST_PREFIX)) {
                            image = image.substring(SPECIAL_IST_PREFIX_IDX);
                            if (image.contains("@")) {
                                logger.warning(warningPrefix
                                        + " the presence of @ implies an image stream image, not an image stream tag, "
                                        + " so no ImageStreamTag to Docker image reference translation was performed.");
                            } else {
                                boolean hasNamespace = hasOneAndOnlyOneWithSomethingAfter(
                                        image, "/");
                                boolean hasTag = hasOneAndOnlyOneWithSomethingAfter(
                                        image, ":");
                                String namespace = getAuthenticatedOpenShiftClient()
                                        .getNamespace();
                                String isName = image;
                                String newImage = null;
                                if (hasNamespace) {
                                    String[] parts = image.split("/");
                                    namespace = parts[0];
                                    isName = parts[1];
                                }
                                if (hasTag) {
                                    ImageStreamTag ist = getAuthenticatedOpenShiftClient()
                                            .imageStreamTags()
                                            .inNamespace(namespace)
                                            .withName(isName).get();
                                    if (ist != null
                                            && ist.getImage() != null
                                            && ist.getImage()
                                                    .getDockerImageReference() != null
                                            && ist.getImage()
                                                    .getDockerImageReference()
                                                    .length() > 0) {
                                        newImage = ist.getImage()
                                                .getDockerImageReference();
                                        logger.fine(String
                                                .format("Converting image ref %s as an imagestreamtag %s to fully qualified image %s",
                                                        image, isName, newImage));
                                    } else {
                                        logger.warning(warningPrefix
                                                + " used the 'imagestreamtag:' prefix in the image field, but the subsequent value, while a valid ImageStreamTag reference,"
                                                + " produced no valid ImageStreaTag upon lookup,"
                                                + " so no ImageStreamTag to Docker image reference translation was performed.");
                                    }
                                } else {
                                    logger.warning(warningPrefix
                                            + " used the 'imagestreamtag:' prefix in the image field, but the subsequent value had no tag indicator,"
                                            + " so no ImageStreamTag to Docker image reference translation was performed.");
                                }

                                if (newImage != null) {
                                    logger.fine("translated IST ref " + image
                                            + " to docker image ref "
                                            + newImage);
                                    pt.getContainers().get(0)
                                            .setImage(newImage);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        if (logger.isLoggable(Level.FINE))
                            logger.log(Level.FINE, "podTemplateFromConfigMap",
                                    t);
                    }
                    results.add((PodTemplate) podTemplate);
                } else {
                    logger.warning(warningPrefix + "' is not a PodTemplate");
                }
            } catch (XStreamException xse) {
                logger.warning(new IOException("Unable to read key '"
                        + entry.getKey() + "' from ConfigMap '"
                        + configMap.getMetadata().getName() + "'", xse)
                        .getMessage());
            } catch (Error e) {
                logger.warning(new IOException("Unable to read key '"
                        + entry.getKey() + "' from ConfigMap '"
                        + configMap.getMetadata().getName() + "'", e)
                        .getMessage());
            }
        }

        return results;
    }
}