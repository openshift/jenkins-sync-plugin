package io.fabric8.jenkins.openshiftsync;

import hudson.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.Annotations.ENABLE_MULTIBRANCH_SYNC;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAnnotation;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class BuildConfigToMultibranchJobsMap {


  private final static Logger logger = Logger.getLogger(BuildConfigToJobMap.class.getName());
  private static ConcurrentHashMap<String, ArrayList<WorkflowJob>> buildConfigToMultibranchJobsMap;

  private BuildConfigToMultibranchJobsMap() {
  }

  static synchronized void initializeBuildConfigToMultibranchJobsMap() {
    if (buildConfigToMultibranchJobsMap == null) {
      List<WorkflowJob> jobs = Jenkins.getActiveInstance().getAllItems(WorkflowJob.class);
      buildConfigToMultibranchJobsMap = new ConcurrentHashMap<>();
      for (WorkflowJob job : jobs) {
        BuildConfigProjectProperty buildConfigProjectProperty = job.getProperty(BuildConfigProjectProperty.class);
        if (buildConfigProjectProperty == null) {
          continue;
        }
        String namespace = buildConfigProjectProperty.getNamespace();
        String buildConfigName = buildConfigProjectProperty.getName();
        Boolean enableMultibranchSync = Boolean.valueOf(getAnnotation(buildConfigProjectProperty.getBuildConfig(), ENABLE_MULTIBRANCH_SYNC));
        if (enableMultibranchSync) {
          if (isNotBlank(namespace) && isNotBlank(buildConfigName)) {
            ArrayList<WorkflowJob> jobsList =  buildConfigToMultibranchJobsMap.get(OpenShiftUtils.jenkinsJobName(namespace, buildConfigName));
            if (jobsList == null){
              jobsList = new ArrayList<WorkflowJob>();
            }
            jobsList.add(job);

            buildConfigToMultibranchJobsMap.put(OpenShiftUtils.jenkinsJobName(namespace, buildConfigName), jobsList);
          }
        }

      }
    }
  }

}
