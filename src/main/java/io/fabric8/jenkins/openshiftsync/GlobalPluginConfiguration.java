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

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.Util;
import hudson.init.InitMilestone;
import hudson.security.ACL;
import hudson.triggers.SafeTimerTask;
import hudson.util.ListBoxModel;
import io.fabric8.kubernetes.client.KubernetesClientException;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getNamespaceOrUseDefault;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;

@Extension
public class GlobalPluginConfiguration extends GlobalConfiguration {

	private static final Logger logger = Logger.getLogger(GlobalPluginConfiguration.class.getName());

	private boolean enabled = true;

	private String server;

	private String credentialsId = "";

	private String[] namespaces;

	private String jobNamePattern;

	private String skipOrganizationPrefix;

	private String skipBranchSuffix;

    private int buildListInterval = 300;
    private int buildConfigListInterval = 300;
    private int secretListInterval = 300;
    private int configMapListInterval = 300;
    private int imageStreamListInterval = 300;
    
	private transient BuildWatcher buildWatcher;

	private transient BuildConfigWatcher buildConfigWatcher;

	private transient SecretWatcher secretWatcher;

	private transient ConfigMapWatcher configMapWatcher;

	private transient ImageStreamWatcher imageStreamWatcher;

	@DataBoundConstructor
	public GlobalPluginConfiguration(boolean enable, String server, String namespace, String credentialsId,
			String jobNamePattern, String skipOrganizationPrefix, String skipBranchSuffix,
			int buildListInterval, int buildConfigListInterval, int configMapListInterval,
			int secretListInterval, int imageStreamListInterval) {
		this.enabled = enable;
		this.server = server;
		this.namespaces = StringUtils.isBlank(namespace) ? null : namespace.split(" ");
		this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
		this.jobNamePattern = jobNamePattern;
		this.skipOrganizationPrefix = skipOrganizationPrefix;
		this.skipBranchSuffix = skipBranchSuffix;
		this.buildListInterval = buildListInterval;
		this.buildConfigListInterval = buildConfigListInterval;
		this.configMapListInterval = configMapListInterval;
		this.secretListInterval = secretListInterval;
		this.imageStreamListInterval = imageStreamListInterval;
		configChange();
	}

	public GlobalPluginConfiguration() {
		load();
		configChange();
		//save();
	}

	public static GlobalPluginConfiguration get() {
		return GlobalConfiguration.all().get(GlobalPluginConfiguration.class);
	}

	@Override
	public String getDisplayName() {
		return "OpenShift Jenkins Sync";
	}

	@Override
	public boolean configure(StaplerRequest req, JSONObject json) throws hudson.model.Descriptor.FormException {
		req.bindJSON(this, json);
		configChange();
		save();
		return true;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getServer() {
		return server;
	}

	public void setServer(String server) {
		this.server = server;
	}

	// When Jenkins is reset, credentialsId is strangely set to null. However,
	// credentialsId has no reason to be null.
	public String getCredentialsId() {
		return credentialsId == null ? "" : credentialsId;
	}

	public void setCredentialsId(String credentialsId) {
		this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
	}

	public String getNamespace() {
		return namespaces == null ? "" : StringUtils.join(namespaces, " ");
	}

	public void setNamespace(String namespace) {
		this.namespaces = StringUtils.isBlank(namespace) ? null : namespace.split(" ");
	}

	public String getJobNamePattern() {
		return jobNamePattern;
	}

	public void setJobNamePattern(String jobNamePattern) {
		this.jobNamePattern = jobNamePattern;
	}

	public String getSkipOrganizationPrefix() {
		return skipOrganizationPrefix;
	}

	public void setSkipOrganizationPrefix(String skipOrganizationPrefix) {
		this.skipOrganizationPrefix = skipOrganizationPrefix;
	}

	public String getSkipBranchSuffix() {
		return skipBranchSuffix;
	}

	public void setSkipBranchSuffix(String skipBranchSuffix) {
		this.skipBranchSuffix = skipBranchSuffix;
	}

    public int getBuildListInterval() {
        return buildListInterval;
    }

    public void setBuildListInterval(int buildListInterval) {
        this.buildListInterval = buildListInterval;
    }

    public int getBuildConfigListInterval() {
        return buildConfigListInterval;
    }

    public void setBuildConfigListInterval(int buildConfigListInterval) {
        this.buildConfigListInterval = buildConfigListInterval;
    }

    public int getSecretListInterval() {
        return secretListInterval;
    }

    public void setSecretListInterval(int secretListInterval) {
        this.secretListInterval = secretListInterval;
    }

    public int getConfigMapListInterval() {
        return configMapListInterval;
    }

    public void setConfigMapListInterval(int configMapListInterval) {
        this.configMapListInterval = configMapListInterval;
    }

    public int getImageStreamListInterval() {
        return imageStreamListInterval;
    }

    public void setImageStreamListInterval(int imageStreamListInterval) {
        this.imageStreamListInterval = imageStreamListInterval;
    }

    // https://wiki.jenkins-ci.org/display/JENKINS/Credentials+Plugin
	// http://javadoc.jenkins-ci.org/credentials/com/cloudbees/plugins/credentials/common/AbstractIdCredentialsListBoxModel.html
	// https://github.com/jenkinsci/kubernetes-plugin/blob/master/src/main/java/org/csanchez/jenkins/plugins/kubernetes/KubernetesCloud.java
	public static ListBoxModel doFillCredentialsIdItems(String credentialsId) {
		Jenkins jenkins = Jenkins.getInstance();
		if (jenkins == null) {
			return (ListBoxModel) null;
		}

		if (!jenkins.hasPermission(Jenkins.ADMINISTER)) {
			// Important! Otherwise you expose credentials metadata to random
			// web requests.
			return new StandardListBoxModel().includeCurrentValue(credentialsId);
		}

		return new StandardListBoxModel().includeEmptyValue().includeAs(ACL.SYSTEM, jenkins, OpenShiftToken.class)
				.includeCurrentValue(credentialsId);
	}

	private synchronized void configChange() {
	    logger.info("OpenShift Sync Plugin processing a newly supplied configuration");
        if (buildConfigWatcher != null) {
            buildConfigWatcher.stop();
        }
        if (buildWatcher != null) {
            buildWatcher.stop();
        }
        if (configMapWatcher != null) {
            configMapWatcher.stop();
        }
        if (imageStreamWatcher != null) {
            imageStreamWatcher.stop();
        }
        if (secretWatcher != null) {
            secretWatcher.stop();
        }
        buildWatcher = null;
        buildConfigWatcher = null;
        configMapWatcher = null;
        imageStreamWatcher = null;
        secretWatcher = null;
        OpenShiftUtils.shutdownOpenShiftClient();
        
		if (!enabled) {
		    logger.info("OpenShift Sync Plugin has been disabled");
			return;
		}
		try {
			OpenShiftUtils.initializeOpenShiftClient(server);
			this.namespaces = getNamespaceOrUseDefault(namespaces, getOpenShiftClient());

			Runnable task = new SafeTimerTask() {
				@Override
				protected void doRun() throws Exception {
					logger.info("Confirming Jenkins is started");
					while (true) {
						final Jenkins instance = Jenkins.getActiveInstance();
						// We can look at Jenkins Init Level to see if we are
						// ready
						// to start. If we do not wait, we risk the chance of a
						// deadlock.
						//
						InitMilestone initLevel = instance.getInitLevel();
						logger.fine("Jenkins init level: " + initLevel.toString());
						if (initLevel == InitMilestone.COMPLETED) {
							break;
						}
						logger.fine("Jenkins not ready...");
						try {
							Thread.sleep(500);
						} catch (InterruptedException e) {
							// ignore
						}
					}

					buildConfigWatcher = new BuildConfigWatcher(namespaces);
					buildConfigWatcher.start();
					buildWatcher = new BuildWatcher(namespaces);
					buildWatcher.start();
					configMapWatcher = new ConfigMapWatcher(namespaces);
					configMapWatcher.start();
					imageStreamWatcher = new ImageStreamWatcher(namespaces);
					imageStreamWatcher.start();
					secretWatcher = new SecretWatcher(namespaces);
					secretWatcher.start();
				}
			};
			// lets give jenkins a while to get started ;)
			Timer.get().schedule(task, 1, TimeUnit.SECONDS);
		} catch (KubernetesClientException e) {
			if (e.getCause() != null) {
				logger.log(Level.SEVERE, "Failed to configure OpenShift Jenkins Sync Plugin: " + e.getCause());
			} else {
				logger.log(Level.SEVERE, "Failed to configure OpenShift Jenkins Sync Plugin: " + e);
			}
		}
	}
}
