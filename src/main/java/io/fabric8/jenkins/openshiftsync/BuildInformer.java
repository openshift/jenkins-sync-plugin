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

import static io.fabric8.jenkins.openshiftsync.Annotations.BUILDCONFIG_NAME;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.getJobFromBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.getJobFromBuildConfigNameNamespace;
import static io.fabric8.jenkins.openshiftsync.BuildPhases.CANCELLED;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.cancelBuild;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.deleteRun;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.getJobFromBuild;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.handleBuildList;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.triggerJob;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAnnotation;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getInformerFactory;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isCancellable;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isCancelled;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isNew;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.updateOpenShiftBuildPhase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import hudson.security.ACL;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildList;
import io.fabric8.openshift.api.model.BuildStatus;
import io.fabric8.openshift.client.OpenShiftClient;
import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;

public class BuildInformer extends BuildWatcher implements ResourceEventHandler<Build> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecretInformer.class.getName());
    private final static BuildComparator BUILD_COMPARATOR = new BuildComparator();
    private SharedIndexInformer<Build> informer;

    public BuildInformer(String namespace) {
        super(namespace);
    }

    /**
     * now that listing interval is 5 minutes (used to be 10 seconds), we have seen
     * timing windows where if the build watch events come before build config watch
     * events when both are created in a simultaneous fashion, there is an up to 5
     * minutes delay before the job run gets kicked off started seeing duplicate
     * builds getting kicked off so quit depending on so moved off of concurrent
     * hash set to concurrent hash map using namepace/name key
     */
    @Override
    public int getListIntervalInSeconds() {
        return 1_000 * GlobalPluginConfiguration.get().getBuildListInterval();
    }

    public void start() {
        LOGGER.info("Starting Build informer for {} !!" + namespace);
        LOGGER.debug("Listing Build resources");
        SharedInformerFactory factory = getInformerFactory().inNamespace(namespace);
        this.informer = factory.sharedIndexInformerFor(Build.class, getListIntervalInSeconds());
        this.informer.addEventHandler(this);
        LOGGER.info("Build informer started for namespace: {}" + namespace);
//        BuildList list = getOpenshiftClient().builds().inNamespace(namespace).list();
//        onInit(list.getItems());
    }

    @Override
    public void onAdd(Build obj) {
        LOGGER.debug("Build informer  received add event for: {}" + obj);
        if (obj != null) {
            ObjectMeta metadata = obj.getMetadata();
            String name = metadata.getName();
            LOGGER.info("Build informer received add event for: {}" + name);
            addEventToJenkinsJobRun(obj);
        }
    }

    @Override
    public void onUpdate(Build oldObj, Build newObj) {
        LOGGER.debug("Build informer received update event for: {} to: {}" + oldObj + " " + newObj);
        if (newObj != null) {
            String oldRv = oldObj.getMetadata().getResourceVersion();
            String newRv = newObj.getMetadata().getResourceVersion();
            LOGGER.info("Build informer received update event for: {} to: {}" + oldRv + " " + newRv);
            modifyEventToJenkinsJobRun(newObj);
        }
    }

    @Override
    public void onDelete(Build obj, boolean deletedFinalStateUnknown) {
        LOGGER.info("Build informer received delete event for: {}" + obj);
        if (obj != null) {
            deleteEventToJenkinsJobRun(obj);
        }
    }

    public static void onInit(List<Build> list) {
        Collections.sort(list, BUILD_COMPARATOR);
        // We need to sort the builds into their build configs so we can
        // handle build run policies correctly.
        Map<String, BuildConfig> buildConfigMap = new HashMap<>();
        Map<BuildConfig, List<Build>> buildConfigBuildMap = new HashMap<>(list.size());
        mapBuildToBuildConfigs(list, buildConfigMap, buildConfigBuildMap);
        mapBuildsToBuildConfigs(buildConfigBuildMap);
        reconcileRunsAndBuilds();
    }

    private static void mapBuildsToBuildConfigs(Map<BuildConfig, List<Build>> buildConfigBuildMap) {
        // Now handle the builds.
        for (Map.Entry<BuildConfig, List<Build>> buildConfigBuilds : buildConfigBuildMap.entrySet()) {
            BuildConfig bc = buildConfigBuilds.getKey();
            if (bc.getMetadata() == null) {
                // Should never happen but let's be safe...
                continue;
            }
            WorkflowJob job = getJobFromBuildConfig(bc);
            if (job == null) {
                List<Build> builds = buildConfigBuilds.getValue();
                for (Build b : builds) {
                    LOGGER.info("skipping listed new build " + b.getMetadata().getName() + " no job at this time");
                    addBuildToNoBCList(b);
                }
                continue;
            }
            BuildConfigProjectProperty bcp = job.getProperty(BuildConfigProjectProperty.class);
            if (bcp == null) {
                List<Build> builds = buildConfigBuilds.getValue();
                for (Build b : builds) {
                    LOGGER.info("skipping listed new build " + b.getMetadata().getName() + " no prop at this time");
                    addBuildToNoBCList(b);
                }
                continue;
            }
            List<Build> builds = buildConfigBuilds.getValue();
            handleBuildList(job, builds, bcp);
        }
    }

    private static void mapBuildToBuildConfigs(List<Build> list, Map<String, BuildConfig> buildConfigMap,
            Map<BuildConfig, List<Build>> buildConfigBuildMap) {
        for (Build b : list) {
            if (!OpenShiftUtils.isPipelineStrategyBuild(b)) {
                continue;
            }
            String buildConfigName = b.getStatus().getConfig().getName();
            if (StringUtils.isEmpty(buildConfigName)) {
                continue;
            }
            String namespace = b.getMetadata().getNamespace();
            String buildConfigNamespacedName = namespace + "/" + buildConfigName;
            BuildConfig bc = buildConfigMap.get(buildConfigNamespacedName);
            if (bc == null) {
                final OpenShiftClient client = getAuthenticatedOpenShiftClient();
                bc = client.buildConfigs().inNamespace(namespace).withName(buildConfigName).get();
                if (bc == null) {
                    // if the bc is not there via a REST get, then it is not
                    // going to be, and we are not handling manual creation
                    // of pipeline build objects, so don't bother with "no bc list"
                    continue;
                }
                buildConfigMap.put(buildConfigNamespacedName, bc);
            }
            List<Build> bcBuilds = buildConfigBuildMap.get(bc);
            if (bcBuilds == null) {
                bcBuilds = new ArrayList<>();
                buildConfigBuildMap.put(bc, bcBuilds);
            }
            bcBuilds.add(b);
        }
    }

    private static void modifyEventToJenkinsJobRun(Build build) {
        BuildStatus status = build.getStatus();
        if (status != null && isCancellable(status) && isCancelled(status)) {
            WorkflowJob job = getJobFromBuild(build);
            if (job != null) {
                cancelBuild(job, build);
            } else {
                removeBuildFromNoBCList(build);
            }
        } else {
            // see if any pre-BC cached builds can now be flushed
            flushBuildsWithNoBCList();
        }
    }

    public static boolean addEventToJenkinsJobRun(Build build) {
        // should have been caught upstack, but just in case since public method
        if (!OpenShiftUtils.isPipelineStrategyBuild(build))
            return false;
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
            try {
                return triggerJob(job, build);
            } catch (IOException e) {
                LOGGER.error("Error while trying to trigger Job: " + e);
            }
        }
        LOGGER.info("skipping watch event for build " + build.getMetadata().getName() + " no job at this time");
        addBuildToNoBCList(build);
        return false;
    }

    private static void addBuildToNoBCList(Build build) {
        // should have been caught upstack, but just in case since public method
        if (!OpenShiftUtils.isPipelineStrategyBuild(build))
            return;
        try {
            buildsWithNoBCList.put(build.getMetadata().getNamespace() + build.getMetadata().getName(), build);
        } catch (ConcurrentModificationException | IllegalArgumentException | UnsupportedOperationException
                | NullPointerException e) {
            LOGGER.warn( "Failed to add item " + build.getMetadata().getName(), e);
        }
    }

    private static void removeBuildFromNoBCList(Build build) {
        buildsWithNoBCList.remove(build.getMetadata().getNamespace() + build.getMetadata().getName());
    }

    // trigger any builds whose watch events arrived before the
    // corresponding build config watch events
    public static void flushBuildsWithNoBCList() {

        ConcurrentHashMap<String, Build> clone = null;
        synchronized (buildsWithNoBCList) {
            clone = new ConcurrentHashMap<String, Build>(buildsWithNoBCList);
        }
        boolean anyRemoveFailures = false;
        for (Build build : clone.values()) {
            WorkflowJob job = getJobFromBuild(build);
            if (job != null) {
                try {
                    LOGGER.info("triggering job run for previously skipped build " + build.getMetadata().getName());
                    triggerJob(job, build);
                } catch (IOException e) {
                    LOGGER.warn( "flushBuildsWithNoBCList", e);
                }
                try {
                    synchronized (buildsWithNoBCList) {
                        removeBuildFromNoBCList(build);
                    }
                } catch (Throwable t) {
                    // TODO
                    // concurrent mod exceptions are not suppose to occur
                    // with concurrent hash set; this try/catch with log
                    // and the anyRemoveFailures post processing is a bit
                    // of safety paranoia until this proves to be true
                    // over extended usage ... probably can remove at some
                    // point
                    anyRemoveFailures = true;
                    LOGGER.warn( "flushBuildsWithNoBCList", t);
                }
            }

            synchronized (buildsWithNoBCList) {
                if (anyRemoveFailures && buildsWithNoBCList.size() > 0) {
                    buildsWithNoBCList.clear();
                }

            }
        }
    }

    // innerDeleteEventToJenkinsJobRun is the actual delete logic at the heart of
    // deleteEventToJenkinsJobRun that is either in a sync block or not based on the
    // presence of a BC uid
    @SuppressWarnings({ "deprecation", "serial" })
    private static void innerDeleteEventToJenkinsJobRun(final Build build) throws Exception {
        final WorkflowJob job = getJobFromBuild(build);
        if (job != null) {
            ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
                @Override
                public Void call() throws Exception {
                    cancelBuild(job, build, true);
                    return null;
                }
            });
        } else {
            // in case build was created and deleted quickly, prior to seeing BC
            // event, clear out from pre-BC cache
            removeBuildFromNoBCList(build);
        }
        deleteRun(job, build);
    }

    // in response to receiving an openshift delete build event, this method
    // will drive the clean up of the Jenkins job run the build is mapped one to one
    // with; as part of that clean up it will synchronize with the build config
    // event watcher to handle build config delete events and build delete events
    // that arrive concurrently and in a nondeterministic order
    private static void deleteEventToJenkinsJobRun(final Build build) {
        List<OwnerReference> ownerRefs = build.getMetadata().getOwnerReferences();
        String bcUid = null;
        for (OwnerReference ref : ownerRefs) {
            if ("BuildConfig".equals(ref.getKind()) && ref.getUid() != null && ref.getUid().length() > 0) {
                // employ intern to facilitate sync'ing on the same actual object
                bcUid = ref.getUid().intern();
                synchronized (bcUid) {
                    // if entire job already deleted via bc delete, just return
                    if (getJobFromBuildConfigNameNamespace(getAnnotation(build, BUILDCONFIG_NAME),
                            build.getMetadata().getNamespace()) == null) {
                        return;
                    }
                    try {
                        innerDeleteEventToJenkinsJobRun(build);
                    } catch (Exception e) {
                        LOGGER.error("Error while trying to delete JobRun: " + e);
                    }
                    return;
                }
            }
        }
        // otherwise, if something odd is up and there is no parent BC, just clean up
        try {
            innerDeleteEventToJenkinsJobRun(build);
        } catch (Exception e) {
            LOGGER.error("Error while trying to delete JobRun: " + e);
        }
    }

    /**
     * Reconciles Jenkins job runs and OpenShift builds
     *
     * Deletes all job runs that do not have an associated build in OpenShift
     */
    @SuppressWarnings("deprecation")
    private static void reconcileRunsAndBuilds() {
        LOGGER.debug("Reconciling job runs and builds");
        List<WorkflowJob> jobs = Jenkins.getActiveInstance().getAllItems(WorkflowJob.class);
        for (WorkflowJob job : jobs) {
            BuildConfigProjectProperty property = job.getProperty(BuildConfigProjectProperty.class);
            if (property != null) {
                String ns = property.getNamespace();
                String name = property.getName();
                if (StringUtils.isNotBlank(ns) && StringUtils.isNotBlank(name)) {
                    LOGGER.debug("Checking job " + job + " runs for BuildConfig " + ns + "/" + name);
                    OpenShiftClient client = getAuthenticatedOpenShiftClient();
                    BuildList builds = client.builds().inNamespace(ns).withLabel("buildconfig=" + name).list();
                    for (WorkflowRun run : job.getBuilds()) {
                        boolean found = false;
                        BuildCause cause = run.getCause(BuildCause.class);
                        for (Build build : builds.getItems()) {
                            if (cause != null && cause.getUid().equals(build.getMetadata().getUid())) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            deleteRun(run);
                        }
                    }
                }
            }

        }
    }

}
