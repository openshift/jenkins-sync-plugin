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

import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.getJobFromBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.removeJobWithBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildPhases.NEW;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_BUILD_STATUS_FIELD;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_LABELS_BUILD_CONFIG_NAME;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getInformerFactory;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isPipelineStrategyBuildConfig;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import hudson.model.Job;
import hudson.security.ACL;
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildList;
import io.fabric8.openshift.client.OpenShiftClient;
import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;
import jenkins.util.Timer;

/**
 * Watches {@link BuildConfig} objects in OpenShift and for WorkflowJobs we
 * ensure there is a suitable Jenkins Job object defined with the correct
 * configuration
 */
public class BuildConfigInformer extends BuildConfigWatcher implements ResourceEventHandler<BuildConfig> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecretInformer.class.getName());
    private SharedIndexInformer<BuildConfig> informer;

    public BuildConfigInformer(String namespace) {
        super(namespace);
    }

    @Override
    public int getListIntervalInSeconds() {
        return 1_000 * GlobalPluginConfiguration.get().getBuildConfigListInterval();
    }

    public void start() {
        LOGGER.info("Starting BuildConfig informer for {} !!" + namespace);
        LOGGER.debug("listing BuildConfig resources");
        SharedInformerFactory factory = getInformerFactory().inNamespace(namespace);
        this.informer = factory.sharedIndexInformerFor(BuildConfig.class, getListIntervalInSeconds());
        informer.addEventHandler(this);
        LOGGER.info("BuildConfig informer started for namespace: {}" + namespace);
        // BuildConfigList list =
        // getOpenshiftClient().buildConfigs().inNamespace(namespace).list();
        // onInit(list.getItems());
    }

    public void stop() {
        LOGGER.info("Stopping secret informer {} !!" + namespace);
        this.informer.stop();
    }

    @Override
    public void onAdd(BuildConfig obj) {
        LOGGER.debug("BuildConfig informer  received add event for: {}" + obj);
        if (obj != null) {
            ObjectMeta metadata = obj.getMetadata();
            String name = metadata.getName();
            LOGGER.info("BuildConfig informer received add event for: {}" + name);
            upsertJob(obj);
        }
    }

    @Override
    public void onUpdate(BuildConfig oldObj, BuildConfig newObj) {
        LOGGER.debug("BuildConfig informer received update event for: {} to: {}" + oldObj + " " + newObj);
        if (newObj != null) {
            String oldRv = oldObj.getMetadata().getResourceVersion();
            String newRv = newObj.getMetadata().getResourceVersion();
            LOGGER.info("BuildConfig informer received update event for: {} to: {}" + oldRv + " " + newRv);
            modifyEventToJenkinsJob(newObj);
        }
    }

    @Override
    public void onDelete(BuildConfig obj, boolean deletedFinalStateUnknown) {
        LOGGER.info("BuildConfig informer received delete event for: {}" + obj);
        if (obj != null) {
            deleteEventToJenkinsJob(obj);
        }
    }

    @SuppressWarnings({ "deprecation", "serial" })
    private void cleanupJobsMissingStartBuildEvent(BuildConfig buildConfig) throws Exception {
        boolean buildConfigNameNotNull = buildConfig != null && buildConfig.getMetadata() != null;
        String name = buildConfigNameNotNull ? buildConfig.getMetadata().getName() : "null";
        // we employ impersonation here to insure we have "full access";
        // for example, can we actually
        // read in jobs defs for verification? without impersonation here
        // we would get null back when trying to read in the job from disk
        ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
            @Override
            public Void call() throws Exception {
                // if bc event came after build events, let's poke the BuildWatcher builds with
                // no BC list to create job runs
                BuildWatcher.flushBuildsWithNoBCList();
                // now, if the build event was lost and never received, builds will stay in new
                // for 5 minutes ...
                // let's launch a background thread to clean them up at a quicker interval than
                // the default 5 minute general build
                // relist function
                Runnable backupBuildQuery = new SafeTimerTask() {
                    @Override
                    public void doRun() {
                        if (!CredentialsUtils.hasCredentials()) {
                            LOGGER.debug("No Openshift Token credential defined.");
                            return;
                        }
                        final OpenShiftClient client = getAuthenticatedOpenShiftClient();
                        BuildList buildList = client.builds().inNamespace(namespace)
                                .withField(OPENSHIFT_BUILD_STATUS_FIELD, NEW)
                                .withLabel(OPENSHIFT_LABELS_BUILD_CONFIG_NAME, name).list();
                        if (buildList.getItems().size() > 0) {
                            LOGGER.info("build backup query for " + name + " found new builds");
                            BuildWatcher.onInitialBuilds(buildList);
                        }
                    }
                };
                Timer.get().schedule(backupBuildQuery, 10 * 1000, MILLISECONDS);
                return null;
            }
        });
    }

    @SuppressWarnings({ "deprecation" })
    private void upsertJob(final BuildConfig buildConfig) {
        if (isPipelineStrategyBuildConfig(buildConfig)) {
            // sync on intern of name should guarantee sync on same actual obj
            synchronized (buildConfig.getMetadata().getUid().intern()) {
                try {
                    ACL.impersonate(ACL.SYSTEM, new JobProcessor(this, buildConfig));
                } catch (Exception e) {
                    LOGGER.error("Error while trying to insert JobRun: " + e);

                }
            }
        }
        try {
            cleanupJobsMissingStartBuildEvent(buildConfig);
        } catch (Exception e) {
            LOGGER.error("Error while trying to clean up orphan JobRuns: " + e);
            e.printStackTrace();
        }
    }

    private void modifyEventToJenkinsJob(BuildConfig buildConfig) {
        if (isPipelineStrategyBuildConfig(buildConfig)) {
            upsertJob(buildConfig);
            return;
        }

        // no longer a Jenkins build so lets delete it if it exists
        deleteEventToJenkinsJob(buildConfig);
    }

    // innerDeleteEventToJenkinsJob is the actual delete logic at the heart of
    // deleteEventToJenkinsJob that is either in a sync block or not based on the
    // presence of a BC uid
    @SuppressWarnings({ "deprecation", "serial" })
    private void innerDeleteEventToJenkinsJob(final BuildConfig buildConfig) throws Exception {
        Job<?, ?> job = getJobFromBuildConfig(buildConfig);
        if (job != null) {
            // employ intern of the BC UID to facilitate sync'ing on the same
            // actual object
            synchronized (buildConfig.getMetadata().getUid().intern()) {
                ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
                    @Override
                    public Void call() throws Exception {
                        try {
                            deleteInProgress(
                                    buildConfig.getMetadata().getNamespace() + buildConfig.getMetadata().getName());
                            job.delete();
                        } finally {
                            removeJobWithBuildConfig(buildConfig);
                            Jenkins.getActiveInstance().rebuildDependencyGraphAsync();
                            deleteCompleted(
                                    buildConfig.getMetadata().getNamespace() + buildConfig.getMetadata().getName());
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

    private void onInit(List<BuildConfig> list) {
        for (BuildConfig buildConfig : list) {
            try {
                upsertJob(buildConfig);
            } catch (Exception e) {
                LOGGER.error("Failed to update job", e);
            }
        }
        // poke the BuildWatcher builds with no BC list and see if we
        // can create job
        // runs for premature builds
        BuildWatcher.flushBuildsWithNoBCList();
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
    private void deleteEventToJenkinsJob(final BuildConfig buildConfig) {
        if (buildConfig != null) {
            String bcUid = buildConfig.getMetadata().getUid();
            if (bcUid != null && bcUid.length() > 0) {
                // employ intern of the BC UID to facilitate sync'ing on the same
                // actual object
                bcUid = bcUid.intern();
                synchronized (bcUid) {
                    try {
                        innerDeleteEventToJenkinsJob(buildConfig);
                    } catch (Exception e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                    return;
                }
            }
            // uid should not be null / empty, but just in case, still clean up
            try {
                innerDeleteEventToJenkinsJob(buildConfig);
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }
}
