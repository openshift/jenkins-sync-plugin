package io.fabric8.jenkins.openshiftsync;

import com.thoughtworks.xstream.XStreamException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.triggers.SafeTimerTask;
import hudson.util.XStream2;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.internal.KubernetesDeserializer;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.apache.commons.jelly.parser.XMLParser;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;
import org.csanchez.jenkins.plugins.kubernetes.PodVolumes;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.StringReader;
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

public class ConfigMapWatcher implements Watcher<ConfigMap> {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final String[] namespaces;
    private Map<String,Watch> configMapWatches;
    private Map<String, List<PodTemplate>> trackedConfigMaps;

    private ScheduledFuture relister;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ConfigMapWatcher(String[] namespaces) {
        this.namespaces = namespaces;
        this.configMapWatches =new HashMap<String,Watch>();
        this.trackedConfigMaps = new ConcurrentHashMap<>();
    }

    public synchronized void start() {
        // lets process the initial state
        logger.info("Now handling startup config maps!!");
        // lets do this in a background thread to avoid errors like:
        //  Tried proxying io.fabric8.jenkins.openshiftsync.GlobalPluginConfiguration to support a circular dependency, but it is not an interface.
        Runnable task = new SafeTimerTask() {
            @Override
            public void doRun() {
                for(String namespace:namespaces) {
                    try {
                        logger.fine("listing ConfigMap resources");
                        final ConfigMapList configMaps = getAuthenticatedOpenShiftClient().configMaps().inNamespace(namespace).list();
                        onInitialConfigMaps(configMaps);
                        logger.fine("handled ConfigMap resources");
                        if (configMapWatches.get(namespace) == null) {
                            configMapWatches.put(namespace,getAuthenticatedOpenShiftClient().configMaps().inNamespace(namespace).withResourceVersion(configMaps.getMetadata().getResourceVersion()).watch(ConfigMapWatcher.this));
                        }
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to load ConfigMaps: " + e, e);
                    }
                }
            }
        };
        relister = Timer.get().scheduleAtFixedRate(task, 100, 10 * 1000, TimeUnit.MILLISECONDS);
    }

    @Override
    public void eventReceived(Action action, ConfigMap configMap) {
        try {
            switch (action) {
                case ADDED:
                    if(containsSlave(configMap)){
                        List<PodTemplate> templates = podTemplatesFromConfigMap(configMap);
                        trackedConfigMaps.put(configMap.getMetadata().getUid(), templates);
                        for( PodTemplate podTemplate : podTemplatesFromConfigMap(configMap)) {
                            OpenShiftUtils.addPodTemplate(podTemplate);
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
                                OpenShiftUtils.removePodTemplate(podTemplate);
                            }

                            for( PodTemplate podTemplate : podTemplatesFromConfigMap(configMap)) {
                                OpenShiftUtils.addPodTemplate(podTemplate);
                            }
                        } else {
                            // The user modified the configMap to no longer be a jenkins-slave.
                            for( PodTemplate podTemplate : trackedConfigMaps.get(configMap.getMetadata().getUid())) {
                                OpenShiftUtils.removePodTemplate(podTemplate);
                            }

                            trackedConfigMaps.remove(configMap.getMetadata().getUid());
                        }
                    } else {
                        if(containsSlave(configMap)) {
                            // The user modified the configMap to be a jenkins-slave

                            List<PodTemplate> templates = podTemplatesFromConfigMap(configMap);
                            trackedConfigMaps.put(configMap.getMetadata().getUid(), templates);
                            for( PodTemplate podTemplate : podTemplatesFromConfigMap(configMap)) {
                                OpenShiftUtils.addPodTemplate(podTemplate);
                            }
                        }
                    }
                    break;

                case DELETED:
                    if(trackedConfigMaps.containsKey(configMap.getMetadata().getUid())) {
                        for(PodTemplate podTemplate : trackedConfigMaps.get(configMap.getMetadata().getUid())) {
                            OpenShiftUtils.removePodTemplate(podTemplate);
                        }
                        trackedConfigMaps.remove(configMap.getMetadata().getUid());
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

        for(Map.Entry<String,Watch> entry: configMapWatches.entrySet()) {
            entry.getValue().close();
            configMapWatches.remove(entry.getKey());
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
                        for( PodTemplate podTemplate : podTemplatesFromConfigMap(configMap)) {
                            OpenShiftUtils.addPodTemplate(podTemplate);
                        }
                    }
                } catch (Exception e) {
                    logger.log(SEVERE, "Failed to update ConfigMap PodTemplates", e);
                }
            }
        }
    }

    private boolean containsSlave(ConfigMap configMap) {
        if(configMap.getMetadata().getLabels() != null) {
            return configMap.getMetadata().getLabels().containsKey("role") && configMap.getMetadata().getLabels().get("role").equals("jenkins-slave");
        }

        return false;
    }

    // podTemplatesFromConfigMap takes every key from a ConfigMap and tries to create a PodTemplate from the contained
    // XML.
    public List<PodTemplate> podTemplatesFromConfigMap(ConfigMap configMap) {
        List<PodTemplate> results = new ArrayList<>();
        Map<String, String> data = configMap.getData();

        XStream2 xStream2 = new XStream2();

        for(String key : data.keySet()) {
            Object podTemplate;
            try {
                podTemplate = xStream2.fromXML(data.get(key));

                if( podTemplate instanceof PodTemplate ) {
                    results.add((PodTemplate) podTemplate);
                } else {
                    logger.warning("Content of key '" + key + "' in ConfigMap '" + configMap.getMetadata().getName() + "' is not a PodTemplate");
                }
            } catch (XStreamException xse) {
                logger.warning(new IOException("Unable to read key '" + key + "' from ConfigMap '" + configMap.getMetadata().getName() + "'", xse).getMessage());
            } catch (Error e) {
                logger.warning(new IOException("Unable to read key '" + key + "' from ConfigMap '" + configMap.getMetadata().getName() + "'", e).getMessage());
            }
        }

        return results;
    }
}