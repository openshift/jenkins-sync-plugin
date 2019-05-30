package io.fabric8.jenkins.openshiftsync;

import hudson.Extension;
import hudson.model.*;
import hudson.scm.SCMDescriptor;
import jenkins.branch.MultiBranchProjectDescriptor;
import org.jenkins.ui.icon.Icon;
import org.jenkins.ui.icon.IconSet;
import org.jenkins.ui.icon.IconSpec;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.Messages;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

import java.util.logging.Level;
import java.util.logging.Logger;

public class BuildConfigToMultiBranchMapper extends WorkflowMultiBranchProject {

  private static final Logger LOGGER = Logger.getLogger(BuildConfigToMultiBranchMapper.class.getName());


  public BuildConfigToMultiBranchMapper(ItemGroup parent, String name) {
    super(parent, name);
  }

  @Override
  protected BuildBranchProjectFactory newProjectFactory() {
    return new BuildBranchProjectFactory();
  }

  @Extension
  public static class DescriptorImpl extends MultiBranchProjectDescriptor implements IconSpec {
    public DescriptorImpl() {
    }

    public String getDisplayName() {
      return org.jenkinsci.plugins.workflow.multibranch.Messages.WorkflowMultiBranchProject_DisplayName();
    }

    public String getDescription() {
      return Messages.WorkflowMultiBranchProject_Description();
    }

    public String getIconFilePathPattern() {
      return "plugin/workflow-multibranch/images/:size/pipelinemultibranchproject.png";
    }

    public String getIconClassName() {
      return "icon-pipeline-multibranch-project";
    }

    public TopLevelItem newInstance(ItemGroup parent, String name) {
      return new BuildConfigToMultiBranchMapper(parent, name);
    }

    public boolean isApplicable(Descriptor descriptor) {
      if (descriptor instanceof SCMDescriptor) {
        SCMDescriptor d = (SCMDescriptor)descriptor;

        try {
          if (!d.isApplicable(new WorkflowJob((ItemGroup)null, (String)null))) {
            return false;
          }
        } catch (RuntimeException var4) {
          BuildConfigToMultiBranchMapper.LOGGER.log(Level.FINE, "SCMDescriptor.isApplicable hack failed", var4);
        }
      }

      return super.isApplicable(descriptor);
    }

    static {
      IconSet.icons.addIcon(new Icon("icon-pipeline-multibranch-project icon-sm", "plugin/workflow-multibranch/images/16x16/pipelinemultibranchproject.png", "width: 16px; height: 16px;"));
      IconSet.icons.addIcon(new Icon("icon-pipeline-multibranch-project icon-md", "plugin/workflow-multibranch/images/24x24/pipelinemultibranchproject.png", "width: 24px; height: 24px;"));
      IconSet.icons.addIcon(new Icon("icon-pipeline-multibranch-project icon-lg", "plugin/workflow-multibranch/images/32x32/pipelinemultibranchproject.png", "width: 32px; height: 32px;"));
      IconSet.icons.addIcon(new Icon("icon-pipeline-multibranch-project icon-xlg", "plugin/workflow-multibranch/images/48x48/pipelinemultibranchproject.png", "width: 48px; height: 48px;"));
    }
  }


}
