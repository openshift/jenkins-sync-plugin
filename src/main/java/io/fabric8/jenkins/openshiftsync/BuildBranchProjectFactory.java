package io.fabric8.jenkins.openshiftsync;

import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.SubmoduleConfig;
import hudson.plugins.git.UserRemoteConfig;
import hudson.plugins.git.extensions.GitSCMExtension;
import io.fabric8.openshift.api.model.*;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowBranchProjectFactory;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.CredentialsUtils.updateSourceCredentials;
import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class BuildConfigToBranchMap extends WorkflowBranchProjectFactory {

  public static final String JENKINS_PIPELINE_BUILD_STRATEGY = "JenkinsPipeline";
  public static final String DEFAULT_JENKINS_FILEPATH = "Jenkinsfile";
  private static final Logger LOGGER = Logger.getLogger(BuildConfigToJobMapper.class.getName());

  public BuildConfigToBranchMap() {
    super();
  }

  protected BuildSCMBinder createDefinition(BuildConfig buildconfig) throws IOException {
    if (!OpenShiftUtils.isPipelineStrategyBuildConfig(buildconfig)) {
      return null;
    }

    BuildConfigSpec spec = buildconfig.getSpec();
    BuildSource source = null;
    String jenkinsfile = null;
    String jenkinsfilePath = null;
    if (spec != null) {
      source = spec.getSource();
      BuildStrategy strategy = spec.getStrategy();
      if (strategy != null) {
        JenkinsPipelineBuildStrategy jenkinsPipelineStrategy = strategy.getJenkinsPipelineStrategy();
        if (jenkinsPipelineStrategy != null) {
          jenkinsfile = jenkinsPipelineStrategy.getJenkinsfile();
          jenkinsfilePath = jenkinsPipelineStrategy.getJenkinsfilePath();
        }
      }
    }
    if (jenkinsfile == null) {
      // Is this a Jenkinsfile from Git SCM?
//      if (source != null && source.getGit() != null && source.getGit().getUri() != null) {
        if (jenkinsfilePath == null) {
          jenkinsfilePath = DEFAULT_JENKINS_FILEPATH;
        }
//        if (!isEmpty(source.getContextDir())) {
//          jenkinsfilePath = new File(source.getContextDir(), jenkinsfilePath).getPath();
//        }
//        GitBuildSource gitSource = source.getGit();
//        String branchRef = gitSource.getRef();
//        List<BranchSpec> branchSpecs = Collections.emptyList();
//        if (isNotBlank(branchRef)) {
//          branchSpecs = Collections.singletonList(new BranchSpec(branchRef));
//        }
//        String credentialsId = updateSourceCredentials(buildconfig);
//        // if credentialsID is null, go with an SCM where anonymous has to be sufficient
//        GitSCM scm = new GitSCM(
//          Collections.singletonList(new UserRemoteConfig(gitSource.getUri(), null, null, credentialsId)),
//          branchSpecs, false, Collections.<SubmoduleConfig>emptyList(), null, null,
//          Collections.<GitSCMExtension>emptyList());
        return new BuildSCMBinder(jenkinsfile);
//      } else {
//        LOGGER.warning("BuildConfig does not contain source repository information - "
//          + "cannot map BuildConfig to Jenkins job");
//        return null;
//      }
    } else {
      return new BuildSCMBinder(jenkinsfile);
    }
  }
}
