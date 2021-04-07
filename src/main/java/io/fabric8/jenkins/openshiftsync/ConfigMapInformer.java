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
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.addPodTemplate;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.configMapContainsSlave;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.podTemplatesFromConfigMap;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.processSlavesForAddEvent;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.processSlavesForDeleteEvent;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.processSlavesForModifyEvent;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.trackedPodTemplates;
import static java.util.logging.Level.SEVERE;

import java.util.List;

import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.kubernetes.client.informers.cache.Lister;
import io.fabric8.openshift.client.OpenShiftClient;

public class ConfigMapInformer extends ConfigMapWatcher implements ResourceEventHandler<ConfigMap> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigMapWatcher.class);

    private static final long RESYNC_PERIOD = 30 * 1000L;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ConfigMapInformer(String namespace) {
        super(namespace);
    }

    @Override
    public int getListIntervalInSeconds() {
        return GlobalPluginConfiguration.get().getConfigMapListInterval();
    }

    public void start() {
        LOGGER.info("Now handling startup config maps for {} !!", namespace);
        LOGGER.trace("listing ConfigMap resources");
        OpenShiftClient client = getAuthenticatedOpenShiftClient();
        SharedInformerFactory informerFactory = client.informers();
        SharedIndexInformer<ConfigMap> informer = informerFactory.inNamespace(namespace)
                .sharedIndexInformerFor(ConfigMap.class, RESYNC_PERIOD);
        informer.addEventHandler(this);
        LOGGER.info("ConfigMap informer started for namespace: {}", namespace);
        Lister<ConfigMap> list = new Lister<>(informer.getIndexer(), namespace);
        onInit(list.list());
        informerFactory.startAllRegisteredInformers();
    }

    @Override
    public void onAdd(ConfigMap obj) {
        LOGGER.info("ConfigMap informer  received add event for: {}", obj);
        List<PodTemplate> slavesFromCM = PodTemplateUtils.podTemplatesFromConfigMap(this, obj);
        boolean hasSlaves = slavesFromCM.size() > 0;
        if (hasSlaves) {
            ObjectMeta metadata = obj.getMetadata();
            String uid = metadata.getUid();
            String cmname = metadata.getName();
            String namespace = metadata.getNamespace();
            processSlavesForAddEvent(this, slavesFromCM, PodTemplateUtils.cmType, uid, cmname, namespace);
        }
    }

    @Override
    public void onUpdate(ConfigMap oldObj, ConfigMap newObj) {
        LOGGER.info("ConfigMap informer  received update event for: {} to: {}", oldObj, newObj);
//        List<PodTemplate> slavesFromCM = PodTemplateUtils.podTemplatesFromConfigMap(this, newObj);
//        ObjectMeta metadata = newObj.getMetadata();
//        String uid = metadata.getUid();
//        String cmname = metadata.getName();
//        String namespace = metadata.getNamespace();
//        processSlavesForModifyEvent(this, slavesFromCM, PodTemplateUtils.cmType, uid, cmname, namespace);
    }

    @Override
    public void onDelete(ConfigMap obj, boolean deletedFinalStateUnknown) {
        LOGGER.info("ConfigMap informer received delete event for: {}", obj);
        List<PodTemplate> slavesFromCM = PodTemplateUtils.podTemplatesFromConfigMap(this, obj);
        ObjectMeta metadata = obj.getMetadata();
        String uid = metadata.getUid();
        String cmname = metadata.getName();
        String namespace = metadata.getNamespace();
        processSlavesForDeleteEvent(this, slavesFromCM, PodTemplateUtils.cmType, uid, cmname, namespace);
    }

    private void onInit(List<ConfigMap> list) {
        if (list != null) {
            for (ConfigMap configMap : list) {
                addPodTemplateFromConfigMap(configMap);
            }
        }
    }

    private void addPodTemplateFromConfigMap(ConfigMap configMap) {
        try {
            String uid = configMap.getMetadata().getUid();
            if (configMapContainsSlave(configMap) && !trackedPodTemplates.containsKey(uid)) {
                List<PodTemplate> templates = podTemplatesFromConfigMap(this, configMap);
                trackedPodTemplates.put(uid, templates);
                for (PodTemplate podTemplate : templates) {
                    LOGGER.info("Adding PodTemplate {}", podTemplate);
                    addPodTemplate(podTemplate);
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to update ConfigMap PodTemplates", e);
        }
    }

}
