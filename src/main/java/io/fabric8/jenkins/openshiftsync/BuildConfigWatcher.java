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
import hudson.BulkChange;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.triggers.SafeTimerTask;
import hudson.util.XStream2;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigList;
import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;
import jenkins.util.Timer;

import org.apache.tools.ant.filters.StringInputStream;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.getJobFromBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.initializeBuildConfigToJobMap;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.putJobWithBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.removeJobWithBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMapper.mapBuildConfigToFlow;
import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL;
import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL_LATEST_ONLY;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.maybeScheduleNext;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isJenkinsBuildConfig;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.jenkinsJobDisplayName;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.jenkinsJobName;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.parseResourceVersion;
import static java.net.HttpURLConnection.HTTP_GONE;
import static java.util.logging.Level.SEVERE;

/**
 * Watches {@link BuildConfig} objects in OpenShift and for WorkflowJobs we ensure there is a
 * suitable Jenkins Job object defined with the correct configuration
 */
public class BuildConfigWatcher implements Watcher<BuildConfig> {
  private final Logger logger = Logger.getLogger(getClass().getName());
  private final String namespace;
  private Watch buildConfigWatch;
  private ScheduledFuture relister;

  public BuildConfigWatcher(String namespace) {
    this.namespace = namespace;
  }

  public synchronized void start() {
    initializeBuildConfigToJobMap();

    // lets process the initial state
    logger.info("Now handling startup build configs!!");
    // lets do this in a background thread to avoid errors like:
    //  Tried proxying io.fabric8.jenkins.openshiftsync.GlobalPluginConfiguration to support a circular dependency, but it is not an interface.
    Runnable task = new SafeTimerTask() {
      @Override
      public void doRun() {
        try {
          logger.fine("listing BuildConfigs resources");
          final BuildConfigList buildConfigs = getOpenShiftClient().buildConfigs().inNamespace(namespace).list();
          onInitialBuildConfigs(buildConfigs);
          logger.fine("handled BuildConfigs resources");
          if (buildConfigWatch == null) {
            buildConfigWatch = getOpenShiftClient().buildConfigs().inNamespace(namespace).withResourceVersion(buildConfigs.getMetadata().getResourceVersion()).watch(BuildConfigWatcher.this);
          }
        } catch (Exception e) {
          logger.log(SEVERE, "Failed to load BuildConfigs: " + e, e);
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
    if (buildConfigWatch != null) {
      buildConfigWatch.close();
      buildConfigWatch = null;
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

  private synchronized void onInitialBuildConfigs(BuildConfigList buildConfigs) {
    List<BuildConfig> items = buildConfigs.getItems();
    if (items != null) {
      for (BuildConfig buildConfig : items) {
        try {
          upsertJob(buildConfig);
        } catch (Exception e) {
          logger.log(SEVERE, "Failed to update job", e);
        }
      }
    }
  }

  @SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
  @Override
  public synchronized void eventReceived(Watcher.Action action, BuildConfig buildConfig) {
    try {
      switch (action) {
        case ADDED:
          upsertJob(buildConfig);
          break;
        case DELETED:
          deleteJob(buildConfig);
          break;
        case MODIFIED:
          modifyJob(buildConfig);
          break;
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Caught: " + e, e);
    }
  }
  
  private void updateJob(WorkflowJob job, InputStream jobStream, String jobName, BuildConfig buildConfig, String existingBuildRunPolicy, BuildConfigProjectProperty buildConfigProjectProperty) throws IOException {
      Source source = new StreamSource(jobStream);
      job.updateByXml(source);
      job.save();
      logger.info("Updated job " + jobName + " from BuildConfig " + NamespaceName.create(buildConfig) + " with revision: " + buildConfig.getMetadata().getResourceVersion());
      if (existingBuildRunPolicy != null && !existingBuildRunPolicy.equals(buildConfigProjectProperty.getBuildRunPolicy())) {
        maybeScheduleNext(job);
      }
  }

  private void upsertJob(final BuildConfig buildConfig) throws Exception {
    if (isJenkinsBuildConfig(buildConfig)) {
      ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
        @Override
        public Void call() throws Exception {
          String jobName = jenkinsJobName(buildConfig);
          WorkflowJob job = getJobFromBuildConfig(buildConfig);
          boolean newJob = job == null;
          if (newJob) {
            job = new WorkflowJob(Jenkins.getActiveInstance(), jobName);
          }
          BulkChange bk = new BulkChange(job);

          job.setDisplayName(jenkinsJobDisplayName(buildConfig));

          FlowDefinition flowFromBuildConfig = mapBuildConfigToFlow(buildConfig);
          if (flowFromBuildConfig == null) {
            return null;
          }

          job.setDefinition(flowFromBuildConfig);

          String existingBuildRunPolicy = null;

          BuildConfigProjectProperty buildConfigProjectProperty = job.getProperty(BuildConfigProjectProperty.class);
          if (buildConfigProjectProperty != null) {
            existingBuildRunPolicy = buildConfigProjectProperty.getBuildRunPolicy();
            long updatedBCResourceVersion = parseResourceVersion(buildConfig);
            long oldBCResourceVersion = parseResourceVersion(buildConfigProjectProperty.getResourceVersion());
            BuildConfigProjectProperty newProperty = new BuildConfigProjectProperty(buildConfig);
            if (updatedBCResourceVersion <= oldBCResourceVersion &&
              newProperty.getUid().equals(buildConfigProjectProperty.getUid()) &&
              newProperty.getNamespace().equals(buildConfigProjectProperty.getNamespace()) &&
              newProperty.getName().equals(buildConfigProjectProperty.getName()) &&
              newProperty.getBuildRunPolicy().equals(buildConfigProjectProperty.getBuildRunPolicy())
              ) {
              return null;
            }
            buildConfigProjectProperty.setUid(newProperty.getUid());
            buildConfigProjectProperty.setNamespace(newProperty.getNamespace());
            buildConfigProjectProperty.setName(newProperty.getName());
            buildConfigProjectProperty.setResourceVersion(newProperty.getResourceVersion());
            buildConfigProjectProperty.setBuildRunPolicy(newProperty.getBuildRunPolicy());
          } else {
            job.addProperty(
              new BuildConfigProjectProperty(buildConfig)
            );
          }

          job.setConcurrentBuild(
            !(buildConfig.getSpec().getRunPolicy().equals(SERIAL) ||
              buildConfig.getSpec().getRunPolicy().equals(SERIAL_LATEST_ONLY))
          );

          InputStream jobStream = new StringInputStream(new XStream2().toXML(job));

          if (newJob) {
              try {
                  Jenkins.getActiveInstance().createProjectFromXML(
                          jobName,
                          jobStream
                        ).save();
                        logger.info("Created job " + jobName + " from BuildConfig " + NamespaceName.create(buildConfig) + " with revision: " + buildConfig.getMetadata().getResourceVersion());
              } catch (IllegalArgumentException e) {
                  // see https://github.com/openshift/jenkins-sync-plugin/issues/117, jenkins might reload existing jobs on startup between the
                  // newJob check above and when we make the createProjectFromXML call; if so, retry as an update
                  updateJob(job, jobStream, jobName, buildConfig, existingBuildRunPolicy, buildConfigProjectProperty);
              }
          } else {
              updateJob(job, jobStream, jobName, buildConfig, existingBuildRunPolicy, buildConfigProjectProperty);
          }
          bk.commit();
          putJobWithBuildConfig(Jenkins.getActiveInstance().getItemByFullName(job.getFullName(), WorkflowJob.class), buildConfig);
          return null;
        }
      });
    }
  }

  private void modifyJob(BuildConfig buildConfig) throws Exception {
    if (isJenkinsBuildConfig(buildConfig)) {
      upsertJob(buildConfig);
      return;
    }

    // no longer a Jenkins build so lets delete it if it exists
    deleteJob(buildConfig);
  }

  private void deleteJob(final BuildConfig buildConfig) throws Exception {
    final Job job = getJobFromBuildConfig(buildConfig);
    if (job != null) {
      ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
        @Override
        public Void call() throws Exception {
          try {
            job.delete();
          } finally {
            removeJobWithBuildConfig(buildConfig);
            Jenkins.getActiveInstance().rebuildDependencyGraphAsync();
          }
          return null;
        }
      });
    }
  }
}
