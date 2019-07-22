package io.fabric8.jenkins.openshiftsync;

import static io.fabric8.jenkins.openshiftsync.Annotations.DISABLE_SYNC_CREATE;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.getJobFromBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.putJobWithBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMapper.mapBuildConfigToFlow;
import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL;
import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL_LATEST_ONLY;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.updateJob;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAnnotation;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getFullNameParent;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getName;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getNamespace;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.jenkinsJobDisplayName;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.jenkinsJobFullName;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.jenkinsJobName;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.parseResourceVersion;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Map;

import org.apache.tools.ant.filters.StringInputStream;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import com.cloudbees.hudson.plugins.folder.Folder;

import hudson.AbortException;
import hudson.BulkChange;
import hudson.model.ItemGroup;
import hudson.model.ParameterDefinition;
import hudson.util.XStream2;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildSource;
import io.fabric8.openshift.api.model.BuildStrategy;
import jenkins.branch.BranchSource;
import jenkins.branch.MultiBranchProject.BranchIndexing;
import jenkins.model.Jenkins;
import jenkins.plugins.git.GitSCMSource;
import jenkins.security.NotReallyRoleSensitiveCallable;

final class JobProcessor extends NotReallyRoleSensitiveCallable<Void, Exception> {
	/**
	 * 
	 */
	private final BuildConfigWatcher jobProcessor;
	private final BuildConfig buildConfig;
	private final Boolean enableMultibranchSync;

	public JobProcessor(BuildConfigWatcher buildConfigWatcher, BuildConfig buildConfig, Boolean enableMultibranchSync) {
		jobProcessor = buildConfigWatcher;
		this.buildConfig = buildConfig;
		this.enableMultibranchSync = enableMultibranchSync;
	}

	@Override
	public Void call() throws Exception {
		Jenkins activeInstance = Jenkins.getActiveInstance();
	    ItemGroup parent = activeInstance;
	    String jobName = jenkinsJobName(buildConfig);
	    String jobFullName = jenkinsJobFullName(buildConfig);

	    // check if the annotation for enabling multibranch exists
	    if (enableMultibranchSync){
	        jobProcessor.logger.fine("INFO: Multibranch Sync enabled for buildConfig "+buildConfig.getMetadata().getName());
	        parent = getFullNameParent(activeInstance, jobFullName, getNamespace(buildConfig));
	        BuildConfigToMultiBranchMapper multibranchProject = new BuildConfigToMultiBranchMapper(parent, jobName);
	        BulkChange bulkMultibranchProject = createMultibranchProject(jobName, jobFullName,	multibranchProject);
	        InputStream mbStream = new StringInputStream(new XStream2().toXML(multibranchProject));
	        String jenkinsfilePath = updateJenkinsfilePath(buildConfig, multibranchProject);
	        createBranchProjectFactory(multibranchProject, jenkinsfilePath);
	        populateMultibranchFolder(buildConfig, activeInstance, parent, jobName, multibranchProject, mbStream);
	        bulkMultibranchProject.commit(); // Commit BulkChange on multibranchProject
	        return null;
	    }

	    WorkflowJob job = getJobFromBuildConfig(buildConfig);

	    if (job == null) {
	        job = (WorkflowJob) activeInstance.getItemByFullName(jobFullName);
	    }
	    boolean newJob = job == null;
	    if (newJob) {
	        String disableOn = getAnnotation(buildConfig, DISABLE_SYNC_CREATE);
	        if (disableOn != null && disableOn.length() > 0) {
	            jobProcessor.logger.fine("Not creating missing jenkins job " + jobFullName + " due to annotation: " + DISABLE_SYNC_CREATE);
	            return null;
	        }
	        parent = getFullNameParent(activeInstance, jobFullName, getNamespace(buildConfig));
	        job = new WorkflowJob(parent, jobName);
	    }
	    
	    
	    BulkChange bulkJob = new BulkChange(job);
	    job.setDisplayName(jenkinsJobDisplayName(buildConfig));
	    FlowDefinition flowFromBuildConfig = mapBuildConfigToFlow(buildConfig);
	    if (flowFromBuildConfig == null) {
	        return null;
	    }

	    Map<String, ParameterDefinition> paramMap = createOrUpdateJob(buildConfig, activeInstance, parent, jobName, job, newJob, flowFromBuildConfig);
	    bulkJob.commit();
	    populateNamespaceFolder(buildConfig, activeInstance, parent, jobName, job, paramMap);
	    return null;
	}

	private void populateNamespaceFolder(final BuildConfig buildConfig, Jenkins activeInstance,
			ItemGroup parent, String jobName, WorkflowJob job,
			Map<String, ParameterDefinition> paramMap) throws IOException, AbortException {
		String fullName = job.getFullName();
	    WorkflowJob workflowJob = activeInstance.getItemByFullName(fullName, WorkflowJob.class);
	    if (workflowJob == null && parent instanceof Folder) {
	        // we should never need this but just in
	        // case there's an
	        // odd timing issue or something...
	        Folder folder = (Folder) parent;
	        folder.add(job, jobName);
	        workflowJob = activeInstance.getItemByFullName(fullName, WorkflowJob.class);

	    }
	    if (workflowJob == null) {
	        jobProcessor.logger.warning("Could not find created job " + fullName + " for BuildConfig: " + getNamespace(buildConfig) + "/" + getName(buildConfig));
	    } else {
	        JenkinsUtils.verifyEnvVars(paramMap, workflowJob, buildConfig);
	        putJobWithBuildConfig(workflowJob, buildConfig);
	    }
	}

	private Map<String, ParameterDefinition> createOrUpdateJob(final BuildConfig buildConfig,
			Jenkins activeInstance, ItemGroup parent, String jobName, WorkflowJob job, boolean newJob,
			FlowDefinition flowFromBuildConfig) throws IOException {
		job.setDefinition(flowFromBuildConfig);

	    String existingBuildRunPolicy = null;
	    BuildConfigProjectProperty buildConfigProjectProperty = job.getProperty(BuildConfigProjectProperty.class);
	    existingBuildRunPolicy = populateBuildConfigJobProperties(buildConfig, job, existingBuildRunPolicy, buildConfigProjectProperty);

	    // (re)populate job param list with any envs
	    // from the build config
	    Map<String, ParameterDefinition> paramMap = JenkinsUtils.addJobParamForBuildEnvs(job, buildConfig.getSpec().getStrategy().getJenkinsPipelineStrategy(), true);

	    job.setConcurrentBuild(!(buildConfig.getSpec().getRunPolicy().equals(SERIAL) || buildConfig.getSpec().getRunPolicy().equals(SERIAL_LATEST_ONLY)));

	    InputStream jobStream = new StringInputStream(new XStream2().toXML(job));

	    if (newJob) {
	        try {
	            if (parent instanceof Folder) {
	                Folder folder = (Folder) parent;
	                folder.createProjectFromXML(jobName, jobStream).save();
	            } else {
	                activeInstance.createProjectFromXML(jobName, jobStream).save();
	            }

	            jobProcessor.logger.info("Created job " + jobName + " from BuildConfig " + NamespaceName.create(buildConfig) + " with revision: " + buildConfig.getMetadata().getResourceVersion());
	        } catch (IllegalArgumentException e) {
	            // see
	            // https://github.com/openshift/jenkins-sync-plugin/issues/117,
	            // jenkins might reload existing jobs on
	            // startup between the
	            // newJob check above and when we make
	            // the createProjectFromXML call; if so,
	            // retry as an update
	            updateJob(job, jobStream, existingBuildRunPolicy, buildConfigProjectProperty);
	            jobProcessor.logger.info("Updated job " + jobName + " from BuildConfig " + NamespaceName.create(buildConfig) + " with revision: " + buildConfig.getMetadata().getResourceVersion());
	        }
	    } else {
	        updateJob(job, jobStream, existingBuildRunPolicy, buildConfigProjectProperty);
	        jobProcessor.logger.info("Updated job " + jobName + " from BuildConfig " + NamespaceName.create(buildConfig) + " with revision: " + buildConfig.getMetadata().getResourceVersion());
	    }
		return paramMap;
	}

	private BulkChange createMultibranchProject(String jobName, String jobFullName,
			BuildConfigToMultiBranchMapper multibranchProject) throws IOException {
		// BulkChange on multibranchProject
		BulkChange bulkMultibranchProject = new BulkChange(multibranchProject);

		multibranchProject.setDisplayName(jobFullName);
		multibranchProject.setDescription("Multibranch Project for BuildConfig "+jobName);
		return bulkMultibranchProject;
	}

	private String updateJenkinsfilePath(final BuildConfig buildConfig,
			BuildConfigToMultiBranchMapper multibranchProject) {
		//Find Jenkinsfile Path
		BuildSource buildSource = buildConfig.getSpec().getSource();
		BuildStrategy buildStrategy = buildConfig.getSpec().getStrategy();
		String gitUri = buildSource.getGit().getUri();
		String jenkinsfilePath = buildStrategy.getJenkinsPipelineStrategy().getJenkinsfilePath();
		jenkinsfilePath = jenkinsfilePath == null ? "Jenkinsfile" : jenkinsfilePath;
		String contextDir = buildSource.getContextDir();
		if (contextDir != null){
		    jenkinsfilePath = contextDir + "/"+ jenkinsfilePath;
		}

		GitSCMSource gitSCMSource = new GitSCMSource(null, gitUri, "", "*", "", false);
		BranchSource branchSource = new BranchSource(gitSCMSource);
		multibranchProject.getSourcesList().add(branchSource);
		return jenkinsfilePath;
	}

	private void populateMultibranchFolder(final BuildConfig buildConfig, Jenkins activeInstance,
			ItemGroup parent, String jobName, BuildConfigToMultiBranchMapper multibranchProject,
			InputStream mbStream) throws IOException, AbortException {
		Folder folder = (Folder) parent;
		try {
		    folder.createProjectFromXML(jobName, mbStream).save();
		} catch (Exception e) {
		}

		BranchIndexing branchIndex = multibranchProject.getIndexing();
		branchIndex.run();

		multibranchProject = (BuildConfigToMultiBranchMapper) branchIndex.getParent();

		Collection<WorkflowJob> branchJobs = multibranchProject.getItems();
		for (int i = 0 ; i < branchJobs.size(); i++){
		    WorkflowJob branchJob = (WorkflowJob) branchJobs.toArray()[i];
		    Map<String, ParameterDefinition> paramMap = populateBranchJob(buildConfig, jobName,
					branchJob);

		    populateNamespaceFolder(buildConfig, activeInstance, parent, jobName, branchJob, paramMap);
		}
	}

	private void createBranchProjectFactory(BuildConfigToMultiBranchMapper multibranchProject,
			String jenkinsfilePath) throws IOException {
		BuildBranchProjectFactory branchProjectFactory = multibranchProject.newProjectFactory();

		// BulkChange on Project Factory
		BulkChange bulkBranchProjectFactory = new BulkChange(branchProjectFactory);
		branchProjectFactory.setScriptPath(jenkinsfilePath);
		branchProjectFactory.setOwner(multibranchProject);
		bulkBranchProjectFactory.commit(); // Commit BulkChange on Branch Project Factory
	}

	private Map<String, ParameterDefinition> populateBranchJob(final BuildConfig buildConfig,
			String jobName, WorkflowJob branchJob) throws IOException {
		BulkChange bulkBranchJob = new BulkChange(branchJob);

		FlowDefinition flowFromBuildConfig = mapBuildConfigToFlow(buildConfig);
		if (flowFromBuildConfig == null) {
		  return null;
		}

		branchJob.setDefinition(flowFromBuildConfig);

		String existingBuildRunPolicy = null;

		BuildConfigProjectProperty buildConfigProjectProperty = branchJob.getProperty(BuildConfigProjectProperty.class);

		existingBuildRunPolicy = populateBuildConfigJobProperties(buildConfig, branchJob,
				existingBuildRunPolicy, buildConfigProjectProperty);

		// (re)populate job param list with any envs
		// from the build config
		Map<String, ParameterDefinition> paramMap = JenkinsUtils.addJobParamForBuildEnvs(branchJob, buildConfig.getSpec().getStrategy().getJenkinsPipelineStrategy(), true);

		branchJob.setConcurrentBuild(!(buildConfig.getSpec().getRunPolicy().equals(SERIAL) || buildConfig.getSpec().getRunPolicy().equals(SERIAL_LATEST_ONLY)));

		InputStream branchJobStream = new StringInputStream(new XStream2().toXML(branchJob));

		updateJob(branchJob, branchJobStream, existingBuildRunPolicy, buildConfigProjectProperty);
		jobProcessor.logger.info("Updated job " + jobName + " from BuildConfig " + NamespaceName.create(buildConfig) + " with revision: " + buildConfig.getMetadata().getResourceVersion());

		bulkBranchJob.commit();
		return paramMap;
	}

	private String populateBuildConfigJobProperties(final BuildConfig buildConfig,
			WorkflowJob branchJob, String existingBuildRunPolicy,
			BuildConfigProjectProperty buildConfigProjectProperty) throws IOException {
		if (buildConfigProjectProperty != null) {
		    existingBuildRunPolicy = buildConfigProjectProperty.getBuildRunPolicy();
		    long updatedBCResourceVersion = parseResourceVersion(buildConfig);
		    long oldBCResourceVersion = parseResourceVersion(buildConfigProjectProperty.getResourceVersion());
		    BuildConfigProjectProperty newProperty = new BuildConfigProjectProperty(buildConfig);
		    if (updatedBCResourceVersion <= oldBCResourceVersion && newProperty.getUid().equals(buildConfigProjectProperty.getUid()) && newProperty.getNamespace().equals(buildConfigProjectProperty.getNamespace())
		      && newProperty.getName().equals(buildConfigProjectProperty.getName()) && newProperty.getBuildRunPolicy().equals(buildConfigProjectProperty.getBuildRunPolicy())) {
		      return null;
		    }
		    buildConfigProjectProperty.setUid(newProperty.getUid());
		    buildConfigProjectProperty.setNamespace(newProperty.getNamespace());
		    buildConfigProjectProperty.setName(newProperty.getName());
		    buildConfigProjectProperty.setResourceVersion(newProperty.getResourceVersion());
		    buildConfigProjectProperty.setBuildRunPolicy(newProperty.getBuildRunPolicy());
		} else {
		    branchJob.addProperty(new BuildConfigProjectProperty(buildConfig));
		}
		return existingBuildRunPolicy;
	}
}