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

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getInformerFactory;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenshiftClient;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.CONFIGMAP;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;

public class ConfigMapInformer extends ConfigMapWatcher implements ResourceEventHandler<ConfigMap> {
    private final static Logger LOGGER = Logger.getLogger(ConfigMapWatcher.class.getName());

    private static final long RESYNC_PERIOD = 30 * 1000L;
    private SharedIndexInformer<ConfigMap> informer;

    public ConfigMapInformer(String namespace) {
        super(namespace);
    }

    @Override
    public int getListIntervalInSeconds() {
        return GlobalPluginConfiguration.get().getConfigMapListInterval();
    }

    public void start() {
        LOGGER.info("Starting configMap informer for {} !!" + namespace);
        LOGGER.fine("listing ConfigMap resources");
        SharedInformerFactory factory = getInformerFactory().inNamespace(namespace);
        this.informer = factory.sharedIndexInformerFor(ConfigMap.class, RESYNC_PERIOD);
        informer.addEventHandler(this);
        factory.startAllRegisteredInformers();
        LOGGER.info("ConfigMap informer started for namespace: {}" + namespace);
        ConfigMapList list = getOpenshiftClient().configMaps().inNamespace(namespace).list();
        onInit(list.getItems());
    }

    public void stop() {
        LOGGER.info("Stopping configMap informer {} !!" + namespace);
        this.informer.stop();
    }

    @Override
    public void onAdd(ConfigMap obj) {
        LOGGER.fine("ConfigMap informer  received add event for: {}" + obj);
        ObjectMeta metadata = obj.getMetadata();
        String name = metadata.getName();
        LOGGER.info("ConfigMap informer received add event for: {}" + name);
        List<PodTemplate> podTemplates = PodTemplateUtils.podTemplatesFromConfigMap(obj);
        String uid = metadata.getUid();
        String namespace = metadata.getNamespace();
        PodTemplateUtils.addAgents(podTemplates, CONFIGMAP, uid, name, namespace);
    }

    @Override
    public void onUpdate(ConfigMap oldObj, ConfigMap newObj) {
        LOGGER.fine("ConfigMap informer  received update event for: {} to: {}" + oldObj + newObj);
        String oldResourceVersion = oldObj.getMetadata() != null ? oldObj.getMetadata().getResourceVersion() : null;
        String newResourceVersion = newObj.getMetadata() != null ? newObj.getMetadata().getResourceVersion() : null;
        LOGGER.info("Update event received resource versions: {} to: {}" + oldResourceVersion + newResourceVersion);
        List<PodTemplate> podTemplates = PodTemplateUtils.podTemplatesFromConfigMap(newObj);
        ObjectMeta metadata = newObj.getMetadata();
        String uid = metadata.getUid();
        String name = metadata.getName();
        String namespace = metadata.getNamespace();
        PodTemplateUtils.updateAgents(podTemplates, CONFIGMAP, uid, name, namespace);
    }

    @Override
    public void onDelete(ConfigMap obj, boolean deletedFinalStateUnknown) {
        LOGGER.fine("ConfigMap informer received delete event for: {}" + obj);
        List<PodTemplate> podTemplates = PodTemplateUtils.podTemplatesFromConfigMap(obj);
        ObjectMeta metadata = obj.getMetadata();
        String uid = metadata.getUid();
        String name = metadata.getName();
        String namespace = metadata.getNamespace();
        PodTemplateUtils.deleteAgents(podTemplates, CONFIGMAP, uid, name, namespace);
    }

    private void onInit(List<ConfigMap> list) {
        if (list != null) {
            for (ConfigMap configMap : list) {
                PodTemplateUtils.addPodTemplateFromConfigMap(configMap);
            }
        }
    }

    private void waitInformerSync(SharedIndexInformer<ConfigMap> informer) {
        while (!informer.hasSynced()) {
            LOGGER.info("Waiting informer to sync for " + namespace);
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (InterruptedException e) {
                LOGGER.info("Interrupted waiting thread: " + e);
            }
        }
    }
}
