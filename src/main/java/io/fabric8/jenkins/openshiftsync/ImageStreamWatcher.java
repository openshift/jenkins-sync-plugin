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

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.openshift.api.model.ImageStream;
import io.fabric8.openshift.api.model.ImageStreamList;
import io.fabric8.openshift.api.model.ImageStreamStatus;
import io.fabric8.openshift.api.model.ImageStreamTag;
import io.fabric8.openshift.api.model.TagReference;
import io.fabric8.openshift.client.OpenShiftClient;

public class ImageStreamWatcher extends BaseWatcher {
    private static final String SLAVE_LABEL = "slave-label";
    private static final String IMAGESTREAM_TYPE = isType;
    private final Logger logger = Logger.getLogger(getClass().getName());

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ImageStreamWatcher(String[] namespaces) {
        super(namespaces);
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
                for (String ns : namespaces) {
                    ImageStreamList imageStreams = null;
                    try {
                        logger.fine("listing ImageStream resources");
                        imageStreams = getClient().imageStreams().inNamespace(ns).list();
                        onImageStreamInitialization(imageStreams);
                        logger.fine("handled ImageStream resources");
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to load ImageStreams: " + e, e);
                    }
                    try {
                        String resourceVersion = "0";
                        if (imageStreams == null) {
                            logger.warning("Unable to get image stream list; impacts resource version used for watch");
                        } else {
                            resourceVersion = imageStreams.getMetadata().getResourceVersion();
                        }
                        if (watches.get(ns) == null) {
                            logger.info("creating ImageStream watch for namespace " + ns + " and resource version "
                                    + resourceVersion);
                            ImageStreamWatcher w = ImageStreamWatcher.this;
                            WatcherCallback<ImageStream> watcher = new WatcherCallback<ImageStream>(w, ns);
                            addWatch(ns, getClient().imageStreams().inNamespace(ns).withResourceVersion(resourceVersion)
                                    .watch(watcher));
                        }
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to load ImageStreams: " + e, e);
                    }
                }
            }
        };
    }

    public void start() {
        // lets process the initial state
        logger.info("Now handling startup image streams!!");
        super.start();
    }

    public void eventReceived(Action action, ImageStream imageStream) {
        try {
            List<PodTemplate> slaves = podTemplates(imageStream);
            ObjectMeta metadata = imageStream.getMetadata();
            String uid = metadata.getUid();
            String name = metadata.getName();
            String namespace = metadata.getNamespace();
            switch (action) {
            case ADDED:
                processSlavesForAddEvent(slaves, IMAGESTREAM_TYPE, uid, name, namespace);
                break;
            case MODIFIED:
                processSlavesForModifyEvent(slaves, IMAGESTREAM_TYPE, uid, name, namespace);
                break;
            case DELETED:
                processSlavesForDeleteEvent(slaves, IMAGESTREAM_TYPE, uid, name, namespace);
                break;
            case ERROR:
                logger.warning("watch for imageStream " + name + " received error event ");
                break;
            default:
                logger.warning("watch for imageStream " + name + " received unknown event " + action);
                break;
            }
        } catch (Exception e) {
            logger.log(WARNING, "Caught: " + e, e);
        }
    }

    @Override
    public <T> void eventReceived(Action action, T resource) {
        ImageStream imageStream = (ImageStream) resource;
        eventReceived(action, imageStream);
    }

    private OpenShiftClient getClient() {
        return getAuthenticatedOpenShiftClient();
    }

    private void onImageStreamInitialization(ImageStreamList imageStreams) {
        if (imageStreams != null) {
            List<ImageStream> items = imageStreams.getItems();
            if (items != null) {
                for (ImageStream imageStream : items) {
                    try {
                        List<PodTemplate> agents = podTemplates(imageStream);
                        for (PodTemplate entry : agents) {
                            // watch event might beat the timer - put call is technically fine, but not
                            // addPodTemplate given k8s plugin issues
                            if (!JenkinsUtils.hasPodTemplate(entry)) {
                                JenkinsUtils.addPodTemplate(entry);
                            }
                        }
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to update job", e);
                    }
                }
            }
        }
    }

    private List<PodTemplate> podTemplates(ImageStream imageStream) {
        List<PodTemplate> results = new ArrayList<PodTemplate>();
        // for IS, since we can check labels, check there
        ObjectMeta metadata = imageStream.getMetadata();
        String isName = metadata.getName();
        if (hasSlaveLabelOrAnnotation(metadata.getLabels())) {
            ImageStreamStatus status = imageStream.getStatus();
            String repository = status.getDockerImageRepository();
            Map<String, String> annotations = metadata.getAnnotations();
            PodTemplate podTemplate = podTemplateFromData(isName, repository, annotations);
            results.add(podTemplate);
        }
        results.addAll(extractPodTemplatesFromImageStreamTags(imageStream));
        return results;
    }

    private List<PodTemplate> extractPodTemplatesFromImageStreamTags(ImageStream imageStream) {
        // for slave-label, still check annotations
        // since we cannot create watches on ImageStream tags, we have to
        // traverse the tags and look for the slave label
        List<PodTemplate> results = new ArrayList<PodTemplate>();
        List<TagReference> tags = imageStream.getSpec().getTags();
        for (TagReference tagRef : tags) {
            addPodTemplateFromTag(results, imageStream, tagRef);
        }
        return results;
    }

    private void addPodTemplateFromTag(List<PodTemplate> results, ImageStream imageStream, TagReference tagRef) {
        ObjectMeta metadata = imageStream.getMetadata();
        String ns = metadata.getNamespace();
        String isName = metadata.getName();
        ImageStreamTag tag = null;
        try {
            String tagName = isName + ":" + tagRef.getName();
            tag = getClient().imageStreamTags().inNamespace(ns).withName(tagName).get();
        } catch (Throwable t) {
            logger.log(FINE, "podTemplates", t);
        }
        // for ImageStreamTag (IST), we can't set labels directly, but can inherit, so
        // we check annotations (if ImageStreamTag directly updated) and then labels (if
        // inherited from imagestream)
        if (tag != null) {
            ObjectMeta tagMetadata = tag.getMetadata();
            Map<String, String> tagAnnotations = tagMetadata.getAnnotations();
            String tagName = tagMetadata.getName();
            String tagImageReference = tag.getImage().getDockerImageReference();
            if (hasSlaveLabelOrAnnotation(tagAnnotations)) {
                results.add(this.podTemplateFromData(tagName, tagImageReference, tagAnnotations));
            } else {
                Map<String, String> tagLabels = tagMetadata.getLabels();
                if (hasSlaveLabelOrAnnotation(tagLabels)) {
                    results.add(this.podTemplateFromData(tagName, tagImageReference, tagLabels));
                }
            }
        }
    }

    private PodTemplate podTemplateFromData(String name, String image, Map<String, String> map) {
        // node, pod names cannot have colons
        String templateName = name.replaceAll(":", ".");
        String label = (map != null && map.containsKey(SLAVE_LABEL)) ? map.get(SLAVE_LABEL) : name;
        PodTemplate result = JenkinsUtils.podTemplateInit(templateName, image, label);
        return result;
    }
}