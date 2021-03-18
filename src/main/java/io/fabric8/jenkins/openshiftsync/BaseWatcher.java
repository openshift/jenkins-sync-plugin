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

import static java.net.HttpURLConnection.HTTP_GONE;

import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.WatcherException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jenkins.util.Timer;

public abstract class BaseWatcher {
    private final Logger LOGGER = Logger.getLogger(BaseWatcher.class.getName());

    protected ScheduledFuture relister;
    protected final String[] namespaces;
    protected ConcurrentHashMap<String, Watch> watches;
    private final String PT_NAME_CLAIMED = "The event for %s | %s | %s that attempts to add the pod template %s was ignored because a %s previously created a pod template with the same name";
    private final String PT_NOT_OWNED = "The event for %s | %s | %s that no longer includes the pod template %s was ignored because the type %s was associated with that pod template";

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public BaseWatcher(String[] namespaces) {
        this.namespaces = namespaces;
        watches = new ConcurrentHashMap<>();
    }

    public abstract Runnable getStartTimerTask();

    public abstract int getListIntervalInSeconds();

    public abstract <T> void eventReceived(io.fabric8.kubernetes.client.Watcher.Action action, T resource);

    public synchronized void start() {
        // lets do this in a background thread to avoid errors like:
        // Tried proxying
        // io.fabric8.jenkins.openshiftsync.GlobalPluginConfiguration to support
        // a circular dependency, but it is not an interface.
        Runnable task = getStartTimerTask();
        relister = Timer.get().scheduleAtFixedRate(task, 100, // still do the
                                                              // first run 100
                                                              // milliseconds in
                getListIntervalInSeconds() * 1000,
                TimeUnit.MILLISECONDS);

    }

    public void stop() {
        if (relister != null && !relister.isDone()) {
            relister.cancel(true);
            relister = null;
        }

        for (Map.Entry<String, Watch> entry : watches.entrySet()) {
            entry.getValue().close();
            watches.remove(entry.getKey());
        }
    }

    public void onClose(WatcherException e, String namespace) {
        //scans of fabric client confirm this call be called with null
        //we do not want to totally ignore this, as the closing of the
        //watch can effect responsiveness
        LOGGER.info("Watch for type " + this.getClass().getName() + " closed for one of the following namespaces: " + watches.keySet().toString());
        if (e != null) {
            LOGGER.warning(e.toString());

            if (e.isHttpGone()) {
                stop();
                start();
            }
        }
        // clearing the watches here will signal the extending classes
        // to attempt to re-establish the watch the next time they attempt
        // to list; should shield from rapid/repeated close/reopen cycles
        // doing it in this fashion
        watches.remove(namespace);
    }

    public void addWatch(String key, Watch desiredWatch) {
        Watch watch = watches.putIfAbsent(key, desiredWatch);
        if (watch != null) {
          watch.close();
        }
    }

    protected void processSlavesForAddEvent(List<PodTemplate> slaves, String type, String uid, String apiObjName, String namespace) {
      LOGGER.info("Adding PodTemplate(s) for ");
      List<PodTemplate> finalSlaveList = new ArrayList<PodTemplate>();
        for (PodTemplate podTemplate : slaves) {
          PodTemplateUtils.addPodTemplate(this, type, apiObjName, namespace, finalSlaveList, podTemplate);
        }
        PodTemplateUtils.updateTrackedPodTemplatesMap(uid, finalSlaveList);
    }

    protected void processSlavesForModifyEvent(List<PodTemplate> slaves, String type, String uid, String apiObjName, String namespace) {
        LOGGER.info("Modifying PodTemplates");
        boolean alreadyTracked = PodTemplateUtils.trackedPodTemplates.containsKey(uid);
        boolean hasSlaves = slaves.size() > 0; // Configmap has podTemplates
        if (alreadyTracked) {
            if (hasSlaves) {
                // Since the user could have change the immutable image
                // that a PodTemplate uses, we just
                // recreate the PodTemplate altogether. This makes it so
                // that any changes from within
                // Jenkins is undone.

                // Check if there are new PodTemplates added or removed to the configmap,
                // if they are, add them to or remove them from trackedPodTemplates
                List<PodTemplate> podTemplatesToTrack = new ArrayList<PodTemplate>();
                PodTemplateUtils.purgeTemplates(this, type, uid, apiObjName, namespace);
                for(PodTemplate pt: slaves){
                    podTemplatesToTrack = PodTemplateUtils.onlyTrackPodTemplate(this, type,apiObjName,namespace,podTemplatesToTrack, pt);
                }
                PodTemplateUtils.updateTrackedPodTemplatesMap(uid, podTemplatesToTrack);
                for (PodTemplate podTemplate : podTemplatesToTrack) {
                      // still do put here in case this is a new item from the last
                      // update on this ConfigMap/ImageStream
                      PodTemplateUtils.addPodTemplate(this, type,null,null,null, podTemplate);
                }
            } else {
                // The user modified the configMap to no longer be a
                // jenkins-slave.
                PodTemplateUtils.purgeTemplates(this, type, uid, apiObjName, namespace);
            }
        } else {
            if (hasSlaves) {
                List<PodTemplate> finalSlaveList = new ArrayList<PodTemplate>();
                for (PodTemplate podTemplate : slaves) {
                  // The user modified the api obj to be a jenkins-slave
                  PodTemplateUtils.addPodTemplate(this, type, apiObjName, namespace, finalSlaveList, podTemplate);
                }
                PodTemplateUtils.updateTrackedPodTemplatesMap(uid, finalSlaveList);
            }
        }
    }

    protected void processSlavesForDeleteEvent(List<PodTemplate> slaves, String type, String uid, String apiObjName, String namespace) {
        if (PodTemplateUtils.trackedPodTemplates.containsKey(uid)) {
            PodTemplateUtils.purgeTemplates(this, type, uid, apiObjName, namespace);
        }
    }
}
