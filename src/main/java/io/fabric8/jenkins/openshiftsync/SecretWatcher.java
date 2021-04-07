/**
 * Copyright (C) 2016 Red Hat, Inc.
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
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenshiftClient;
import static java.util.logging.Level.SEVERE;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.openshift.client.OpenShiftClient;

/**
 * Watches {@link Secret} objects in Kubernetes and syncs then to Credentials in
 * Jenkins
 */
public class SecretWatcher extends BaseWatcher<Secret> {
    private ConcurrentHashMap<String, String> trackedSecrets;
    private final Logger logger = Logger.getLogger(getClass().getName());

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public SecretWatcher(String namespace) {
        super(namespace);
        this.trackedSecrets = new ConcurrentHashMap<>();
    }

    @Override
    public int getListIntervalInSeconds() {
        return GlobalPluginConfiguration.get().getSecretListInterval();
    }

    public void start() {
        // lets process the initial state
        // super.start();
        logger.info("Now handling startup secrets for " + namespace + " !!");
        SecretList secrets = null;
        String ns = this.namespace;
        try {
            logger.fine("listing Secrets resources");
            secrets = getAuthenticatedOpenShiftClient().secrets().inNamespace(ns)
                    .withLabel(Constants.OPENSHIFT_LABELS_SECRET_CREDENTIAL_SYNC, Constants.VALUE_SECRET_SYNC).list();
            onInitialSecrets(secrets);
            logger.fine("handled Secrets resources");
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to load Secrets: " + e, e);
        }
        try {
            String rv = "0";
            if (secrets == null) {
                logger.warning("Unable to get secret list; impacts resource version used for watch");
            } else {
                rv = secrets.getMetadata().getResourceVersion();
            }

            if (this.watch == null) {
                synchronized (this.lock) {
                    if (this.watch == null) {
                        logger.info("creating Secret watch for namespace " + ns + " and resource version" + rv);
                        OpenShiftClient client = getOpenshiftClient();
                        this.watch = client.secrets().inNamespace(ns)
                                .withLabel(OPENSHIFT_LABELS_SECRET_CREDENTIAL_SYNC, VALUE_SECRET_SYNC)
                                .withResourceVersion(rv).watch(this);
                    }
                }
            }
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to load Secrets: " + e, e);
        }
    }

    private void onInitialSecrets(SecretList secrets) {
        if (secrets == null)
            return;
        if (trackedSecrets == null)
            trackedSecrets = new ConcurrentHashMap<String, String>();
        List<Secret> items = secrets.getItems();
        if (items != null) {
            for (Secret secret : items) {
                try {
                    if (validSecret(secret) && shouldProcessSecret(secret)) {
                        upsertCredential(secret);
                        trackedSecrets.put(secret.getMetadata().getUid(), secret.getMetadata().getResourceVersion());
                    }
                } catch (Exception e) {
                    logger.log(SEVERE, "Failed to update job", e);
                }
            }
        }
    }

    @SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
    @Override
    public void eventReceived(Action action, Secret secret) {
        if (secret == null) {
            logger.warning("Received  event with null Secret: " + action + ", ignoring: " + this);
            return;
        }
        try {
            switch (action) {
            case ADDED:
                upsertCredential(secret);
                break;
            case DELETED:
                deleteCredential(secret);
                break;
            case MODIFIED:
                modifyCredential(secret);
                break;
            case ERROR:
                logger.warning("watch for secret " + secret.getMetadata().getName() + " received error event ");
                break;
            default:
                logger.warning(
                        "watch for secret " + secret.getMetadata().getName() + " received unknown event " + action);
                break;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Caught: " + e, e);
        }
    }

    private void upsertCredential(final Secret secret) throws Exception {
        if (secret != null) {
            ObjectMeta metadata = secret.getMetadata();
            if (metadata != null) {
                logger.info("Upserting Secret with Uid " + metadata.getUid() + " with Name " + metadata.getName());
                if (validSecret(secret)) {
                    CredentialsUtils.upsertCredential(secret);
                    trackedSecrets.put(metadata.getUid(), metadata.getResourceVersion());
                }
            }
        }
    }

    private void modifyCredential(Secret secret) throws Exception {
        if (secret != null) {
            ObjectMeta metadata = secret.getMetadata();
            if (metadata != null) {
                logger.info("Modifying Secret with Uid " + metadata.getUid() + " with Name " + metadata.getName());
                if (validSecret(secret) && shouldProcessSecret(secret)) {
                    CredentialsUtils.upsertCredential(secret);
                    trackedSecrets.put(metadata.getUid(), metadata.getResourceVersion());
                }
            }
        }
    }

    private boolean validSecret(Secret secret) {
        if (secret != null) {
            ObjectMeta metadata = secret.getMetadata();
            if (metadata != null) {
                String name = metadata.getName();
                String namespace = metadata.getNamespace();
                logger.info("Validating Secret with Uid " + metadata.getUid() + " with Name " + name);
                return name != null && !name.isEmpty() && namespace != null && !namespace.isEmpty();
            }
        }
        return false;
    }

    private boolean shouldProcessSecret(Secret secret) {
        if (secret != null) {
            ObjectMeta metadata = secret.getMetadata();
            if (metadata != null) {
                String uid = metadata.getUid();
                String rv = metadata.getResourceVersion();
                String savedRV = trackedSecrets.get(uid);
                if (savedRV == null || !savedRV.equals(rv)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void deleteCredential(final Secret secret) throws Exception {
        if (secret != null) {
            ObjectMeta metadata = secret.getMetadata();
            if (metadata != null) {
                trackedSecrets.remove(metadata.getUid());
                CredentialsUtils.deleteCredential(secret);
            }
        }
    }
}
