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

import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_LABELS_SECRET_CREDENTIAL_SYNC;
import static io.fabric8.jenkins.openshiftsync.Constants.VALUE_SECRET_SYNC;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getInformerFactory;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static java.util.Collections.singletonMap;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.fabric8.openshift.client.OpenShiftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
//import io.fabric8.kubernetes.client.dsl.base.OperationContext;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;

public class SecretInformer implements ResourceEventHandler<Secret>, Lifecyclable, Resyncable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecretInformer.class.getName());

    private final static ConcurrentHashMap<String, String> trackedSecrets = new ConcurrentHashMap<String, String>();
    private String namespace;
    private SharedIndexInformer<Secret> informer;

    public SecretInformer(String namespace) {
        this.namespace = namespace;
    }

    @Override
    public long getResyncPeriodMilliseconds() {
        return 1_000 * GlobalPluginConfiguration.get().getSecretListInterval();
    }

    public void start() {
        LOGGER.info("Starting Secret informer " + namespace + "!!");
        OpenShiftClient client = getOpenShiftClient();
        Map<String, String> labels = singletonMap(OPENSHIFT_LABELS_SECRET_CREDENTIAL_SYNC, VALUE_SECRET_SYNC);
        this.informer = client.secrets().inNamespace(namespace).withLabels(labels).inform();
        informer.addEventHandler(this);
        client.informers().startAllRegisteredInformers();
        LOGGER.info("Secret informer started for namespace: " + namespace);
    }

    public void stop() {
      LOGGER.info("Stopping informer " + namespace + "!!");
      if( this.informer != null ) {
        this.informer.stop();
      }
    }


    @Override
    public void onAdd(Secret obj) {
        LOGGER.debug("Secret informer received add event for: " + obj);
        if (obj != null) {
            ObjectMeta metadata = obj.getMetadata();
            String name = metadata.getName();
            LOGGER.info("Secret informer received add event for: " + name);
            SecretManager.insertOrUpdateCredentialFromSecret(obj);
        }
    }

    @Override
    public void onUpdate(Secret oldObj, Secret newObj) {
        LOGGER.debug("Secret informer received update event for: " + oldObj + " to: " + newObj);
        if (oldObj != null) {
            final String name = oldObj.getMetadata().getName();
            LOGGER.info("Secret informer received update event for: {}", name);
            SecretManager.updateCredential(newObj);
        }
    }

    @Override
    public void onDelete(Secret obj, boolean deletedFinalStateUnknown) {
        LOGGER.debug("Secret informer received delete event for: {}", obj);
        if (obj != null) {
            final String name = obj.getMetadata().getName();
            LOGGER.info("Secret informer received delete event for: {}", name);
            CredentialsUtils.deleteCredential(obj);
        }
    }

    private void onInit(List<Secret> list) {
        for (Secret secret : list) {
            try {
                if (SecretManager.validSecret(secret) && SecretManager.shouldProcessSecret(secret)) {
                    SecretManager.insertOrUpdateCredentialFromSecret(secret);
                    trackedSecrets.put(secret.getMetadata().getUid(), secret.getMetadata().getResourceVersion());
                }
            } catch (Exception e) {
                LOGGER.error("Failed to update secred", e);
            }
        }
    }

}
