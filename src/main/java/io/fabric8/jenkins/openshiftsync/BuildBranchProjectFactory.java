package io.fabric8.jenkins.openshiftsync;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.SubmoduleConfig;
import hudson.plugins.git.UserRemoteConfig;
import hudson.plugins.git.extensions.GitSCMExtension;
import io.fabric8.openshift.api.model.*;
import jenkins.scm.api.SCMProbeStat;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;;
import org.jenkinsci.plugins.workflow.multibranch.AbstractWorkflowBranchProjectFactory;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowBranchProjectFactory;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.CredentialsUtils.updateSourceCredentials;
import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class BuildBranchProjectFactory extends AbstractWorkflowBranchProjectFactory {

  static final String SCRIPT = "Jenkinsfile";
  static private String scriptPath = SCRIPT;

  public Object readResolve() {
    if (this.scriptPath == null) {
      this.scriptPath = BuildBranchProjectFactory.SCRIPT;
    }
    return this;
  }

  @DataBoundConstructor
  public BuildBranchProjectFactory() {
  }

  public void setScriptPath(String jenkinsfilePath) {
      if (jenkinsfilePath != null) {
        scriptPath = jenkinsfilePath;
      } else {
        scriptPath = SCRIPT;
      }
  }

  public String getScriptPath() {
    return scriptPath;
  }

  @Override
  protected FlowDefinition createDefinition() {
    return new BuildSCMBinder(scriptPath);
  }

  @Override
  protected SCMSourceCriteria getSCMSourceCriteria(SCMSource source) {
    return new SCMSourceCriteria() {
      @Override
      public boolean isHead(SCMSourceCriteria.Probe probe, TaskListener listener) throws IOException {
        SCMProbeStat stat = probe.stat(scriptPath);
        switch (stat.getType()) {
          case NONEXISTENT:
            if (stat.getAlternativePath() != null) {
              listener.getLogger().format("      ‘%s’ not found (but found ‘%s’, search is case sensitive)%n", scriptPath, stat.getAlternativePath());
            } else {
              listener.getLogger().format("      ‘%s’ not found%n", scriptPath);
            }
            return false;
          case DIRECTORY:
            listener.getLogger().format("      ‘%s’ found but is a directory not a file%n", scriptPath);
            return false;
          default:
            listener.getLogger().format("      ‘%s’ found%n", scriptPath);
            return true;

        }
      }

      @Override
      public int hashCode() {
        return getClass().hashCode();
      }

      @Override
      public boolean equals(Object obj) {
        return getClass().isInstance(obj);
      }
    };
  }

  @Extension
  public static class DescriptorImpl extends AbstractWorkflowBranchProjectFactoryDescriptor {

    @Override
    public String getDisplayName() {
      return "by Jenkinsfile (OpenShift)";
    }

  }
}
