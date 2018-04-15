#!/usr/bin/env groovy
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

def updateDependencies(source){

  def properties = []
  properties << ['<openshift-client.version>','io/fabric8/openshift-client']

  updatePropertyVersion{
    updates = properties
    repository = source
    project = 'fabric8io/openshift-jenkins-sync-plugin'
  }
}

def stage(){
  return stageProject{
    project = 'fabric8io/openshift-jenkins-sync-plugin'
    useGitTagForNextVersion = true
  }
}

def release(project){
  releaseProject{
    stagedProject = project
    useGitTagForNextVersion = true
    helmPush = false
    groupId = 'io.fabric8.jenkins.plugins'
    githubOrganisation = 'fabric8io'
    artifactIdToWatchInCentral = 'openshift-sync'
    artifactExtensionToWatchInCentral = 'jar'
    extraImagesToTag = null
  }
}

return this
