package io.fabric8.jenkins.openshiftsync;

import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.getJobFromBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.removeJobWithBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.removeJobWithBuildConfigNameNamespace;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isPipelineStrategyBuildConfig;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jetty.util.ConcurrentHashSet;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.util.logging.Logger;
import java.util.List;
import java.util.logging.Level;

import hudson.model.Job;
import hudson.security.ACL;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.client.OpenShiftClient;
import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;

public class BuildConfigManager {
    private static final Logger logger = Logger.getLogger(BuildConfigManager.class.getName());
    
    static {
        reconcileJobsAndBuildConfigs();
    }
    
    /**
     * for coordinating between ItemListener.onUpdate and onDeleted both getting
     * called when we delete a job; ID should be combo of namespace and name for BC
     * to properly differentiate; we don't use UUID since when we filter on the
     * ItemListener side the UUID may not be available
     **/
    private static final ConcurrentHashSet<String> deletesInProgress = new ConcurrentHashSet<String>();

    public static boolean isDeleteInProgress(String bcID) {
        return deletesInProgress.contains(bcID);
    }

    public static void deleteCompleted(String bcID) {
        deletesInProgress.remove(bcID);
    }

    public static void deleteInProgress(String bcName) {
        deletesInProgress.add(bcName);
    }

    static void modifyEventToJenkinsJob(BuildConfig buildConfig) throws Exception {
        if (isPipelineStrategyBuildConfig(buildConfig)) {
            upsertJob(buildConfig);
            return;
        }

        // no longer a Jenkins build so lets delete it if it exists
        deleteEventToJenkinsJob(buildConfig);
    }

    static void upsertJob(final BuildConfig buildConfig) throws Exception {
        if (isPipelineStrategyBuildConfig(buildConfig)) {
            // sync on intern of name should guarantee sync on same actual obj
            synchronized (buildConfig.getMetadata().getUid().intern()) {
                ACL.impersonate(ACL.SYSTEM, new JobProcessor(buildConfig));
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
    static void deleteEventToJenkinsJob(final BuildConfig buildConfig) throws Exception {
        if (buildConfig != null) {
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

    // innerDeleteEventToJenkinsJob is the actual delete logic at the heart of
    // deleteEventToJenkinsJob
    // that is either in a sync block or not based on the presence of a BC uid
    private static void innerDeleteEventToJenkinsJob(final BuildConfig buildConfig) throws Exception {
        final Job job = getJobFromBuildConfig(buildConfig);
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
    
    static void reconcileJobsAndBuildConfigs() {
        logger.info("Reconciling jobs and build configs");
        List<WorkflowJob> jobs = Jenkins.getActiveInstance().getAllItems(WorkflowJob.class);
        for (WorkflowJob job : jobs) {
            BuildConfigProjectProperty property = job.getProperty(BuildConfigProjectProperty.class);
            if (property != null) {
                String ns = property.getNamespace();
                String name = property.getName();
                if (StringUtils.isNotBlank(ns) && StringUtils.isNotBlank(name)) {
                    logger.info("Checking job " + job + " runs for BuildConfig " + ns + "/" + name);
                    OpenShiftClient client = getAuthenticatedOpenShiftClient();
                    BuildConfig bc = client.buildConfigs().inNamespace(ns).withName(name).get();
                    if (bc == null) {
                        try {
                            ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
                                @Override
                                public Void call() throws Exception {
                                    try {
                                        deleteInProgress(ns + name);
                                        job.delete();
                                    } finally {
                                        removeJobWithBuildConfigNameNamespace(name, ns);
                                        Jenkins.getActiveInstance().rebuildDependencyGraphAsync();
                                        deleteCompleted(ns + name);
                                    }
                                    return null;
                                }
                            });
                        } catch (Exception e) {
                            // we do not throw error, so as to continue on to next BC; any exception thrown
                            // here will be of a flavor on the jenkins end of things that we cannot immeidately 
                            // recover from
                            logger.log(Level.INFO, "reconcileJobsAndBuildConfigs", e);
                        }
                       
                    }
                }
            }
        }
    }

}
