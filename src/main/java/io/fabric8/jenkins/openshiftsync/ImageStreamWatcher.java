package io.fabric8.jenkins.openshiftsync;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.openshift.api.model.ImageStream;
import io.fabric8.openshift.api.model.ImageStreamList;
import jenkins.util.Timer;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;
import org.csanchez.jenkins.plugins.kubernetes.PodVolumes;
import org.omg.PortableServer.POA;

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

public class ImageStreamWatcher implements Watcher<ImageStream> {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final String[] namespaces;
    private Map<String,Watch> imageStreamWatches;
    private Map<String, PodTemplate> trackedImageStreams;

    private ScheduledFuture relister;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ImageStreamWatcher(String[] namespaces) {
        this.namespaces = namespaces;
        this.imageStreamWatches =new HashMap<String,Watch>();
        this.trackedImageStreams = new ConcurrentHashMap<>();
    }

    public synchronized void start() {
        // lets process the initial state
        logger.info("Now handling startup image streams!!");
        // lets do this in a background thread to avoid errors like:
        //  Tried proxying io.fabric8.jenkins.openshiftsync.GlobalPluginConfiguration to support a circular dependency, but it is not an interface.
        Runnable task = new SafeTimerTask() {
            @Override
            public void doRun() {
                for(String namespace:namespaces) {
                    try {
                        logger.fine("listing ImageStream resources");
                        final ImageStreamList imageStreams = getAuthenticatedOpenShiftClient().imageStreams().inNamespace(namespace).list();
                        onInitialImageStream(imageStreams);
                        logger.fine("handled ImageStream resources");
                        if (imageStreamWatches.get(namespace) == null) {
                            imageStreamWatches.put(namespace,getAuthenticatedOpenShiftClient().imageStreams().inNamespace(namespace).withResourceVersion(imageStreams.getMetadata().getResourceVersion()).watch(ImageStreamWatcher.this));
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
    public void eventReceived(Action action, ImageStream imageStream) {
        try {
            switch (action) {
                case ADDED:
                    if(isSlaveImage(imageStream)){
                        PodTemplate podTemplate = podTemplateFromImageStream(imageStream);
                        trackedImageStreams.put(imageStream.getMetadata().getUid(), podTemplate);
                        OpenShiftUtils.addPodTemplate(podTemplate);
                    }
                    break;

                case MODIFIED:
                    boolean alreadyTracked = trackedImageStreams.containsKey(imageStream.getMetadata().getUid());

                    if(alreadyTracked) {
                        if (isSlaveImage(imageStream)) {
                            // Since the user could have change the immutable image that a PodTemplate uses, we just
                            // recreate the PodTemplate altogether. This makes it so that any changes from within
                            // Jenkins is undone.
                            OpenShiftUtils.removePodTemplate(trackedImageStreams.get(imageStream.getMetadata().getUid()));
                            PodTemplate podTemplate = podTemplateFromImageStream(imageStream);
                            OpenShiftUtils.addPodTemplate(podTemplate);
                            trackedImageStreams.put(imageStream.getMetadata().getUid(),podTemplate);
                        } else {
                            // The user modified the imageStream to no longer be a jenkins-slave.
                            OpenShiftUtils.removePodTemplate(trackedImageStreams.get(imageStream.getMetadata().getUid()));
                            trackedImageStreams.remove(imageStream.getMetadata().getUid());
                        }
                    } else {
                        if(isSlaveImage(imageStream)) {
                            // The user modified the imageStream to be a jenkins-slave
                            PodTemplate podTemplate = podTemplateFromImageStream(imageStream);
                            trackedImageStreams.put(imageStream.getMetadata().getUid(), podTemplate);
                            OpenShiftUtils.addPodTemplate(podTemplate);
                        }
                    }
                    break;

                case DELETED:
                    if(trackedImageStreams.containsKey(imageStream.getMetadata().getUid())) {
                        OpenShiftUtils.removePodTemplate(trackedImageStreams.get(imageStream.getMetadata().getUid()));
                        trackedImageStreams.remove(imageStream.getMetadata().getUid());
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

        for(Map.Entry<String,Watch> entry:imageStreamWatches.entrySet()) {
            entry.getValue().close();
            imageStreamWatches.remove(entry.getKey());
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

    private synchronized void onInitialImageStream(ImageStreamList imageStreams) {
        if(trackedImageStreams == null) {
            trackedImageStreams = new ConcurrentHashMap<>(imageStreams.getItems().size());
        }
        List<ImageStream> items = imageStreams.getItems();
        if (items != null) {
            for (ImageStream imageStream : items) {
                try {
                    if(isSlaveImage(imageStream) && !trackedImageStreams.containsKey(imageStream.getMetadata().getUid())) {
                        PodTemplate podTemplate = podTemplateFromImageStream(imageStream);
                        trackedImageStreams.put(imageStream.getMetadata().getUid(), podTemplate);
                        OpenShiftUtils.addPodTemplate(podTemplate);
                    }
                } catch (Exception e) {
                    logger.log(SEVERE, "Failed to update job", e);
                }
            }
        }
    }

    private boolean isSlaveImage(ImageStream imageStream) {
        if(imageStream.getMetadata().getLabels() != null) {
            return imageStream.getMetadata().getLabels().containsKey("role") && imageStream.getMetadata().getLabels().get("role").equals("jenkins-slave");
        }

        return false;
    }

    public PodTemplate podTemplateFromImageStream(ImageStream imageStream) {
        PodTemplate result = new PodTemplate(imageStream.getStatus().getDockerImageRepository(), new ArrayList<PodVolumes.PodVolume>());

        if(imageStream.getMetadata().getAnnotations().containsKey("slave-label")) {
            result.setLabel(imageStream.getMetadata().getAnnotations().get("slave-label"));
        } else {
            result.setLabel(imageStream.getMetadata().getName());
        }

        result.setName(imageStream.getMetadata().getName());
        result.setAlwaysPullImage(false);
        result.setInstanceCap(Integer.MAX_VALUE);
        result.setCommand("");

        return result;
    }
}