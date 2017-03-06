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
import hudson.model.Computer;
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

  private String skipOrganisationPrefix;

  private String skipBranchSuffix;

  private transient BuildWatcher buildWatcher;

  private transient BuildConfigWatcher buildConfigWatcher;

  private transient SecretWatcher secretWatcher;

  @DataBoundConstructor
  public GlobalPluginConfiguration(boolean enable, String server, String namespace, String credentialsId, String jobNamePattern, String skipOrganisationPrefix, String skipBranchSuffix) {
    this.enabled = enable;
    this.server = server;
    this.namespaces = StringUtils.isBlank(namespace)?null:namespace.split(" ");
    this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    this.jobNamePattern = jobNamePattern;
    this.skipOrganisationPrefix = skipOrganisationPrefix;
    this.skipBranchSuffix = skipBranchSuffix;
    configChange();
  }

  public GlobalPluginConfiguration() {
    load();
    configChange();
    save();
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

  public String getCredentialsId() {
    return credentialsId;
  }

  public void setCredentialsId(String credentialsId) {
    this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
  }

  public String getNamespace() {
    return namespaces==null?"":StringUtils.join(namespaces," ");
  }

  public void setNamespace(String namespace) {
    this.namespaces = StringUtils.isBlank(namespace)?null:namespace.split(" ");
  }

  // https://wiki.jenkins-ci.org/display/JENKINS/Credentials+Plugin
  // http://javadoc.jenkins-ci.org/credentials/com/cloudbees/plugins/credentials/common/AbstractIdCredentialsListBoxModel.html
  // https://github.com/jenkinsci/kubernetes-plugin/blob/master/src/main/java/org/csanchez/jenkins/plugins/kubernetes/KubernetesCloud.java
  public static ListBoxModel doFillCredentialsIdItems(String credentialsId) {
    Jenkins jenkins = Jenkins.getInstance();
    if( jenkins == null ) {
      return (ListBoxModel)null;
    }

    if (!jenkins.hasPermission(Jenkins.ADMINISTER)) {
      // Important! Otherwise you expose credentials metadata to random web requests.
      return new StandardListBoxModel().includeCurrentValue(credentialsId);
    }

    return new StandardListBoxModel()
            .includeEmptyValue()
            .includeAs(ACL.SYSTEM, jenkins, OpenShiftToken.class)
            .includeCurrentValue(credentialsId)
            ;
  }

  public String getJobNamePattern() {
    return jobNamePattern;
  }

  public void setJobNamePattern(String jobNamePattern) {
    this.jobNamePattern = jobNamePattern;
  }

  public String getSkipOrganisationPrefix() {
    return skipOrganisationPrefix;
  }

  public void setSkipOrganisationPrefix(String skipOrganisationPrefix) {
    this.skipOrganisationPrefix = skipOrganisationPrefix;
  }

  public String getSkipBranchSuffix() {
    return skipBranchSuffix;
  }

  public void setSkipBranchSuffix(String skipBranchSuffix) {
    this.skipBranchSuffix = skipBranchSuffix;
  }

  private void configChange() {
    if (!enabled) {
      if (buildConfigWatcher != null) {
        buildConfigWatcher.stop();
      }
      if (buildWatcher != null) {
        buildWatcher.stop();
      }
      if (secretWatcher != null) {
        secretWatcher.stop();
      }
      OpenShiftUtils.shutdownOpenShiftClient();
      return;
    }
    try {
      OpenShiftUtils.initializeOpenShiftClient(server);
      this.namespaces = getNamespaceOrUseDefault(namespaces, getOpenShiftClient());

      Runnable task = new SafeTimerTask() {
        @Override
        protected void doRun() throws Exception {
          logger.info("Waiting for Jenkins to be started");
          while (true) {
            Computer[] computers = Jenkins.getActiveInstance().getComputers();
            boolean ready = false;
            for (Computer c : computers) {
              // Jenkins.isAcceptingTasks() results in hudson.model.Node.isAcceptingTasks() getting called, and that always returns true;
              // the Computer.isAcceptingTasks actually introspects various Jenkins data structures to determine readiness
              if (c.isAcceptingTasks()) {
                ready = true;
                break;
              }
            }
            if (ready) {
              break;
            }
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
          secretWatcher = new SecretWatcher(namespaces);
          secretWatcher.start();
        }
      };
      // lets give jenkins a while to get started ;)
      Timer.get().schedule(task, 1,TimeUnit.SECONDS);
    } catch (KubernetesClientException e) {
      if (e.getCause() != null) {
        logger.log(Level.SEVERE, "Failed to configure OpenShift Jenkins Sync Plugin: " +  e.getCause());
      } else {
        logger.log(Level.SEVERE, "Failed to configure OpenShift Jenkins Sync Plugin: " +  e);
      }
    }
  }

}
