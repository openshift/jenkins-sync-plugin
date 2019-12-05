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

import static io.fabric8.jenkins.openshiftsync.CredentialsUtils.updateSourceCredentials;
import static java.util.logging.Level.INFO;
import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.apache.commons.lang.StringUtils.isNotBlank;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;

import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.SubmoduleConfig;
import hudson.plugins.git.UserRemoteConfig;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.scm.SCM;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigSpec;
import io.fabric8.openshift.api.model.BuildSource;
import io.fabric8.openshift.api.model.BuildStrategy;
import io.fabric8.openshift.api.model.GitBuildSource;
import io.fabric8.openshift.api.model.JenkinsPipelineBuildStrategy;
import jenkins.branch.Branch;

public class BuildConfigToJobMapper {
    public static final String JENKINS_PIPELINE_BUILD_STRATEGY = "JenkinsPipeline";
    public static final String DEFAULT_JENKINS_FILEPATH = "Jenkinsfile";
    private static final Logger LOGGER = Logger.getLogger(BuildConfigToJobMapper.class.getName());
    private static final String GIT_SCM_TYPE = "Git";

    /**
     * @param bc A BuildConfig object.
     * @return the FlowDefinition representing a Jenkins Build built from a
     *         pipeline.
     */
    public static FlowDefinition mapBuildConfigToFlow(BuildConfig bc) throws IOException {
        if (!OpenShiftUtils.isPipelineStrategyBuildConfig(bc)) {
            return null;
        }

        BuildSource source = null;
        String jenkinsfile = null;
        String jenkinsfilePath = null;
        BuildConfigSpec spec = bc.getSpec();
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
            if (source != null && source.getGit() != null && source.getGit().getUri() != null) {
                if (jenkinsfilePath == null) {
                    jenkinsfilePath = DEFAULT_JENKINS_FILEPATH;
                }
                if (!isEmpty(source.getContextDir())) {
                    jenkinsfilePath = new File(source.getContextDir(), jenkinsfilePath).getPath();
                }
                GitBuildSource gitSource = source.getGit();
                String branchRef = gitSource.getRef();
                List<BranchSpec> branchSpecs = Collections.emptyList();
                if (isNotBlank(branchRef)) {
                    branchSpecs = Collections.singletonList(new BranchSpec(branchRef));
                }
                String credentialsId = updateSourceCredentials(bc);
                // if credentialsID is null, go with an SCM where anonymous has to be sufficient
                UserRemoteConfig remoteConfig = new UserRemoteConfig(gitSource.getUri(), null, null, credentialsId);
                List<UserRemoteConfig> userRemoteConfigs = Collections.singletonList(remoteConfig);
                List<SubmoduleConfig> submoduleCfg = Collections.<SubmoduleConfig>emptyList();
                List<GitSCMExtension> extensions = Collections.<GitSCMExtension>emptyList();
                GitSCM scm = new GitSCM(userRemoteConfigs, branchSpecs, false, submoduleCfg, null, null, extensions);
                return new CpsScmFlowDefinition(scm, jenkinsfilePath);
            } else {
                LOGGER.warning("BuildConfig does not contain source repository: cannot map BuildConfig to Jenkins job");
                return null;
            }
        } else {
            return new CpsFlowDefinition(jenkinsfile, true);
        }
    }

    /**
     * Updates the {@link BuildConfig} if the Jenkins {@link WorkflowJob} changes
     *
     * @param job         the job thats been updated via Jenkins
     * @param buildConfig the OpenShift BuildConfig to update
     * @return true if the BuildConfig was changed This will be decided if the
     *         Definition in the Job is of type CpsFlowDefinition or
     *         CpsScmFlowDefinition
     *
     */
    public static boolean updateBuildConfigFromJob(WorkflowJob job, BuildConfig buildConfig) {
        NamespaceName namespaceName = NamespaceName.create(buildConfig);
        JenkinsPipelineBuildStrategy jenkinsPipelineStrategy = null;
        BuildConfigSpec spec = buildConfig.getSpec();
        if (spec != null) {
            BuildStrategy strategy = spec.getStrategy();
            if (strategy != null) {
                jenkinsPipelineStrategy = strategy.getJenkinsPipelineStrategy();
            }
        }

        if (jenkinsPipelineStrategy == null) {
            LOGGER.warning("No jenkinsPipelineStrategy available in the BuildConfig " + namespaceName);
            return false;
        }

        FlowDefinition definition = job.getDefinition();
        if (definition instanceof CpsScmFlowDefinition) {
            return updateScmFlowDefinition(buildConfig, namespaceName, jenkinsPipelineStrategy, definition);
        }

        if (definition instanceof CpsFlowDefinition) {
            return updateCpsFlowDefinition(namespaceName, jenkinsPipelineStrategy, definition);
        }

        return updateBranchName(job, buildConfig, namespaceName, jenkinsPipelineStrategy, definition);
    }

    private static boolean updateBranchName(WorkflowJob job, BuildConfig buildConfig, NamespaceName namespaceName,
            JenkinsPipelineBuildStrategy jenkinsPipelineStrategy, FlowDefinition definition) {
        // support multi-branch or github organization jobs
        BranchJobProperty property = job.getProperty(BranchJobProperty.class);
        if (property != null) {
            Branch branch = property.getBranch();
            if (branch != null) {
                String ref = branch.getName();
                SCM scm = branch.getScm();
                BuildConfigSpec spec = buildConfig.getSpec();
                BuildSource source = getOrCreateBuildSource(spec);
                buildConfig.getSpec().getSource().getGit().setRef(ref);
                if (scm instanceof GitSCM) {
                    if (populateFromGitSCM(buildConfig, source, (GitSCM) scm, ref)) {
                        if (StringUtils.isEmpty(jenkinsPipelineStrategy.getJenkinsfilePath())) {
                            jenkinsPipelineStrategy.setJenkinsfilePath("Jenkinsfile");
                        }
                        return true;
                    }
                }
            }
        }

        String clazz = (definition == null) ? "null" : definition.getClass().getName();
        LOGGER.warning("Cannot update BuildConfig " + namespaceName + " as the definition is of class " + clazz);
        return false;
    }

    private static boolean updateCpsFlowDefinition(NamespaceName namespaceName,
            JenkinsPipelineBuildStrategy jenkinsPipelineStrategy, FlowDefinition definition) {
        CpsFlowDefinition cpsFlowDefinition = (CpsFlowDefinition) definition;
        String jenkinsfile = cpsFlowDefinition.getScript();
        if (jenkinsfile != null && jenkinsfile.trim().length() > 0
                && !jenkinsfile.equals(jenkinsPipelineStrategy.getJenkinsfile())) {
            LOGGER.log(INFO, "updating bc " + namespaceName + " jenkinsfile to " + jenkinsfile
                    + " where old jenkinsfile was " + jenkinsPipelineStrategy.getJenkinsfile());
            jenkinsPipelineStrategy.setJenkinsfile(jenkinsfile);
            return true;
        }
        return false;
    }

    private static boolean updateScmFlowDefinition(BuildConfig buildConfig, NamespaceName namespaceName,
            JenkinsPipelineBuildStrategy jenkinsPipelineStrategy, FlowDefinition definition) {
        CpsScmFlowDefinition cpsScmFlowDefinition = (CpsScmFlowDefinition) definition;
        String scriptPath = cpsScmFlowDefinition.getScriptPath();
        if (scriptPath != null && scriptPath.trim().length() > 0) {
            boolean rc = false;
            BuildConfigSpec spec = buildConfig.getSpec();
            BuildSource source = getOrCreateBuildSource(spec);
            String bcContextDir = source.getContextDir();
            if (StringUtils.isNotBlank(bcContextDir) && scriptPath.startsWith(bcContextDir)) {
                scriptPath = scriptPath.replaceFirst("^" + bcContextDir + "/?", "");
            }
            if (!scriptPath.equals(jenkinsPipelineStrategy.getJenkinsfilePath())) {
                LOGGER.log(INFO, "updating bc " + namespaceName + " jenkinsfile path to " + scriptPath + " from ");
                rc = true;
                jenkinsPipelineStrategy.setJenkinsfilePath(scriptPath);
            }
            SCM scm = cpsScmFlowDefinition.getScm();
            if (scm instanceof GitSCM) {
                populateFromGitSCM(buildConfig, source, (GitSCM) scm, null);
                LOGGER.log(INFO, "updating bc " + namespaceName);
                rc = true;
            }
            return rc;
        }
        return false;
    }

    private static boolean populateFromGitSCM(BuildConfig buildConfig, BuildSource source, GitSCM gitSCM, String ref) {
        LOGGER.info("Populating SCM from BuildConfig " + buildConfig + " for branch: " + ref);
        source.setType(GIT_SCM_TYPE);
        List<RemoteConfig> repositories = gitSCM.getRepositories();
        if (repositories != null && repositories.size() > 0) {
            if (repositories.size() > 1) {
                LOGGER.warning("Configuration contains more than 1 repos: OpenShift Sync only support single repo.");
            }
            RemoteConfig remote = repositories.get(0);
            List<URIish> urIs = remote.getURIs();
            if (urIs != null && urIs.size() > 0) {
                URIish urIish = urIs.get(0);
                String gitUrl = urIish.toString();
                if (gitUrl != null && gitUrl.length() > 0) {
                    if (StringUtils.isEmpty(ref)) {
                        List<BranchSpec> branches = gitSCM.getBranches();
                        if (branches != null && branches.size() > 0) {
                            BranchSpec branchSpec = branches.get(0);
                            String branch = branchSpec.getName();
                            while (branch.startsWith("*") || branch.startsWith("/")) {
                                branch = branch.substring(1);
                            }
                            if (!branch.isEmpty()) {
                                ref = branch;
                            }
                        }
                    }
                    OpenShiftUtils.updateGitSourceUrl(buildConfig, gitUrl, ref);
                    return true;
                }
            }
        }
        return false;
    }

    private static BuildSource getOrCreateBuildSource(BuildConfigSpec spec) {
        BuildSource source = spec.getSource();
        if (source == null) {
            source = new BuildSource();
            spec.setSource(source);
        }
        return source;
    }
}
