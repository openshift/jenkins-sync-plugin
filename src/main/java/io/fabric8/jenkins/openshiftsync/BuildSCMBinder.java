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
import hudson.Extension;
import hudson.model.Action;
import hudson.model.ItemGroup;
import hudson.model.TaskListener;
import hudson.model.Queue.Executable;
import hudson.scm.SCM;
import java.util.List;
import jenkins.branch.Branch;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMRevisionAction;
import jenkins.scm.api.SCMSource;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDefinitionDescriptor;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

// Copied the SCMBinder class for usage as the class had no Access Modifier
public class BuildSCMBinder extends FlowDefinition {

  public static final String DEFAULT_JENKINS_FILEPATH = "Jenkinsfile";
  private String jenkinsFilePath;

  BuildSCMBinder(String jenkinsFilePath) {
    this.jenkinsFilePath = jenkinsFilePath;
  }

  public FlowExecution create(FlowExecutionOwner handle, TaskListener listener, List<? extends Action> actions) throws Exception {
    Executable exec = handle.getExecutable();

    if (jenkinsFilePath.isEmpty() || jenkinsFilePath == null){
      this.jenkinsFilePath = DEFAULT_JENKINS_FILEPATH;
    }

    if (!(exec instanceof WorkflowRun)) {
      throw new IllegalStateException("inappropriate context");
    } else {
      WorkflowRun build = (WorkflowRun)exec;
      WorkflowJob job = (WorkflowJob)build.getParent();
      BranchJobProperty property = (BranchJobProperty)job.getProperty(BranchJobProperty.class);
      if (property == null) {
        throw new IllegalStateException("inappropriate context");
      } else {
        Branch branch = property.getBranch();
        ItemGroup<?> parent = job.getParent();
        if (!(parent instanceof WorkflowMultiBranchProject)) {
          throw new IllegalStateException("inappropriate context");
        } else {
          SCMSource scmSource = ((WorkflowMultiBranchProject)parent).getSCMSource(branch.getSourceId());
          if (scmSource == null) {
            throw new IllegalStateException(branch.getSourceId() + " not found");
          } else {
            SCMHead head = branch.getHead();
            SCMRevision tip = scmSource.fetch(head, listener);
            SCM scm;
            if (tip != null) {
              scm = scmSource.build(head, scmSource.getTrustedRevision(tip, listener));
              build.addAction(new SCMRevisionAction(tip));
            } else {
              listener.error("Could not determine exact tip revision of " + branch.getName() + "; falling back to nondeterministic checkout");
              scm = branch.getScm();
            }

            return (new CpsScmFlowDefinition(scm, jenkinsFilePath)).create(handle, listener, actions);
          }
        }
      }
    }
  }


  @Extension
  public static class DescriptorImpl extends FlowDefinitionDescriptor {
    public DescriptorImpl() {
    }

    public String getDisplayName() {
      return "Pipeline script from an OpenShift Pipeline Strategy Build";
    }
  }
}