package io.fabric8.jenkins.openshiftsync;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.openshift.api.model.BuildConfig;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class BuildConfigToJobMap {

    private static Map<String, WorkflowJob> buildConfigToJobMap;

    private BuildConfigToJobMap() {
    }

    static synchronized void initializeBuildConfigToJobMap() {
        if (buildConfigToJobMap == null) {
            List<WorkflowJob> jobs = Jenkins.getActiveInstance().getAllItems(
                    WorkflowJob.class);
            buildConfigToJobMap = new ConcurrentHashMap<>(jobs.size());
            for (WorkflowJob job : jobs) {
                BuildConfigProjectProperty buildConfigProjectProperty = job
                        .getProperty(BuildConfigProjectProperty.class);
                if (buildConfigProjectProperty == null) {
                    continue;
                }
                String bcUid = buildConfigProjectProperty.getUid();
                if (isNotBlank(bcUid)) {
                    buildConfigToJobMap.put(bcUid, job);
                }
            }
        }
    }

    static synchronized WorkflowJob getJobFromBuildConfig(
            BuildConfig buildConfig) {
        ObjectMeta meta = buildConfig.getMetadata();
        if (meta == null) {
            return null;
        }
        return getJobFromBuildConfigUid(meta.getUid());
    }

    static synchronized WorkflowJob getJobFromBuildConfigUid(String uid) {
        if (isBlank(uid)) {
            return null;
        }
        return buildConfigToJobMap.get(uid);
    }

    static synchronized void putJobWithBuildConfig(WorkflowJob job,
            BuildConfig buildConfig) {
        if (buildConfig == null) {
            throw new IllegalArgumentException("BuildConfig cannot be null");
        }
        if (job == null) {
            throw new IllegalArgumentException("Job cannot be null");
        }
        ObjectMeta meta = buildConfig.getMetadata();
        if (meta == null) {
            throw new IllegalArgumentException(
                    "BuildConfig must contain valid metadata");
        }
        putJobWithBuildConfigUid(job, meta.getUid());
    }

    static synchronized void putJobWithBuildConfigUid(WorkflowJob job,
            String uid) {
        if (isBlank(uid)) {
            throw new IllegalArgumentException(
                    "BuildConfig uid must not be blank");
        }
        buildConfigToJobMap.put(uid, job);
    }

    static synchronized void removeJobWithBuildConfig(BuildConfig buildConfig) {
        if (buildConfig == null) {
            throw new IllegalArgumentException("BuildConfig cannot be null");
        }
        ObjectMeta meta = buildConfig.getMetadata();
        if (meta == null) {
            throw new IllegalArgumentException(
                    "BuildConfig must contain valid metadata");
        }
        removeJobWithBuildConfigUid(meta.getUid());
    }

    static synchronized void removeJobWithBuildConfigUid(String uid) {
        if (isBlank(uid)) {
            throw new IllegalArgumentException(
                    "BuildConfig uid must not be blank");
        }
        buildConfigToJobMap.remove(uid);
    }

}
