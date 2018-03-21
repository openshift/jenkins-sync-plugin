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
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.openshift.api.model.ImageStream;
import io.fabric8.openshift.api.model.ImageStreamList;
import io.fabric8.openshift.api.model.ImageStreamTag;
import io.fabric8.openshift.api.model.TagReference;

import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class ImageStreamWatcher extends BaseWatcher {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final List<String> predefinedOpenShiftSlaves;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ImageStreamWatcher(String[] namespaces) {
        super(namespaces);
        this.predefinedOpenShiftSlaves = new ArrayList<String>();
        this.predefinedOpenShiftSlaves.add("maven");
        this.predefinedOpenShiftSlaves.add("nodejs");
    }

    @Override
    public int getListIntervalInSeconds() {
        return GlobalPluginConfiguration.get().getImageStreamListInterval();
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
                    ImageStreamList imageStreams = null;
                    try {
                        logger.fine("listing ImageStream resources");
                        imageStreams = getAuthenticatedOpenShiftClient()
                                .imageStreams().inNamespace(namespace).list();
                        onInitialImageStream(imageStreams);
                        logger.fine("handled ImageStream resources");
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to load ImageStreams: " + e,
                                e);
                    }
                    try {
                        String resourceVersion = "0";
                        if (imageStreams == null) {
                            logger.warning("Unable to get image stream list; impacts resource version used for watch");
                        } else {
                            resourceVersion = imageStreams.getMetadata()
                                    .getResourceVersion();
                        }
                        synchronized(ImageStreamWatcher.this) {
                            if (watches.get(namespace) == null) {
                                logger.info("creating ImageStream watch for namespace "
                                        + namespace
                                        + " and resource version "
                                        + resourceVersion);
                                watches.put(
                                        namespace,
                                        getAuthenticatedOpenShiftClient()
                                                .imageStreams()
                                                .inNamespace(namespace)
                                                .withResourceVersion(
                                                        resourceVersion)
                                                        .watch(new WatcherCallback<ImageStream>(ImageStreamWatcher.this,
                                                                namespace)));
                            }
                        }
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to load ImageStreams: " + e,
                                e);
                    }
                }
            }
        };
    }

    public synchronized void start() {
        // lets process the initial state
        logger.info("Now handling startup image streams!!");
        super.start();
    }

    public void eventReceived(Action action, ImageStream imageStream) {
        try {
            List<PodTemplate> slavesFromIS = podTemplates(imageStream);
            String isname = imageStream.getMetadata().getName();
            switch (action) {
            case ADDED:
                for (PodTemplate entry : slavesFromIS) {
                    // timer might beat watch event - put call is technically
                    // fine, but
                    // not addPodTemplate given k8s plugin issues
                    if (JenkinsUtils.hasPodTemplate(entry.getName()))
                        continue;
                    if (this.predefinedOpenShiftSlaves
                            .contains(entry.getName()))
                        continue;
                    JenkinsUtils.addPodTemplate(entry);
                }
                break;

            case MODIFIED:
                // add/replace entries from latest IS incarnation
                for (PodTemplate entry : slavesFromIS) {
                    if (this.predefinedOpenShiftSlaves
                            .contains(entry.getName()))
                        continue;
                    JenkinsUtils.addPodTemplate(entry);
                }
                // go back and remove tracked items that no longer are marked
                // slaves
                Iterator<PodTemplate> iter = JenkinsUtils.getPodTemplates()
                        .iterator();
                while (iter.hasNext()) {
                    PodTemplate podTemplate = iter.next();
                    // either actual is or an IST, where and IST starts with
                    // the IS name followed by "." (since we have to replace
                    // ":" with "." elsewhere
                    if (!podTemplate.getName().equals(isname) && 
                        !podTemplate.getName().startsWith(isname + "."))
                        continue;
                    if (this.predefinedOpenShiftSlaves.contains(podTemplate
                            .getName()))
                        continue;
                    boolean keep = false;
                    // if an IST based slave, see that particular tag is still
                    // in list
                    for (PodTemplate entry : slavesFromIS) {
                        if (entry.getName().equals(podTemplate.getName())) {
                            keep = true;
                            break;
                        }
                    }
                    if (!keep)
                        JenkinsUtils.removePodTemplate(podTemplate);
                }
                break;

            case DELETED:
                iter = JenkinsUtils.getPodTemplates().iterator();
                while (iter.hasNext()) {
                    PodTemplate podTemplate = iter.next();
                    // either actual is or an IST, where and IST starts with
                    // the IS name followed by "." (since we have to replace
                    // ":" with "." elsewhere
                    if (!podTemplate.getName().equals(isname) &&
                        !podTemplate.getName().startsWith(isname + "."))
                        continue;
                    if (this.predefinedOpenShiftSlaves.contains(podTemplate
                            .getName()))
                        continue;
                    JenkinsUtils.removePodTemplate(podTemplate);
                }
                break;

            case ERROR:
                logger.warning("watch for imageStream " + imageStream.getMetadata().getName() + " received error event ");
                break;
            default:
                logger.warning("watch for imageStream " + imageStream.getMetadata().getName() + " received unknown event " + action);
                break;
            }
        } catch (Exception e) {
            logger.log(WARNING, "Caught: " + e, e);
        }
    }
    @Override
    public <T> void eventReceived(io.fabric8.kubernetes.client.Watcher.Action action, T resource) {
        ImageStream imageStream = (ImageStream)resource;
        eventReceived(action, imageStream);
    }

    private synchronized void onInitialImageStream(ImageStreamList imageStreams) {
        if (imageStreams == null)
            return;
        List<ImageStream> items = imageStreams.getItems();
        if (items != null) {
            for (ImageStream imageStream : items) {
                try {
                    List<PodTemplate> slavesFromIS = podTemplates(imageStream);
                    for (PodTemplate entry : slavesFromIS) {
                        // watch event might beat the timer - put call is
                        // technically fine, but
                        // not addPodTemplate given k8s plugin issues
                        if (JenkinsUtils.hasPodTemplate(entry.getName()))
                            continue;
                        JenkinsUtils.addPodTemplate(entry);
                    }
                } catch (Exception e) {
                    logger.log(SEVERE, "Failed to update job", e);
                }
            }
        }
    }

    private List<PodTemplate> podTemplates(ImageStream imageStream) {
        List<PodTemplate> results = new ArrayList<PodTemplate>();
        // for IS, since we can check labels, check there
        if (hasSlaveLabelOrAnnotation(imageStream.getMetadata().getLabels())) {
            results.add(podTemplateFromData(
                    imageStream.getMetadata().getName(), imageStream
                            .getStatus().getDockerImageRepository(),
                    imageStream.getMetadata().getAnnotations())); // for
                                                                  // slave-label,
                                                                  // still check
                                                                  // annotations
        }

        String namespace = imageStream.getMetadata().getNamespace();

        // since we cannot create watches on ImageStream tags, we have to
        // traverse
        // the tags and look for the slave label
        for (TagReference tagRef : imageStream.getSpec().getTags()) {
            ImageStreamTag ist = null;
            try {
                ist = getAuthenticatedOpenShiftClient()
                        .imageStreamTags()
                        .inNamespace(namespace)
                        .withName(
                                imageStream.getMetadata().getName() + ":"
                                        + tagRef.getName()).get();
            } catch (Throwable t) {
                logger.log(Level.FINE, "podTemplates", t);
            }
            // for IST, can't set labels, so check annotations
            if (ist != null
                    && hasSlaveLabelOrAnnotation(ist.getMetadata()
                            .getAnnotations())) {
                // note, pod names cannot have colons
                results.add(this.podTemplateFromData(ist.getMetadata()
                        .getName().replaceAll(":", "."), ist.getImage()
                        .getDockerImageReference(), ist.getMetadata()
                        .getAnnotations()));
            }
        }
        return results;
    }

    private PodTemplate podTemplateFromData(String name, String image,
            Map<String, String> map) {
        String label = null;
        if (map != null && map.containsKey("slave-label")) {
            label = map.get("slave-label");
        } else {
            label = name;
        }

        PodTemplate result = JenkinsUtils.podTemplateInit(name, image, label);

        return result;
    }
}