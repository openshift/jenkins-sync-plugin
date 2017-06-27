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
import hudson.security.ACL;
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildList;
import io.fabric8.openshift.api.model.BuildStatus;
import jenkins.security.NotReallyRoleSensitiveCallable;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.getJobFromBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.getJobFromBuildConfigUid;
import static io.fabric8.jenkins.openshiftsync.BuildPhases.CANCELLED;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_BUILD_NUMBER;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_BUILD_STATUS_FIELD;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.cancelBuild;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.getJobFromBuild;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.handleBuildList;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.triggerJob;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.*;
import static java.util.logging.Level.WARNING;

public class BuildWatcher extends BaseWatcher implements Watcher<Build> {
  private static final Logger logger = Logger.getLogger(BuildWatcher.class.getName());

  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public BuildWatcher(String[] namespaces) {
      super(namespaces);
  }
  
  @Override
  public Runnable getStartTimerTask() {
      return new SafeTimerTask() {
          @Override
          public void doRun() {
            if (!CredentialsUtils.hasCredentials()) {
              logger.fine("No Openshift Token credential defined.");
              return;
            }
            for(String namespace:namespaces) {
              try {
                logger.fine("listing Build resources");
                BuildList newBuilds = getAuthenticatedOpenShiftClient().builds().inNamespace(namespace).withField(OPENSHIFT_BUILD_STATUS_FIELD, BuildPhases.NEW).list();
                onInitialBuilds(newBuilds);
                logger.fine("handled Build resources");
                if (watches.get(namespace) == null) {
                  watches.put(namespace,getAuthenticatedOpenShiftClient().builds().inNamespace(namespace).withResourceVersion(newBuilds.getMetadata().getResourceVersion()).watch(BuildWatcher.this));
                }
              } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to load initial Builds: " + e, e);
              }
            }
          }
        };
  }
  
  

  public void start() {
    BuildToParametersActionMap.initialize();
    super.start();
  }

  @SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
  @Override
  public synchronized void eventReceived(Action action, Build build) {
    try {
      switch (action) {
        case ADDED:
          addEventToJenkinsJobRun(build);
          break;
        case MODIFIED:
          modifyEventToJenkinsJobRun(build);
          break;
        case DELETED:
            deleteEventToJenkinsJobRun(build);
            break;
      }
    } catch (Exception e) {
      logger.log(WARNING, "Caught: " + e, e);
    }
  }

  public synchronized static void onInitialBuilds(BuildList buildList) {
    List<Build> items = buildList.getItems();
    if (items != null) {

      Collections.sort(items, new Comparator<Build>() {
        @Override
        public int compare(Build b1, Build b2) {
          return Long.compare(
            Long.parseLong(b1.getMetadata().getAnnotations().get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER)),
            Long.parseLong(b2.getMetadata().getAnnotations().get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER))
          );
        }
      });

      // We need to sort the builds into their build configs so we can handle build run policies correctly.
      Map<String, BuildConfig> buildConfigMap = new HashMap<>();
      Map<BuildConfig, List<Build>> buildConfigBuildMap = new HashMap<>(items.size());
      for (Build b : items) {
        String buildConfigName = b.getStatus().getConfig().getName();
        if (StringUtils.isEmpty(buildConfigName)) {
          continue;
        }
        String namespace = b.getMetadata().getNamespace();
        String bcMapKey = namespace + "/" + buildConfigName;
        BuildConfig bc = buildConfigMap.get(bcMapKey);
        if (bc == null) {
          bc = getAuthenticatedOpenShiftClient().buildConfigs().inNamespace(namespace).withName(buildConfigName).get();
          if (bc == null) {
            continue;
          }
          buildConfigMap.put(bcMapKey, bc);
        }
        List<Build> bcBuilds = buildConfigBuildMap.get(bc);
        if (bcBuilds == null) {
          bcBuilds = new ArrayList<>();
          buildConfigBuildMap.put(bc, bcBuilds);
        }
        bcBuilds.add(b);
      }

      // Now handle the builds.
      for (Map.Entry<BuildConfig, List<Build>> buildConfigBuilds : buildConfigBuildMap.entrySet()) {
        BuildConfig bc = buildConfigBuilds.getKey();
        if (bc.getMetadata() == null) {
          // Should never happen but let's be safe...
          continue;
        }
        WorkflowJob job = getJobFromBuildConfig(bc);
        if (job == null) {
          continue;
        }
        BuildConfigProjectProperty bcp = job.getProperty(BuildConfigProjectProperty.class);
        if (bcp == null) {
          continue;
        }
        List<Build> builds = buildConfigBuilds.getValue();
        handleBuildList(job, builds, bcp);
      }
    }
  }

  private static synchronized void modifyEventToJenkinsJobRun(Build build) {
    BuildStatus status = build.getStatus();
    if (status != null &&
      isCancellable(status) &&
      isCancelled(status)) {
      WorkflowJob job = getJobFromBuild(build);
      if (job != null) {
        cancelBuild(job, build);
      }
    }
  }

  public static synchronized boolean addEventToJenkinsJobRun(Build build) throws IOException {
    BuildStatus status = build.getStatus();
    if (status != null) {
      if (isCancelled(status)) {
        updateOpenShiftBuildPhase(build, CANCELLED);
        return false;
      }
      if (!isNew(status)) {
        return false;
      }
    }

    WorkflowJob job = getJobFromBuild(build);
    if (job != null) {
      return triggerJob(job, build);
    }
    return false;
  }
  
  // innerDeleteEventToJenkinsJobRun is the actual delete logic at the heart of deleteEventToJenkinsJobRun
  // that is either in a sync block or not based on the presence of a BC uid
  private static synchronized void innerDeleteEventToJenkinsJobRun(final Build build) throws Exception {
      final WorkflowJob job = getJobFromBuild(build);
      if (job != null) {
        ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
          @Override
          public Void call() throws Exception {
            cancelBuild(job, build, true);
            return null;
          }
        });
      }
  }

  // in response to receiving an openshift delete build event, this method will drive 
  // the clean up of the Jenkins job run the build is mapped one to one with; as part of that 
  // clean up it will synchronize with the build config event watcher to handle build config
  // delete events and build delete events that arrive concurrently and in a nondeterministic
  // order
  private static synchronized void deleteEventToJenkinsJobRun(final Build build) throws Exception {
      List<OwnerReference> ownerRefs = build.getMetadata().getOwnerReferences();
      String bcUid = null;
      for (OwnerReference ref : ownerRefs) {
          if ("BuildConfig".equals(ref.getKind()) && ref.getUid() != null && ref.getUid().length() > 0) {
              // employ intern to facilitate sync'ing on the same actual object
              bcUid = ref.getUid().intern();
              synchronized(bcUid) {
                  // if entire job already deleted via bc delete, just return
                  if (getJobFromBuildConfigUid(bcUid) == null)
                      return;
                  innerDeleteEventToJenkinsJobRun(build);
                  return;
              }
          }
      }
      // otherwise, if something odd is up and there is no parent BC, just clean up
      innerDeleteEventToJenkinsJobRun(build);
  }
}
