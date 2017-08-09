# openshift-jenkins-sync-plugin

This Jenkins plugin keeps OpenShift BuildConfig and Build objects in sync with Jenkins Jobs and Builds.

The synchronization works like this


* changes to OpenShift BuildConfig resources for Jenkins pipeline builds result in updates to the Jenkins Job of the same name
* creating a new OpenShift Build for a BuildConfig associated with a Jenkins Job results in the Jenkins Job being triggered
* changes in a Jenkins Build Run thats associated with a Jenkins Job gets replicated to an OpenShift Build object (which is created if necessary if the build was triggered via Jenkins)
* changes in OpenShift ConfigMap resources are examined for XML documents that correspond to Pod Template configuration for the Kubernetes Cloud plugin at http://github.com/jenkinsci/kubernetes-plugin and change the configuration of the Kuberentes Cloud plugin running in Jenkins to add, edit, or remove Pod Templates based on what exists in the ConfigMap; also note, if the <image></image> setting of the Pod Template starts with "imagestreamtag:", then this plugin will look up the ImageStreamTag for that entry (stripping "imagestreamtag:" first) and if found, replace the entry with the ImageStreamTag's Docker image reference.
* changes to OpenShift ImageStream resources with the label "role" set to "jenkins-slave" and ImageStreamTag resources with the annotation "role" set to "jenkins-slave" are considered images to used with Pod Templates for the Kubernetes Cloud plugin, where the Pod Templates are added, modified, or deleted from the Kubernetes cloud plugin as corresponding ImageStreams and ImageStreamTags are added, modified, or deleted, or have the "role=jenkins-slave" setting changed.  

Development Instructions
------------------------

* Build and run the unit tests
  Execute `mvn clean install`
  
* Install the plugin into a locally-running Jenkins
  Execute `mvn hpi:run`
  Navigate in brower to `http://localhost:8080/jenkins`