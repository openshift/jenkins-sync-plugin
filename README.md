# openshift-jenkins-sync-plugin

This Jenkins plugin keeps OpenShift BuildConfig and Build objects in sync with Jenkins Jobs and Builds.

The synchronization works like this


* Changes to OpenShift BuildConfig resources for Jenkins pipeline builds result in updates to the Jenkins Job of the same name; any BuildConfig source secrets are converted into Jenkins Credentials and registered with
the Jenkins Credentials Plugin.
* Creating a new OpenShift Build for a BuildConfig associated with a Jenkins Job results in the Jenkins Job being triggered
* Changes in a Jenkins Build Run thats associated with a Jenkins Job gets replicated to an OpenShift Build object (which is created if necessary if the build was triggered via Jenkins)
* Changes in OpenShift ConfigMap resources are examined for XML documents that correspond to Pod Template configuration for the Kubernetes Cloud plugin at http://github.com/jenkinsci/kubernetes-plugin and change the configuration of the Kubernetes Cloud plugin running in Jenkins to add, edit, or remove Pod Templates based on what exists in the ConfigMap; also note, if the <image></image> setting of the Pod Template starts with "imagestreamtag:", then this plugin will look up the ImageStreamTag for that entry (stripping "imagestreamtag:" first) and if found, replace the entry with the ImageStreamTag's Docker image reference.
    * Note, if both a ConfigMap and Imagestream attempt to create a PodTemplate of the same name, the first watch event to arrive at the sync plugin claims ownership of the PodTemplate with the given name until the object is deleted or the label is removed.  The other object's PodTemplate definition is ignored until the other object is deleted/label removed and a new event / relist occurs for the other object.
* Changes to OpenShift ImageStream resources with the label "role" set to "jenkins-slave" and ImageStreamTag resources with the annotation "role" set to "jenkins-slave" are considered images to be used as Pod Templates for the Kubernetes Cloud plugin, where the Pod Templates are added, modified, or deleted from the Kubernetes cloud plugin as corresponding ImageStreams and ImageStreamTags are added, modified, or deleted, or have the "role=jenkins-slave" setting changed.  Also, while you cannot set a label directly on an ImageStreamTag, they inherit any labels set on the parent ImageStream.  This plugin will now detect that as well, and create PodTemplates for each ImageStreamTag in addition to the ImageStream.  Also note, the ImageStream's `dockerImageRepository` field is used for the image setting for its PodTemplate.  By comparison, the `dockerImageReference` of each ImageStreamTag is used for the image setting of the corresponding PodTemplate.
    * Note, if both a ConfigMap and Imagestream attempt to create a PodTemplate of the same name, the first watch event to arrive at the sync plugin claims ownership of the PodTemplate with the given name until the object is deleted or the label is removed.  The other object's PodTemplate definition is ignored until the other object is deleted/label removed and a new event / relist occurs for the other object.
* Changes to OpenShift Secrets with the label "credential.sync.jenkins.openshift.io" set to "true" will result in those Secrets getting converted into Jenkins Credentials that are registered with the Jenkins Credentials Plugin.  Mappings occur as follows:
    * First, the name.  By default, the name of the credential in Jenkins will be "<namespace the secret comes from>-<name of the secret>".  But you can use the annotation "jenkins.openshift.io/secret.name" to control what name is used for the Jenkins credential.  However, any naming conflicts are not handled by this plugin.  Now, the mappings:
    * "kubernetes.io/basic-auth" map to Jenkins Username / Password credentials
    * "kubernetes.io/ssh-auth" map to Jenkins SSH User credentials
    * Opaque/generic secrets where the data has a "username" key and a "password" key map to Jenkins Username / Password credentials
    * Opaque/generic secrets where the data has a "ssh-privatekey" map to Jenkins SSH User credentials
    * Opaque/generic secrets where the data has a "secrettext" key map to Jenkins Secret Text credentials
    * Opaque/generic secrets where the data has a "openshift-client-token" key map to Jenkins OpenShift Client Plugin Token credentials
* For a Jenkins Secret File credential, the opaque/generic secret requires the 'filename' attribute. See the example below:

```bash
# Create the secret
oc create secret generic mysecretfile --from-file=filename=mysecret.txt
# Add label to mark that it should be synced.
oc label secret mysecretfile credential.sync.jenkins.openshift.io=true
```

```groovy
// the credential will be created by the plugin with the name '<namespace>-<secretname>'
withCredentials([file(credentialsId: 'namespace-mysecretfile', variable: 'MYFILE')]) {
 sh '''
   #!/bin/bash
   cp ${MYFILE} newsecretfile.txt
 '''
}
```
* For a Jenkins Certificate credential, the opaque/generic secret requires the 'certificate' and 'password' attributes. See the example below:

```bash
# Create the secret
oc create secret generic mycert --from-file=certificate=mycert.p12 --from-literal=password=password
# Add label to mark that it should be synced.
oc label secret mysecretfile credential.sync.jenkins.openshift.io=true
```

Development Instructions
------------------------

* Build and run the unit tests
  Execute `mvn clean install`
  
* Install the plugin into a locally-running Jenkins
  Execute `mvn hpi:run`
  Navigate in brower to `http://localhost:8080/jenkins`
  
Synchronization Polling Frequencies
-----------------------------------

* Jenkins Run to OpenShift Build Sync: 5 seconds [BuildSyncRunListener](https://github.com/openshift/jenkins-sync-plugin/blob/master/src/main/java/io/fabric8/jenkins/openshiftsync/BuildSyncRunListener.java)
  
* OpenShift Resource Relist (backup for missed Watch events): 5 minutes [BaseWatcher](https://github.com/openshift/jenkins-sync-plugin/blob/master/src/main/java/io/fabric8/jenkins/openshiftsync/BaseWatcher.java)
    * Each of the API Object Relist intervals are now configurable from the "Manage Jenkins" -> "Configure System" section for this plugin
    
Other configuration
-------------------

* By default, the project running Jenkins is monitored, but additional projects can be monitored by adding them to the Namespace list in the "Manage Jenkins" -> "Configure System" section for this plugin.  NOTE:  the service account associated with the Jenkins deployment must have the `edit` role for each project monitored
* By default, a Jenkins folder will be created for each project monitored when any Pipeline Strategy build configs are created.  This behavior can be turned off from the "Manage Jenkins" -> "Configure System" section for this plugin.  If turned off, the Jenkins job will not be placed in a folder, and the name will be a combination of the project and build config name.     

Restrictions
--------------------------------------------------

* With respect to Jenkins Pipelines, pipeline writers will not be able to fully leverage the `build` pipeline step to start a OpenShift Pipeline Strategy build from the Jenksinfile of another OpenShift Pipeline Stragetgy build.
The "child" Pipeline Strategy builds will start, but the status cannot be properly captured and the "parent" Pipeline Strategy build fails.
* Do not run multiple jenkins instances running the sync plugin and monitoring the same namespace(s).  There is no coordination between multiple instances of this plugin monitoring the same namespace.  Unpredictable results, such as duplicate, concurrent attempts at initiating OpenShift Pipeline Strategy Builds or initiating Jenkins Job runs that correspond to OpenShift Pipeline Strategy Builds, can result.

Build Config Environment Variable to Jenkins Job Parameter Mapping
------------------------------------------------------------------

See [the OKD documentation](https://docs.okd.io/latest/dev_guide/builds/build_strategies.html#jenkins-pipeline-strategy-environment) for details.

