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
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenshiftClient;
import static java.util.Collections.singletonMap;
import static java.util.logging.Level.SEVERE;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.client.dsl.base.OperationContext;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;

public class SecretInformer extends SecretWatcher implements ResourceEventHandler<Secret> {

    private final static Logger LOGGER = Logger.getLogger(SecretInformer.class.getName());
    private final static ConcurrentHashMap<String, String> trackedSecrets = new ConcurrentHashMap<String, String>();
    private static final long RESYNC_PERIOD = 30 * 1000L;

    private SharedIndexInformer<Secret> informer;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public SecretInformer(String namespace) {
        super(namespace);
    }

    @Override
    public int getListIntervalInSeconds() {
        return GlobalPluginConfiguration.get().getSecretListInterval();
    }

    public void start() {
        LOGGER.info("Starting secret informer {} !!" + namespace);
        LOGGER.fine("listing Secret resources");
        SharedInformerFactory factory = getInformerFactory().inNamespace(namespace);
        Map<String, String> labels = singletonMap(OPENSHIFT_LABELS_SECRET_CREDENTIAL_SYNC, VALUE_SECRET_SYNC);
        OperationContext withLabels = new OperationContext().withLabels(labels);
        this.informer = factory.sharedIndexInformerFor(Secret.class, withLabels, RESYNC_PERIOD);
        informer.addEventHandler(this);
        factory.startAllRegisteredInformers();
        LOGGER.info("Secret informer started for namespace: {}" + namespace);
        SecretList list = getOpenshiftClient().secrets().inNamespace(namespace).withLabels(labels).list();
        onInit(list.getItems());
    }

    public void stop() {
        LOGGER.info("Stopping secret informer {} !!" + namespace);
        this.informer.stop();
    }

    @Override
    public void onAdd(Secret obj) {
        LOGGER.fine("Secret informer  received add event for: {}" + obj);
        ObjectMeta metadata = obj.getMetadata();
        String name = metadata.getName();
        LOGGER.info("Secret informer received add event for: {}" + name);
        insertOrUpdateCredentialFromSecret(obj);
    }

    @Override
    public void onUpdate(Secret oldObj, Secret newObj) {
        LOGGER.info("Secret informer received update event for: {} to: {}" + oldObj + newObj);
        updateCredential(newObj);
    }

    @Override
    public void onDelete(Secret obj, boolean deletedFinalStateUnknown) {
        LOGGER.info("Secret informer received delete event for: {}" + obj);
        CredentialsUtils.deleteCredential(obj);
    }

    private void onInit(List<Secret> list) {
        for (Secret secret : list) {
            try {
                if (validSecret(secret) && shouldProcessSecret(secret)) {
                    insertOrUpdateCredentialFromSecret(secret);
                    trackedSecrets.put(secret.getMetadata().getUid(), secret.getMetadata().getResourceVersion());
                }
            } catch (Exception e) {
                LOGGER.log(SEVERE, "Failed to update secred", e);
            }
        }
    }

}
