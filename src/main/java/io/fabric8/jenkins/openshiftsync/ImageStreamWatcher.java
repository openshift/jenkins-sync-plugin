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
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.openshift.api.model.ImageStream;
import io.fabric8.openshift.api.model.ImageStreamList;
import io.fabric8.openshift.api.model.ImageStreamTag;
import io.fabric8.openshift.api.model.TagReference;

import org.csanchez.jenkins.plugins.kubernetes.PodVolumes;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class ImageStreamWatcher extends BaseWatcher implements Watcher<ImageStream> {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private Map<String, PodTemplate> trackedImageStreams;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ImageStreamWatcher(String[] namespaces) {
        super(namespaces);
        this.trackedImageStreams = new ConcurrentHashMap<>();
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
                        logger.fine("listing ImageStream resources");
                        final ImageStreamList imageStreams = getAuthenticatedOpenShiftClient().imageStreams().inNamespace(namespace).list();
                        onInitialImageStream(imageStreams);
                        logger.fine("handled ImageStream resources");
                        if (watches.get(namespace) == null) {
                            watches.put(namespace,getAuthenticatedOpenShiftClient().imageStreams().inNamespace(namespace).withResourceVersion(imageStreams.getMetadata().getResourceVersion()).watch(ImageStreamWatcher.this));
                        }
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to load ImageStreams: " + e, e);
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
    
    @Override
    public void eventReceived(Action action, ImageStream imageStream) {
        try {
            Map<String,PodTemplate> slavesFromIS = podTemplates(imageStream);
            String isuuid = imageStream.getMetadata().getUid();
            switch (action) {
                case ADDED:
                    for (Map.Entry<String,PodTemplate> entry : slavesFromIS.entrySet()) {
                        trackedImageStreams.put(entry.getKey(), entry.getValue());
                        JenkinsUtils.addPodTemplate(entry.getValue());
                    }
                    break;

                case MODIFIED:
                    // add/replace entries from latest IS incarnation
                    for (Map.Entry<String,PodTemplate> entry : slavesFromIS.entrySet()) {
                        boolean alreadyTracked = trackedImageStreams.containsKey(entry.getKey());
                        if (alreadyTracked) {
                            JenkinsUtils.removePodTemplate(trackedImageStreams.get(entry.getKey()));
                        }
                        JenkinsUtils.addPodTemplate(entry.getValue());
                        trackedImageStreams.put(entry.getKey(), entry.getValue());
                    }
                    // go back and remove tracked items that no longer are marked slaves
                    Set<Entry<String, PodTemplate>> entryset = trackedImageStreams.entrySet();
                    for (Entry<String, PodTemplate> entry : entryset) {
                        // if the entry is not for the IS from the watch event, skip
                        if (!entry.getKey().startsWith(isuuid))
                            continue;
                        if (!slavesFromIS.containsKey(entry.getKey())) {
                            JenkinsUtils.removePodTemplate(entry.getValue());
                            entryset.remove(entry); // removes from trackedImageStreams as well
                        }
                    }
                    break;

                case DELETED:
                    entryset = trackedImageStreams.entrySet();
                    for (Entry<String, PodTemplate> entry : entryset) {
                        // if the entry is for the IS from the watch event, delete
                        if (entry.getKey().startsWith(isuuid)) {
                            JenkinsUtils.removePodTemplate(entry.getValue());
                            entryset.remove(entry); // removes from trackedImageStreams as well
                        }
                    }
                    break;

            }
        } catch (Exception e) {
            logger.log(WARNING, "Caught: " + e, e);
        }
    }

    private synchronized void onInitialImageStream(ImageStreamList imageStreams) {
        if(trackedImageStreams == null) {
            trackedImageStreams = new ConcurrentHashMap<>(imageStreams.getItems().size());
        }
        List<ImageStream> items = imageStreams.getItems();
        if (items != null) {
            for (ImageStream imageStream : items) {
                try {
                    Map<String,PodTemplate> slavesFromIS = podTemplates(imageStream);
                    for (Map.Entry<String,PodTemplate> entry : slavesFromIS.entrySet()) {
                        trackedImageStreams.put(entry.getKey(), entry.getValue());
                        JenkinsUtils.addPodTemplate(entry.getValue());
                    }
                } catch (Exception e) {
                    logger.log(SEVERE, "Failed to update job", e);
                }
            }
        }
    }

    private Map<String,PodTemplate> podTemplates(ImageStream imageStream) {
        Map<String,PodTemplate> results = new HashMap<String,PodTemplate>();
        String isuuid = imageStream.getMetadata().getUid();
        if (hasSlaveLabelOrAnnotation(imageStream.getMetadata().getLabels()))
            results.put(isuuid,
                    podTemplateFromData(imageStream.getMetadata().getName(),
                    imageStream.getStatus().getDockerImageRepository(),
                    imageStream.getMetadata().getLabels()));
        
        String namespace = imageStream.getMetadata().getNamespace();
        
        for (TagReference tagRef : imageStream.getSpec().getTags()) {
            String istkey = "";
            if (tagRef.getFrom() != null)
                istkey = tagRef.getFrom().getName();
            if (istkey.contains("/")) {
                String[] parts = istkey.split("/");
                if (parts.length > 1)
                    istkey = parts[1];
            }
            if (istkey.length() > 0) {
                ImageStreamTag ist = getAuthenticatedOpenShiftClient().imageStreamTags().inNamespace(namespace).withName(istkey).get();
                if (ist != null && hasSlaveLabelOrAnnotation(ist.getMetadata().getAnnotations())) {
                    // for the key, we do isuuid + istuuid so we can distinguish ist entries 
                    // during cleanup actions induced by IS watch events
                    results.put(isuuid + ist.getMetadata().getUid(),
                            this.podTemplateFromData(ist.getMetadata().getName(), 
                            ist.getImage().getDockerImageReference(), 
                            ist.getMetadata().getAnnotations()));
                }
            }
        }
        return results;
    }
    
    private PodTemplate podTemplateFromData(String name, String image, Map<String,String> map) {
        String label = null;
        if(map.containsKey("slave-label")) {
            label = map.get("slave-label");
        } else {
            label = name;
        }

        PodTemplate result = JenkinsUtils.podTemplateInit(name, image, label);

        return result;
    }
}