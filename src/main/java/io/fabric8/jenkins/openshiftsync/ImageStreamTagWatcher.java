package io.fabric8.jenkins.openshiftsync;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.openshift.api.model.ImageStream;
import io.fabric8.openshift.api.model.ImageStreamList;
import io.fabric8.openshift.api.model.ImageStreamTag;
import io.fabric8.openshift.api.model.ImageStreamTagList;
import jenkins.util.Timer;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;
import org.csanchez.jenkins.plugins.kubernetes.PodVolumes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static java.net.HttpURLConnection.HTTP_GONE;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class ImageStreamTagWatcher implements Watcher<ImageStreamTag> {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final String[] namespaces;
    private Map<String,Watch> imageStreamTagWatches;
    private Map<String, PodTemplate> trackedImageStreamTags;

    private ScheduledFuture relister;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ImageStreamTagWatcher(String[] namespaces) {
        this.namespaces = namespaces;
        this.imageStreamTagWatches =new HashMap<String,Watch>();
        this.trackedImageStreamTags = new ConcurrentHashMap<>();
    }

    public synchronized void start() {
        // lets process the initial state
        logger.info("Now handling startup image stream tags!!");
        // lets do this in a background thread to avoid errors like:
        //  Tried proxying io.fabric8.jenkins.openshiftsync.GlobalPluginConfiguration to support a circular dependency, but it is not an interface.
        Runnable task = new SafeTimerTask() {
            @Override
            public void doRun() {
                for(String namespace:namespaces) {
                    try {
                        logger.fine("listing ImageStreamTag resources");
                        final ImageStreamTagList imageStreamTags = getAuthenticatedOpenShiftClient().imageStreamTags().inNamespace(namespace).list();
                        onInitialImageStreamTag(imageStreamTags);
                        logger.fine("handled ImageStreamTag resources");
                        if (imageStreamTagWatches.get(namespace) == null) {
                            imageStreamTagWatches.put(namespace,getAuthenticatedOpenShiftClient().imageStreamTags().inNamespace(namespace).withResourceVersion(imageStreamTags.getMetadata().getResourceVersion()).watch(ImageStreamTagWatcher.this));
                        }
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to load ImageStreams: " + e, e);
                    }
                }
            }
        };
        relister = Timer.get().scheduleAtFixedRate(task, 100, 10 * 1000, TimeUnit.MILLISECONDS);
    }

    @Override
    public void eventReceived(Action action, ImageStreamTag imageStreamTag) {
        try {
            switch (action) {
                case ADDED:
                    if(isSlaveImage(imageStreamTag)){
                        PodTemplate podTemplate = podTemplateFromImageStreamTag(imageStreamTag);
                        trackedImageStreamTags.put(imageStreamTag.getMetadata().getUid(), podTemplate);
                        OpenShiftUtils.addPodTemplate(podTemplate);
                    }
                    break;

                case MODIFIED:
                    boolean alreadyTracked = trackedImageStreamTags.containsKey(imageStreamTag.getMetadata().getUid());

                    if(alreadyTracked) {
                        if (isSlaveImage(imageStreamTag)) {
                            // Since the user could have change the immutable image that a PodTemplate uses, we just
                            // recreate the PodTemplate altogether. This makes it so that any changes from within
                            // Jenkins is undone.
                            OpenShiftUtils.removePodTemplate(trackedImageStreamTags.get(imageStreamTag.getMetadata().getUid()));
                            PodTemplate podTemplate = podTemplateFromImageStreamTag(imageStreamTag);
                            OpenShiftUtils.addPodTemplate(podTemplate);
                            trackedImageStreamTags.put(imageStreamTag.getMetadata().getUid(),podTemplate);
                        } else {
                            // The user modified the imageStreamTag to no longer be a jenkins-slave.
                            OpenShiftUtils.removePodTemplate(trackedImageStreamTags.get(imageStreamTag.getMetadata().getUid()));
                            trackedImageStreamTags.remove(imageStreamTag.getMetadata().getUid());
                        }
                    } else {
                        if(isSlaveImage(imageStreamTag)) {
                            // The user modified the imageStreamTag to be a jenkins-slave
                            PodTemplate podTemplate = podTemplateFromImageStreamTag(imageStreamTag);
                            trackedImageStreamTags.put(imageStreamTag.getMetadata().getUid(), podTemplate);
                            OpenShiftUtils.addPodTemplate(podTemplate);
                        }
                    }
                    break;

                case DELETED:
                    if(trackedImageStreamTags.containsKey(imageStreamTag.getMetadata().getUid())) {
                        OpenShiftUtils.removePodTemplate(trackedImageStreamTags.get(imageStreamTag.getMetadata().getUid()));
                        trackedImageStreamTags.remove(imageStreamTag.getMetadata().getUid());
                    }
                    break;

            }
        } catch (Exception e) {
            logger.log(WARNING, "Caught: " + e, e);
        }
    }

    public synchronized void stop() {
        if (relister != null && !relister.isDone()) {
            relister.cancel(true);
            relister = null;
        }

        for(Map.Entry<String,Watch> entry: imageStreamTagWatches.entrySet()) {
            entry.getValue().close();
            imageStreamTagWatches.remove(entry.getKey());
        }
    }

    @Override
    public synchronized void onClose(KubernetesClientException e) {
        if (e != null) {
            logger.warning(e.toString());

            if (e.getStatus() != null && e.getStatus().getCode() == HTTP_GONE) {
                stop();
                start();
            }
        }
    }

    private synchronized void onInitialImageStreamTag(ImageStreamTagList imageStreamTags) {
        if(trackedImageStreamTags == null) {
            trackedImageStreamTags = new ConcurrentHashMap<>(imageStreamTags.getItems().size());
        }
        List<ImageStreamTag> items = imageStreamTags.getItems();
        if (items != null) {
            for (ImageStreamTag imageStreamTag : items) {
                try {
                    if(isSlaveImage(imageStreamTag) && !trackedImageStreamTags.containsKey(imageStreamTag.getMetadata().getUid())) {
                        PodTemplate podTemplate = podTemplateFromImageStreamTag(imageStreamTag);
                        trackedImageStreamTags.put(imageStreamTag.getMetadata().getUid(), podTemplate);
                        OpenShiftUtils.addPodTemplate(podTemplate);
                    }
                } catch (Exception e) {
                    logger.log(SEVERE, "Failed to update job", e);
                }
            }
        }
    }

    private boolean isSlaveImage(ImageStreamTag imageStreamTag) {
        if(imageStreamTag.getMetadata().getAnnotations() != null) {
            return imageStreamTag.getMetadata().getAnnotations().containsKey("role") && imageStreamTag.getMetadata().getAnnotations().get("role").equals("jenkins-slave");
        }

        return false;
    }

    public PodTemplate podTemplateFromImageStreamTag(ImageStreamTag imageStreamTag) {
        PodTemplate result = new PodTemplate(imageStreamTag.getImage().getDockerImageReference(), new ArrayList<PodVolumes.PodVolume>());

        if(imageStreamTag.getMetadata().getAnnotations().containsKey("slave-label")) {
            result.setLabel(imageStreamTag.getMetadata().getAnnotations().get("slave-label"));
        } else {
            result.setLabel(imageStreamTag.getMetadata().getName());
        }

        result.setName(imageStreamTag.getMetadata().getName());
        result.setInstanceCap(Integer.MAX_VALUE);

        result.setCommand("");

        return result;
    }
}