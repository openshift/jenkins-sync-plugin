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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import jenkins.util.Timer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static java.net.HttpURLConnection.HTTP_GONE;
import static java.util.logging.Level.SEVERE;

/**
 * Watches {@link Secret} objects in Kubernetes and syncs then to Credentials in
 * Jenkins
 */
public class SecretWatcher implements Watcher<Secret> {
	private static final String LABEL_JENKINS = "jenkins";
	private static final String VALUE_SYNC = "sync";

	private final Logger logger = Logger.getLogger(getClass().getName());
	private final String[] namespaces;
	private Watch secretWatch;
	private ScheduledFuture relister;

	public SecretWatcher(String[] namespaces) {
		this.namespaces = namespaces;
	}

	public synchronized void start() {
		// lets process the initial state
		logger.info("Now handling startup secrets!!");
		// lets do this in a background thread to avoid errors like:
		// Tried proxying io.fabric8.jenkins.openshiftsync.GlobalPluginConfiguration to
		// support a circular dependency, but it is not an interface.
		Runnable task = new SafeTimerTask() {
			@Override
			public void doRun() {
				for (String namespace : namespaces) {
					try {
						logger.fine("listing Secrets resources");
						final SecretList secrets = getAuthenticatedOpenShiftClient().secrets().inNamespace(namespace)
								.withLabel(LABEL_JENKINS, VALUE_SYNC).list();
						onInitialSecrets(secrets);
						logger.fine("handled Secrets resources");
						if (secretWatch == null) {
							secretWatch = getOpenShiftClient().secrets().inNamespace(namespace)
									.withLabel(LABEL_JENKINS, VALUE_SYNC)
									.withResourceVersion(secrets.getMetadata().getResourceVersion())
									.watch(SecretWatcher.this);
						}
					} catch (Exception e) {
						logger.log(SEVERE, "Failed to load Secrets: " + e, e);
					}
				}

			}
		};
		relister = Timer.get().scheduleAtFixedRate(task, 100, 10 * 1000, TimeUnit.MILLISECONDS);
	}

	public synchronized void stop() {
		if (relister != null && !relister.isDone()) {
			relister.cancel(true);
			relister = null;
		}
		if (secretWatch != null) {
			secretWatch.close();
			secretWatch = null;
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

	private synchronized void onInitialSecrets(SecretList secrets) {
		List<Secret> items = secrets.getItems();
		if (items != null) {
			for (Secret secret : items) {
				try {
					upsertCredential(secret);
				} catch (Exception e) {
					logger.log(SEVERE, "Failed to update job", e);
				}
			}
		}
	}

	@SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
	@Override
	public synchronized void eventReceived(Action action, Secret secret) {
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
			}
		} catch (Exception e) {
			logger.log(Level.WARNING, "Caught: " + e, e);
		}
	}

	private void upsertCredential(final Secret secret) throws Exception {
		if (isJenkinsSecret(secret)) {
			CredentialsUtils.upsertCredential(secret);
		}
	}

	private void modifyCredential(Secret secret) throws Exception {
		if (isJenkinsSecret(secret)) {
			upsertCredential(secret);
			return;
		}

		// no longer a Jenkins build so lets delete it if it exists
		deleteCredential(secret);
	}

	private static boolean isJenkinsSecret(Secret secret) {
		ObjectMeta metadata = secret.getMetadata();
		if (metadata != null) {
			String name = metadata.getName();
			return name != null && !name.isEmpty();
		}
		return false;
	}

	private void deleteCredential(final Secret secret) throws Exception {
		// TODO should we delete credentials?
		// if we do then we should update BuildConfigWatcher to do the same
	}
}
