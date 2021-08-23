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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import com.google.common.base.Objects;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.listeners.ItemListener;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.openshift.api.model.*;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.removeJobWithBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMapper.updateBuildConfigFromJob;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_LABELS_BUILD_CONFIG_GIT_REPOSITORY_NAME;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

/**
 * Listens to {@link WorkflowJob} objects being updated via the web console or
 * Jenkins REST API and replicating the changes back to the OpenShift
 * {@link BuildConfig} for the case where folks edit inline Jenkinsfile flows
 * inside the Jenkins UI
 */
@Extension
public class PipelineJobListener extends ItemListener {
    private static final Logger logger = Logger.getLogger(PipelineJobListener.class.getName());

    private String server;
    private String namespace;
    private String jobNamePattern;

    public PipelineJobListener() {
        init();
    }

    @DataBoundConstructor
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public PipelineJobListener(String server, String[] namespaces, String jobNamePattern) {
        this.server = server;
        this.namespace = namespaces[0];
        this.jobNamePattern = jobNamePattern;
        init();
    }

    @Override
    public String toString() {
        return "PipelineJobListener{" + "server='" + server + '\'' + ", namespace='" + namespace + '\'' + ", jobNamePattern='" + jobNamePattern + '\'' + '}';
    }

    private void init() {
        // Use namespace.split here to simulate passing an array of strings
        namespace = OpenShiftUtils.getNamespaceOrUseDefault(((namespace != null) ? namespace.split(" ") : null), getOpenShiftClient())[0];
    }

    @Override
    public void onCreated(Item item) {
        if (!GlobalPluginConfiguration.get().isEnabled())
            return;
        reconfigure();
        super.onCreated(item);
        upsertItem(item);
    }

    @Override
    public void onUpdated(Item item) {
        if (!GlobalPluginConfiguration.get().isEnabled())
            return;
        reconfigure();
        super.onUpdated(item);
        upsertItem(item);
    }

    @Override
    public void onDeleted(Item item) {
        if (!GlobalPluginConfiguration.get().isEnabled())
            return;
        reconfigure();
        super.onDeleted(item);
        if (item instanceof WorkflowJob) {
            WorkflowJob job = (WorkflowJob) item;
            BuildConfigProjectProperty property = buildConfigProjectForJob(job);
            if (property != null) {

                NamespaceName buildName = OpenShiftUtils.buildConfigNameFromJenkinsJobName(job.getName(), job.getProperty(BuildConfigProjectProperty.class).getNamespace());

                String namespace = buildName.getNamespace();
                String buildConfigName = buildName.getName();
                BuildConfig buildConfig = getAuthenticatedOpenShiftClient().buildConfigs().inNamespace(namespace).withName(buildConfigName).get();
                if (buildConfig != null) {
                    boolean generatedBySyncPlugin = false;
                    Map<String, String> annotations = buildConfig.getMetadata().getAnnotations();
                    if (annotations != null) {
                        generatedBySyncPlugin = Annotations.GENERATED_BY_JENKINS.equals(annotations.get(Annotations.GENERATED_BY));
                    }
                    try {
                        if (!generatedBySyncPlugin) {
                            logger.info("BuildConfig " + property.getNamespace() + "/" + property.getName() + " will not" + " be deleted since it was not created by " + " the sync plugin");
                        } else {
                            logger.info("Deleting BuildConfig " + property.getNamespace() + "/" + property.getName());
                            getAuthenticatedOpenShiftClient().buildConfigs().inNamespace(namespace).withName(buildConfigName).delete();

                        }
                    } catch (KubernetesClientException e) {
                        if (HTTP_NOT_FOUND != e.getCode()) {
                            logger.log(Level.WARNING, "Failed to delete BuildConfig in namespace: " + namespace + " for name: " + buildConfigName, e);
                        }
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Failed to delete BuildConfig in namespace: " + namespace + " for name: " + buildConfigName, e);
                    } finally {
                        removeJobWithBuildConfig(buildConfig);
                    }
                }
            }
        }
    }

    public void upsertItem(Item item) {
        if (item instanceof WorkflowJob) {
            upsertWorkflowJob((WorkflowJob) item);
        } else if (item instanceof ItemGroup) {
            upsertItemGroup((ItemGroup) item);
        }
    }

    private void upsertItemGroup(ItemGroup itemGroup) {
        Collection items = itemGroup.getItems();
        if (items != null) {
            for (Object child : items) {
                if (child instanceof WorkflowJob) {
                    upsertWorkflowJob((WorkflowJob) child);
                } else if (child instanceof ItemGroup) {
                    upsertItemGroup((ItemGroup) child);
                }
            }
        }
    }

    private void upsertWorkflowJob(WorkflowJob job) {
        BuildConfigProjectProperty property = buildConfigProjectForJob(job);
        String ns = property.getNamespace();
        String bcName = property.getName();
        if (property != null && (!BuildConfigManager.isDeleteInProgress(ns, bcName))) {
            logger.info("Upsert WorkflowJob " + job.getName() + " to BuildConfig: " + ns + "/" + bcName + " in OpenShift");
            upsertBuildConfigForJob(job, property);
        }
    }

    /**
     * Returns the mapping of the jenkins workflow job to a qualified namespace
     * and BuildConfig name
     */
    private BuildConfigProjectProperty buildConfigProjectForJob(WorkflowJob job) {

        BuildConfigProjectProperty property = job.getProperty(BuildConfigProjectProperty.class);

        if (property != null) {
            if (StringUtils.isNotBlank(property.getNamespace()) && StringUtils.isNotBlank(property.getName())) {
                logger.info("Found BuildConfigProjectProperty for namespace: " + property.getNamespace() + " name: " + property.getName());
                return property;
            }
        }

        String patternRegex = this.jobNamePattern;
        String jobName = JenkinsUtils.getFullJobName(job);
        if (StringUtils.isNotEmpty(jobName) && StringUtils.isNotEmpty(patternRegex) && jobName.matches(patternRegex)) {
            String buildConfigName = OpenShiftUtils.convertNameToValidResourceName(JenkinsUtils.getBuildConfigName(job));

            // we will update the uuid when we create the BC
            String uuid = null;

            // TODO what to do for the resourceVersion?
            String resourceVersion = null;
            String buildRunPolicy = "Serial";

            logger.info("Creating BuildConfigProjectProperty for namespace: " + namespace + " name: " + buildConfigName);
            if (property != null) {
                property.setNamespace(namespace);
                property.setName(buildConfigName);
                if (!StringUtils.isNotBlank(property.getBuildRunPolicy())) {
                    property.setBuildRunPolicy(buildRunPolicy);
                }
                return property;
            } else {
                return new BuildConfigProjectProperty(namespace, buildConfigName, uuid, resourceVersion, buildRunPolicy);
            }
        }
        return null;
    }

  /*Right now, when the buildconfig name is different then git repo name
  then sync plugin is not able to find buildconfig by repo name while
  syncing back to openshift after creating a buildconfig with strategy
  JenkinsPipeline, and create a new buildconfig repo name, To fix this
  issue, added a label of gitRepository name so it will first try to
  find the BuildConfig with name of jenkins job, if that is not present
  that is our case then it will also try to find the buildconfig having
  label with the name of jenkins job and then if it is not present will
  create a new BuildConfig*/

    private void upsertBuildConfigForJob(WorkflowJob job, BuildConfigProjectProperty buildConfigProjectProperty) {
        boolean create = false;
        logger.info("Finding BuildConfig for namespace: " + buildConfigProjectProperty.getNamespace() + " name: " +
             buildConfigProjectProperty.getName());
        BuildConfig jobBuildConfig = getAuthenticatedOpenShiftClient().buildConfigs().inNamespace(buildConfigProjectProperty.getNamespace()).withName(buildConfigProjectProperty.getName()).get();


        if (jobBuildConfig == null){

            logger.info("Not able to find BuildConfig for namespace: " + buildConfigProjectProperty.getNamespace() + " name: " +
                buildConfigProjectProperty.getName());

            logger.info("Finding BuildConfig for namespace: " + buildConfigProjectProperty.getNamespace() +
                " with label gitRepository: " + buildConfigProjectProperty.getName());

            BuildConfigList jobBuildConfigs = getOpenShiftClient().buildConfigs().
                inNamespace(buildConfigProjectProperty.getNamespace())
                .withLabels(Collections.singletonMap(OPENSHIFT_LABELS_BUILD_CONFIG_GIT_REPOSITORY_NAME, buildConfigProjectProperty.getName())).list();

            /*Always choose the first one because launcher(https://github.com/fabric8-launcher/launcher-backend) will create
              a single pipeline for a git repo and this will always return one if exists and nothing if does not exist*/
            if (!jobBuildConfigs.getItems().isEmpty()){
                jobBuildConfig = jobBuildConfigs.getItems().get(0);
                logger.info("Able to find BuildConfig for namespace: " + buildConfigProjectProperty.getNamespace() +
                    " with label gitRepository: " + buildConfigProjectProperty.getName());
            } else {
                logger.info("Not able to find BuildConfig for namespace: " + buildConfigProjectProperty.getNamespace() +
                    " with label gitRepository: " + buildConfigProjectProperty.getName());
            }
        } else {
            logger.info("Able to find BuildConfig for namespace: " + buildConfigProjectProperty.getNamespace() + " name: " +
              buildConfigProjectProperty.getName());
        }

        if (jobBuildConfig == null) {

            create = true;
            jobBuildConfig = new BuildConfigBuilder().withNewMetadata().withName(buildConfigProjectProperty.getName()).withNamespace(buildConfigProjectProperty.getNamespace())
                    .addToAnnotations(Annotations.GENERATED_BY, Annotations.GENERATED_BY_JENKINS).endMetadata().withNewSpec().withNewStrategy().withType("JenkinsPipeline").withNewJenkinsPipelineStrategy().endJenkinsPipelineStrategy()
                    .endStrategy().endSpec().build();
        } else {

            ObjectMeta metadata = jobBuildConfig.getMetadata();
            String uid = buildConfigProjectProperty.getUid();
            if (metadata != null && StringUtils.isEmpty(uid)) {
                buildConfigProjectProperty.setUid(metadata.getUid());
            } else if (metadata != null && !Objects.equal(uid, metadata.getUid())) {
                // the UUIDs are different so lets ignore this BC
                return;
            }
        }

        updateBuildConfigFromJob(job, jobBuildConfig);

        if (!hasEmbeddedPipelineOrValidSource(jobBuildConfig)) {
            // this pipeline has not yet been populated with the git source or
            // an embedded
            // pipeline so lets not create/update a BC yet
            return;
        }

        // lets annotate with the job name
        if (create) {
            OpenShiftUtils.addAnnotation(jobBuildConfig, Annotations.JENKINS_JOB_PATH, JenkinsUtils.getFullJobName(job));
        }

        if (create) {
            try {
                logger.info("Creating BuildConfig for namespace: " + buildConfigProjectProperty.getNamespace() +
                    " with name: " + buildConfigProjectProperty.getName());
                BuildConfig bc = getAuthenticatedOpenShiftClient().buildConfigs().inNamespace(jobBuildConfig.getMetadata().getNamespace()).create(jobBuildConfig);
                String uid = bc.getMetadata().getUid();
                buildConfigProjectProperty.setUid(uid);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to create BuildConfig: " + NamespaceName.create(jobBuildConfig) + ". " + e, e);
            }
        } else {
            try {
                getAuthenticatedOpenShiftClient().buildConfigs().inNamespace(jobBuildConfig.getMetadata().getNamespace()).withName(jobBuildConfig.getMetadata().getName()).cascading(false).replace(jobBuildConfig);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to update BuildConfig: " + NamespaceName.create(jobBuildConfig) + ". " + e, e);
            }
        }
    }

    private boolean hasEmbeddedPipelineOrValidSource(BuildConfig buildConfig) {
        BuildConfigSpec spec = buildConfig.getSpec();
        if (spec != null) {
            BuildStrategy strategy = spec.getStrategy();
            if (strategy != null) {
                JenkinsPipelineBuildStrategy jenkinsPipelineStrategy = strategy.getJenkinsPipelineStrategy();
                if (jenkinsPipelineStrategy != null) {
                    if (StringUtils.isNotBlank(jenkinsPipelineStrategy.getJenkinsfile())) {
                        return true;
                    }
                    if (StringUtils.isNotBlank(jenkinsPipelineStrategy.getJenkinsfilePath())) {
                        BuildSource source = spec.getSource();
                        if (source != null) {
                            GitBuildSource git = source.getGit();
                            if (git != null) {
                                if (StringUtils.isNotBlank(git.getUri())) {
                                    return true;
                                }
                            }
                            // TODO support other SCMs
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * TODO is there a cleaner way to get this class injected with any new
     * configuration from GlobalPluginConfiguration?
     */
    private void reconfigure() {
        GlobalPluginConfiguration config = GlobalPluginConfiguration.get();
        if (config != null) {
            this.jobNamePattern = config.getJobNamePattern();
            this.namespace = (config.getNamespace()).split(" ")[0];
            this.server = config.getServer();
        }
    }
}
