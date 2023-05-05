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

import static io.fabric8.jenkins.openshiftsync.Constants.IMAGESTREAM_AGENT_LABEL;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.CONFIGMAP;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import io.fabric8.openshift.client.OpenShiftClient;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;

public class ConfigMapInformer implements ResourceEventHandler<ConfigMap>, Lifecyclable,Resyncable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecretInformer.class.getName());
    private SharedIndexInformer<ConfigMap> informer;
    private String namespace;

    public ConfigMapInformer(String namespace) {
        this.namespace = namespace;
    }

    @Override
    public long getResyncPeriodMilliseconds() {
        return 1_000 * GlobalPluginConfiguration.get().getConfigMapListInterval();
    }

    public void start() {
        LOGGER.info("Starting ConfigMap informer for namespace: " + namespace + "!!");
        OpenShiftClient client = getOpenShiftClient();
        this.informer = client.configMaps().inNamespace(namespace).withLabelIn(IMAGESTREAM_AGENT_LABEL, Constants.imageStreamAgentLabelValues()).inform();
        informer.addEventHandler(this);
        client.informers().startAllRegisteredInformers();
        LOGGER.info("ConfigMap informer started for namespace: " + namespace);
    }

    public void stop() {
      LOGGER.info("Stopping informer " + namespace + "!!");
      if( this.informer != null ) {
        this.informer.stop();
      }
    }


    @Override
    public void onAdd(ConfigMap obj) {
        LOGGER.debug("ConfigMap informer  received add event for: " + obj);
        if (obj != null) {
            ObjectMeta metadata = obj.getMetadata();
            String name = metadata.getName();
            LOGGER.info("ConfigMap informer received add event for: " + name);
            List<PodTemplate> podTemplates = PodTemplateUtils.podTemplatesFromConfigMap(obj);
            String uid = metadata.getUid();
            String namespace = metadata.getNamespace();
            PodTemplateUtils.addAgents(podTemplates, CONFIGMAP, uid, name, namespace);
        }
    }

    @Override
    public void onUpdate(ConfigMap oldObj, ConfigMap newObj) {
        LOGGER.debug("ConfigMap informer  received update event for: " + oldObj + " to: " + newObj);
        if (oldObj != null) {
            String oldResourceVersion = oldObj.getMetadata() != null ? oldObj.getMetadata().getResourceVersion() : null;
            String newResourceVersion = newObj.getMetadata() != null ? newObj.getMetadata().getResourceVersion() : null;
            // fabric8 will call onUpdate on re-list even if nothing changed.
            // This is to prevent constantly purging pod templates when there are no changes
            if (!Objects.equals(oldResourceVersion, newResourceVersion)) {
                LOGGER.info("Update event received resource versions: " + oldResourceVersion + " to: " + newResourceVersion);
                List<PodTemplate> podTemplates = PodTemplateUtils.podTemplatesFromConfigMap(newObj);
                ObjectMeta metadata = newObj.getMetadata();
                String uid = metadata.getUid();
                String name = metadata.getName();
                String namespace = metadata.getNamespace();
                LOGGER.info("ConfigMap informer received update event for: {}", name);
                PodTemplateUtils.updateAgents(podTemplates, CONFIGMAP, uid, name, namespace);
            }
        }
    }

    @Override
    public void onDelete(ConfigMap obj, boolean deletedFinalStateUnknown) {
        LOGGER.debug("ConfigMap informer received delete event for: " + obj);
        if (obj != null) {
            List<PodTemplate> podTemplates = PodTemplateUtils.podTemplatesFromConfigMap(obj);
            ObjectMeta metadata = obj.getMetadata();
            String uid = metadata.getUid();
            String name = metadata.getName();
            String namespace = metadata.getNamespace();
            PodTemplateUtils.deleteAgents(podTemplates, CONFIGMAP, uid, name, namespace);
        }
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
