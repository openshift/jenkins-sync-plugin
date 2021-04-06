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

import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

import java.util.List;
import java.util.logging.Logger;

import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.openshift.api.model.ImageStream;
import io.fabric8.openshift.api.model.ImageStreamList;

public class ImageStreamWatcher extends BaseWatcher {
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
                for (String namespace : namespaces) {
                    addWatchForNamespace(namespace);
                }
            }
        };
    }

    public void addWatchForNamespace(String namespace) {
        ImageStreamList imageStreams = null;
        try {
            logger.fine("listing ImageStream resources");
            imageStreams = OpenShiftUtils.getOpenshiftClient().imageStreams().inNamespace(namespace).list();
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
            if (watches.get(namespace) == null) {
                logger.info("creating ImageStream watch for namespace " + namespace + " and resource version " + resourceVersion);
                ImageStreamWatcher w = ImageStreamWatcher.this;
                WatcherCallback<ImageStream> watcher = new WatcherCallback<ImageStream>(w, namespace);
                addWatch(namespace, OpenShiftUtils.getOpenshiftClient().imageStreams().inNamespace(namespace).withResourceVersion(resourceVersion).watch(watcher));
            }
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to load ImageStreams: " + e, e);
        }
    }

    public void startAfterOnClose(String namespace) {
        synchronized (this.lock) {
             addWatchForNamespace(namespace);
        }
    }

    public void start() {
        // lets process the initial state
        logger.info("Now handling startup image streams!!");
        super.start();
    }

    public void eventReceived(Action action, ImageStream imageStream) {
        try {
            List<PodTemplate> slaves = PodTemplateUtils.getPodTemplatesListFromImageStreams(imageStream);
            ObjectMeta metadata = imageStream.getMetadata();
            String uid = metadata.getUid();
            String name = metadata.getName();
            String namespace = metadata.getNamespace();
            switch (action) {
            case ADDED:
                processSlavesForAddEvent(slaves, PodTemplateUtils.IMAGESTREAM_TYPE, uid, name, namespace);
                break;
            case MODIFIED:
                processSlavesForModifyEvent(slaves, PodTemplateUtils.IMAGESTREAM_TYPE, uid, name, namespace);
                break;
            case DELETED:
                processSlavesForDeleteEvent(slaves, PodTemplateUtils.IMAGESTREAM_TYPE, uid, name, namespace);
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

  private void onImageStreamInitialization(ImageStreamList imageStreams) {
        if (imageStreams != null) {
            List<ImageStream> items = imageStreams.getItems();
            if (items != null) {
                for (ImageStream imageStream : items) {
                    try {
                        List<PodTemplate> agents = PodTemplateUtils.getPodTemplatesListFromImageStreams(imageStream);
                        for (PodTemplate entry : agents) {
                            // watch event might beat the timer - put call is technically fine, but not
                            // addPodTemplate given k8s plugin issues
                            if (!PodTemplateUtils.hasPodTemplate(entry)) {
                                PodTemplateUtils.addPodTemplate(entry);
                            }
                        }
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to update job", e);
                    }
                }
            }
        }
    }

}