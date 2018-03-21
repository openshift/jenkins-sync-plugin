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

import com.cloudbees.hudson.plugins.folder.Folder;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.BulkChange;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.security.ACL;
import hudson.triggers.SafeTimerTask;
import hudson.util.XStream2;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigList;
import io.fabric8.openshift.api.model.BuildList;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.Annotations.DISABLE_SYNC_CREATE;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.getJobFromBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.initializeBuildConfigToJobMap;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.putJobWithBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.removeJobWithBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMapper.mapBuildConfigToFlow;
import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL;
import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL_LATEST_ONLY;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_BUILD_STATUS_FIELD;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_LABELS_BUILD_CONFIG_NAME;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.maybeScheduleNext;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.*;
import static java.util.logging.Level.SEVERE;

/**
 * Watches {@link BuildConfig} objects in OpenShift and for WorkflowJobs we
 * ensure there is a suitable Jenkins Job object defined with the correct
 * configuration
 */
public class BuildConfigWatcher extends BaseWatcher {
    private final Logger logger = Logger.getLogger(getClass().getName());

    // for coordinating between ItemListener.onUpdate and onDeleted both
    // getting called when we delete a job; ID should be combo of namespace
    // and name for BC to properly differentiate; we don't use UUID since
    // when we filter on the ItemListener side the UUID may not be
    // available
    private static final HashSet<String> deletesInProgress = new HashSet<String>();

    public static synchronized void deleteInProgress(String bcName) {
        deletesInProgress.add(bcName);
    }

    public static synchronized boolean isDeleteInProgress(String bcID) {
        return deletesInProgress.contains(bcID);
    }

    public static synchronized void deleteCompleted(String bcID) {
        deletesInProgress.remove(bcID);
    }

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public BuildConfigWatcher(String[] namespaces) {
        super(namespaces);
    }

    @Override
    public int getListIntervalInSeconds() {
        return GlobalPluginConfiguration.get().getBuildConfigListInterval();
    }

    public Runnable getStartTimerTask() {
        return new SafeTimerTask() {
            @Override
            public void doRun() {
                if (!CredentialsUtils.hasCredentials()) {
                    logger.fine("No Openshift Token credential defined.");
                    return;
                }
                for (String namespace : namespaces) {
                    BuildConfigList buildConfigs = null;
                    try {
                        logger.fine("listing BuildConfigs resources");
                        buildConfigs = getAuthenticatedOpenShiftClient().buildConfigs().inNamespace(namespace).list();
                        onInitialBuildConfigs(buildConfigs);
                        logger.fine("handled BuildConfigs resources");
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to load BuildConfigs: " + e, e);
                    }
                    try {
                        String resourceVersion = "0";
                        if (buildConfigs == null) {
                            logger.warning("Unable to get build config list; impacts resource version used for watch");
                        } else {
                            resourceVersion = buildConfigs.getMetadata().getResourceVersion();
                        }
                        synchronized(BuildConfigWatcher.this) {
                            if (watches.get(namespace) == null) {
                                logger.info("creating BuildConfig watch for namespace " + namespace + " and resource version " + resourceVersion);
                                watches.put(namespace, getAuthenticatedOpenShiftClient().buildConfigs().inNamespace(namespace).withResourceVersion(resourceVersion).watch(new WatcherCallback<BuildConfig>(BuildConfigWatcher.this,namespace)));
                            }
                        }
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to load BuildConfigs: " + e, e);
                    }
                }
                // poke the BuildWatcher builds with no BC list and see if we
                // can create job
                // runs for premature builds
                BuildWatcher.flushBuildsWithNoBCList();
            }
        };
    }

    public synchronized void start() {
        initializeBuildConfigToJobMap();
        logger.info("Now handling startup build configs!!");
        super.start();

    }

    private synchronized void onInitialBuildConfigs(BuildConfigList buildConfigs) {
        if (buildConfigs == null)
            return;
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
    public synchronized void eventReceived(Action action, BuildConfig buildConfig) {
        try {
            switch (action) {
            case ADDED:
                upsertJob(buildConfig);
                break;
            case DELETED:
                deleteEventToJenkinsJob(buildConfig);
                break;
            case MODIFIED:
                modifyEventToJenkinsJob(buildConfig);
                break;
            case ERROR:
                logger.warning("watch for buildconfig " + buildConfig.getMetadata().getName() + " received error event ");
                break;
            default:
                logger.warning("watch for buildconfig " + buildConfig.getMetadata().getName() + " received unknown event " + action);
                break;
            }
            // we employ impersonation here to insure we have "full access";
            // for example, can we actually
            // read in jobs defs for verification? without impersonation here
            // we would get null back when trying to read in the job from disk
            ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
                @Override
                public Void call() throws Exception {
                    // if bc event came after build events, let's
                    // poke the BuildWatcher builds with no BC list to
                    // create job
                    // runs
                    BuildWatcher.flushBuildsWithNoBCList();
                    // now, if the build event was lost and never
                    // received, builds
                    // will stay in
                    // new for 5 minutes ... let's launch a background
                    // thread to
                    // clean them up
                    // at a quicker interval than the default 5 minute
                    // general build
                    // relist function
                    if (action == Action.ADDED) {
                        Runnable backupBuildQuery = new SafeTimerTask() {
                            @Override
                            public void doRun() {
                                if (!CredentialsUtils.hasCredentials()) {
                                    logger.fine("No Openshift Token credential defined.");
                                    return;
                                }
                                BuildList buildList = getAuthenticatedOpenShiftClient().builds().inNamespace(buildConfig.getMetadata().getNamespace()).withField(OPENSHIFT_BUILD_STATUS_FIELD, BuildPhases.NEW)
                                        .withLabel(OPENSHIFT_LABELS_BUILD_CONFIG_NAME, buildConfig.getMetadata().getName()).list();
                                if (buildList.getItems().size() > 0) {
                                    logger.info("build backup query for " + buildConfig.getMetadata().getName() + " found new builds");
                                    BuildWatcher.onInitialBuilds(buildList);
                                }
                            }
                        };
                        Timer.get().schedule(backupBuildQuery, 10 * 1000, TimeUnit.MILLISECONDS);
                    }
                    return null;
                }
            });
        } catch (Exception e) {
            logger.log(Level.WARNING, "Caught: " + e, e);
        }
    }
    @Override
    public <T> void eventReceived(io.fabric8.kubernetes.client.Watcher.Action action, T resource) {
        BuildConfig bc = (BuildConfig)resource;
        eventReceived(action, bc);
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
        if (isPipelineStrategyBuildConfig(buildConfig)) {
            // sync on intern of name should guarantee sync on same actual obj
            synchronized (buildConfig.getMetadata().getUid().intern()) {
                ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
                    @Override
                    public Void call() throws Exception {
                        String jobName = jenkinsJobName(buildConfig);
                        String jobFullName = jenkinsJobFullName(buildConfig);
                        WorkflowJob job = getJobFromBuildConfig(buildConfig);
                        Jenkins activeInstance = Jenkins.getActiveInstance();
                        ItemGroup parent = activeInstance;
                        if (job == null) {
                            job = (WorkflowJob) activeInstance.getItemByFullName(jobFullName);
                        }
                        boolean newJob = job == null;
                        if (newJob) {
                            String disableOn = getAnnotation(buildConfig, DISABLE_SYNC_CREATE);
                            if (disableOn != null && disableOn.length() > 0) {
                                logger.fine("Not creating missing jenkins job " + jobFullName + " due to annotation: " + DISABLE_SYNC_CREATE);
                                return null;
                            }
                            parent = getFullNameParent(activeInstance, jobFullName, getNamespace(buildConfig));
                            job = new WorkflowJob(parent, jobName);
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
                            if (updatedBCResourceVersion <= oldBCResourceVersion && newProperty.getUid().equals(buildConfigProjectProperty.getUid()) && newProperty.getNamespace().equals(buildConfigProjectProperty.getNamespace())
                                    && newProperty.getName().equals(buildConfigProjectProperty.getName()) && newProperty.getBuildRunPolicy().equals(buildConfigProjectProperty.getBuildRunPolicy())) {
                                return null;
                            }
                            buildConfigProjectProperty.setUid(newProperty.getUid());
                            buildConfigProjectProperty.setNamespace(newProperty.getNamespace());
                            buildConfigProjectProperty.setName(newProperty.getName());
                            buildConfigProjectProperty.setResourceVersion(newProperty.getResourceVersion());
                            buildConfigProjectProperty.setBuildRunPolicy(newProperty.getBuildRunPolicy());
                        } else {
                            job.addProperty(new BuildConfigProjectProperty(buildConfig));
                        }

                        // (re)populate job param list with any envs
                        // from the build config
                        Map<String, ParameterDefinition> paramMap = JenkinsUtils.addJobParamForBuildEnvs(job, buildConfig.getSpec().getStrategy().getJenkinsPipelineStrategy(), true);

                        job.setConcurrentBuild(!(buildConfig.getSpec().getRunPolicy().equals(SERIAL) || buildConfig.getSpec().getRunPolicy().equals(SERIAL_LATEST_ONLY)));

                        InputStream jobStream = new StringInputStream(new XStream2().toXML(job));

                        if (newJob) {
                            try {
                                if (parent instanceof Folder) {
                                    Folder folder = (Folder) parent;
                                    folder.createProjectFromXML(jobName, jobStream).save();
                                } else {
                                    activeInstance.createProjectFromXML(jobName, jobStream).save();
                                }

                                logger.info("Created job " + jobName + " from BuildConfig " + NamespaceName.create(buildConfig) + " with revision: " + buildConfig.getMetadata().getResourceVersion());
                            } catch (IllegalArgumentException e) {
                                // see
                                // https://github.com/openshift/jenkins-sync-plugin/issues/117,
                                // jenkins might reload existing jobs on
                                // startup between the
                                // newJob check above and when we make
                                // the createProjectFromXML call; if so,
                                // retry as an update
                                updateJob(job, jobStream, jobName, buildConfig, existingBuildRunPolicy, buildConfigProjectProperty);
                            }
                        } else {
                            updateJob(job, jobStream, jobName, buildConfig, existingBuildRunPolicy, buildConfigProjectProperty);
                        }
                        bk.commit();
                        String fullName = job.getFullName();
                        WorkflowJob workflowJob = activeInstance.getItemByFullName(fullName, WorkflowJob.class);
                        if (workflowJob == null && parent instanceof Folder) {
                            // we should never need this but just in
                            // case there's an
                            // odd timing issue or something...
                            Folder folder = (Folder) parent;
                            folder.add(job, jobName);
                            workflowJob = activeInstance.getItemByFullName(fullName, WorkflowJob.class);

                        }
                        if (workflowJob == null) {
                            logger.warning("Could not find created job " + fullName + " for BuildConfig: " + getNamespace(buildConfig) + "/" + getName(buildConfig));
                        } else {
                            JenkinsUtils.verifyEnvVars(paramMap, workflowJob);
                            putJobWithBuildConfig(workflowJob, buildConfig);
                        }
                        return null;
                    }
                });
            }
        }
    }

    private synchronized void modifyEventToJenkinsJob(BuildConfig buildConfig) throws Exception {
        if (isPipelineStrategyBuildConfig(buildConfig)) {
            upsertJob(buildConfig);
            return;
        }

        // no longer a Jenkins build so lets delete it if it exists
        deleteEventToJenkinsJob(buildConfig);
    }

    // innerDeleteEventToJenkinsJob is the actual delete logic at the heart of
    // deleteEventToJenkinsJob
    // that is either in a sync block or not based on the presence of a BC uid
    private void innerDeleteEventToJenkinsJob(final BuildConfig buildConfig) throws Exception {
        final Job job = getJobFromBuildConfig(buildConfig);
        if (job != null) {
            // employ intern of the BC UID to facilitate sync'ing on the same
            // actual object
            synchronized (buildConfig.getMetadata().getUid().intern()) {
                ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
                    @Override
                    public Void call() throws Exception {
                        try {
                            deleteInProgress(buildConfig.getMetadata().getNamespace() + buildConfig.getMetadata().getName());
                            job.delete();
                        } finally {
                            removeJobWithBuildConfig(buildConfig);
                            Jenkins.getActiveInstance().rebuildDependencyGraphAsync();
                            deleteCompleted(buildConfig.getMetadata().getNamespace() + buildConfig.getMetadata().getName());
                        }
                        return null;
                    }
                });
                // if the bc has a source secret it is possible it should
                // be deleted as well (called function will cross reference
                // with secret watch)
                CredentialsUtils.deleteSourceCredentials(buildConfig);
            }

        }

    }

    // in response to receiving an openshift delete build config event, this
    // method will drive
    // the clean up of the Jenkins job the build config is mapped one to one
    // with; as part of that
    // clean up it will synchronize with the build event watcher to handle build
    // config
    // delete events and build delete events that arrive concurrently and in a
    // nondeterministic
    // order
    private synchronized void deleteEventToJenkinsJob(final BuildConfig buildConfig) throws Exception {
        String bcUid = buildConfig.getMetadata().getUid();
        if (bcUid != null && bcUid.length() > 0) {
            // employ intern of the BC UID to facilitate sync'ing on the same
            // actual object
            bcUid = bcUid.intern();
            synchronized (bcUid) {
                innerDeleteEventToJenkinsJob(buildConfig);
                return;
            }
        }
        // uid should not be null / empty, but just in case, still clean up
        innerDeleteEventToJenkinsJob(buildConfig);
    }
}
