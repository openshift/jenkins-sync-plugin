/**
 * Copyright (C) 2016 Red Hat, Inc.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.jenkins.openshiftsync.systest;

import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigBuilder;

/**
 */
public class BuildConfigSpec {
  private final String name;
  private final String jenkinsfile;
  private final String gitUri;

  public BuildConfigSpec(String name, String jenkinsfile) {
    this(name, "http://gogs.vagrant.f8/gogsadmin/" + name + ".git", jenkinsfile);
  }

  public BuildConfigSpec(String name, String gitUri, String jenkinsfile) {
    this.jenkinsfile = jenkinsfile;
    this.name = name;
    this.gitUri = gitUri;
  }

  @Override
  public String toString() {
    return "BuildConfigSpec{" +
      "name='" + name + '\'' +
      ", jenkinsfile='" + jenkinsfile + '\'' +
      '}';
  }

  public String getJenkinsfile() {
    return jenkinsfile;
  }

  public String getName() {
    return name;
  }

  public String getGitUri() {
    return gitUri;
  }

  public BuildConfig createBuildConfig() {
    return new BuildConfigBuilder().
      withNewMetadata().withName(name).endMetadata().
      withNewSpec().
      withNewSource().withType("Git").withNewGit().withUri(gitUri).endGit().endSource().
      withNewStrategy().withType("JenkinsPipeline").withNewJenkinsPipelineStrategy().withJenkinsfile(jenkinsfile).endJenkinsPipelineStrategy().endStrategy().
      endSpec().
      build();
  }
}
