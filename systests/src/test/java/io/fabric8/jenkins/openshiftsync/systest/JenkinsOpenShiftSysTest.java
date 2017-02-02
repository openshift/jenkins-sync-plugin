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
package io.fabric8.jenkins.openshiftsync.systest;

import com.offbytwo.jenkins.JenkinsServer;
import io.fabric8.arquillian.kubernetes.Session;
import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.kubernetes.api.ServiceNames;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.ReplicationControllerBuilder;
import io.fabric8.kubernetes.api.model.ReplicationControllerSpec;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.testing.jenkins.JenkinsAsserts;
import io.fabric8.utils.Asserts;
import io.fabric8.utils.Block;
import io.fabric8.utils.Millis;
import io.fabric8.utils.Strings;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

import static io.fabric8.kubernetes.assertions.Assertions.assertThat;
import static io.fabric8.openshift.assertions.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.extractProperty;
import static org.assertj.core.api.Assertions.fail;

@RunWith(Arquillian.class)
public class JenkinsOpenShiftSysTest {

  @ArquillianResource
  KubernetesClient client;

  @ArquillianResource
  Session session;

  protected String jenkinsName = ServiceNames.JENKINS;
  protected String jenkinsUrl;
  protected OpenShiftClient openshiftClient;
  protected JenkinsServer jenkinsServer;
  protected String namespace;

  // TODO ?
  //@KubernetesModelProcessor
  public void configureJenkins(ReplicationControllerBuilder builder) {
    // lets start with zero replicas!
    ReplicationControllerSpec spec = builder.getSpec();
    spec.setReplicas(0);

    PodSpec podSpec = spec.getTemplate().getSpec();
    List<Container> containers = podSpec.getContainers();
    Container firstContainer = containers.get(0);
    String version = System.getProperty("project.version");
    if (version != null && version.endsWith("-SNAPSHOT")) {
      firstContainer.setImage("fabric8/jenkins-docker");
    }
    // lets clear the volume mounts for now as we don't need secrets or docker sockets for these tests
    podSpec.setVolumes(new ArrayList<Volume>());
    firstContainer.setVolumeMounts(new ArrayList<VolumeMount>());
  }


  @Test
  public void testAppProvisionsRunningPods() throws Exception {
    boolean createJobsDirectlyInJenkins = false;
    namespace = session.getNamespace();
    if (Strings.isNullOrBlank(namespace)) {
      namespace = client.getNamespace();
    }
    System.out.println("=========== using namespace " + namespace);
    client = client.inNamespace(namespace);

    openshiftClient = client.adapt(OpenShiftClient.class);

    long testWaitTime = Millis.minutes(10);

    final List<BuildConfigSpec> buildConfigsCreatedBeforeJenkins = Arrays.asList(new BuildConfigSpec("foo",
      "node {\n" +
        "   stage 'Stage 1'\n" +
        "   sleep 10\n" +
        "   echo 'Hello World 1'\n" +
        "   stage 'Stage 2'\n" +
        "   sleep 10\n" +
        "   echo 'Hello World 2'\n" +
        "}\n"));
    assertCreateBuildConfigs(buildConfigsCreatedBeforeJenkins);

    // scale up jenkins
    client.replicationControllers().withName(jenkinsName).scale(1);

    // check that we have a jenkins pod running
    Asserts.assertWaitFor(testWaitTime, new Block() {
      public void invoke() throws Exception {
        assertThat(client).podsForReplicationController(jenkinsName, namespace).
          runningStatus().assertSize().isGreaterThan(0);

        assertRouteExistsForService(openshiftClient, jenkinsName);

        jenkinsUrl = KubernetesHelper.getServiceURL(client, jenkinsName, namespace, "http", true);
        System.out.println("=========== using Jenkins URL " + jenkinsUrl);

        assertThat(jenkinsUrl).describedAs("Service URL for " + jenkinsName).isNotEmpty().startsWith("http://");

        jenkinsServer = JenkinsAsserts.createJenkinsServer(jenkinsUrl);

        // check we've loaded the initial BuildConfigs into Jenkins
        assertJenkinsJobsExist(buildConfigsCreatedBeforeJenkins);
      }
    });

    dumpJenkinsJobs("After startup of Jenkins");



    // lets create some more BuildCofnig objects now Jenkins is up
    final List<BuildConfigSpec> buildConfigsCreatedAfterJenkins = Arrays.asList(new BuildConfigSpec("bar",
      "node {\n" +
        "   stage 'Bar Stage 1'\n" +
        "   sleep 10\n" +
        "   echo 'Hello Bar 1'\n" +
        "   stage 'Bar Stage 2'\n" +
        "   sleep 10\n" +
        "   echo 'Hello Bar 2'\n" +
        "}\n"));
    assertCreateBuildConfigs(buildConfigsCreatedAfterJenkins);

    Asserts.assertWaitFor(testWaitTime, new Block() {
      public void invoke() throws Exception {
        assertJenkinsJobsExist(buildConfigsCreatedAfterJenkins);
      }
    });

    dumpJenkinsJobs("After created BuildConfig objects after Jenkins was up");

    // now lets create some Jobs directly in Jenkins
    final List<BuildConfigSpec> jenkinsJobsCreated = Arrays.asList(new BuildConfigSpec("madeinjenkins",
      "node {\n" +
        "   stage 'Created In Jenkins Stage 1'\n" +
        "   sleep 10\n" +
        "   echo 'Hello Created In Jenkins 1'\n" +
        "   stage 'Created In Jenkins Stage 2'\n" +
        "   sleep 10\n" +
        "   echo 'Hello Created In Jenkins 2'\n" +
        "}\n"));

    if (createJobsDirectlyInJenkins) {
      assertCreateJenkinsPipelineJobs(jenkinsJobsCreated);

      Asserts.assertWaitFor(testWaitTime, new Block() {
        public void invoke() throws Exception {
          assertBuildConfigsExist(jenkinsJobsCreated);
        }
      });

      dumpJenkinsJobs("After created Jobs inside Jenkins directly");
    }


    // now lets trigger all the things!
    // TODO
  }

  public void dumpJenkinsJobs(String message) {
    try {
      Collection<String> jobNames = new TreeSet<String>(jenkinsServer.getJobs().keySet());
      System.out.println(message + ": Jenkins jobs are: " + jobNames);
    } catch (IOException e) {
      fail("Failed to get Jenkins jobs: " + e, e);
    }
  }


  public void assertCreateBuildConfigs(List<BuildConfigSpec> specs) {
    for (BuildConfigSpec spec : specs) {
      BuildConfig buildConfig = spec.createBuildConfig();
      System.out.println("Creating " + spec);
      openshiftClient.buildConfigs().create(buildConfig);

      assertBuildConfigExists(spec);
    }
  }

  public void assertBuildConfigsExist(List<BuildConfigSpec> specs) {
    for (BuildConfigSpec spec : specs) {
      assertBuildConfigExists(spec);
    }
  }

  public void assertBuildConfigExists(BuildConfigSpec spec) {
    BuildConfig buildConfig = assertThat(openshiftClient).buildConfigs().hasName(spec.getName());
    assertThat(extractProperty("spec.strategy.jenkinsPipelineStrategy.jenkinsfile").from(Arrays.asList(buildConfig))).contains(spec.getJenkinsfile());
  }

  /**
   * Creates the given BuildConfig specs in Jenkins and asserts things are all created propertly
   */
  public void assertCreateJenkinsPipelineJobs(List<BuildConfigSpec> specs) {
    for (BuildConfigSpec spec : specs) {
      String xml = JenkinsAsserts.createJenkinsPipelineJobXml(spec.getJenkinsfile());
      String jobName = spec.getName();
      JenkinsAsserts.assertCreateJenkinsJob(jenkinsServer, xml, jobName);
      assertJenkinsJobExists(spec);
    }
  }

  /**
   * Asserts that the given build config specs exist inside Jenkins
   */
  public void assertJenkinsJobsExist(List<BuildConfigSpec> specs) {
    for (BuildConfigSpec spec : specs) {
      assertJenkinsJobExists(spec);
    }
  }

  public void assertJenkinsJobExists(BuildConfigSpec spec) {
    String jobName = spec.getName();
    String actualXml = JenkinsAsserts.assertJobXml(jenkinsServer, jobName);
    String expectedXml = JenkinsAsserts.createJenkinsPipelineJobXml(spec.getJenkinsfile());
    assertThat(actualXml).describedAs("XML for Jenkins job " + jobName).isNotNull().isEqualTo(expectedXml);
  }

  public static void assertRouteExistsForService(OpenShiftClient client, String serviceName) {
    Route route = null;
    try {
      route = client.routes().withName(serviceName).get();
    } catch (Exception e) {
      System.out.println("Failed to look up routes: " + e);
    }
    if (route == null) {
      route = new RouteBuilder().
      route = new RouteBuilder().
        withNewMetadata().withName(serviceName).endMetadata().
        withNewSpec().
        withNewTo().withKind("Service").withName(serviceName).endTo().
        withNewPort().withNewTargetPort("http").endPort().
        endSpec().
        build();
      client.routes().create(route);
      System.out.println("Created route " + serviceName);
    }
  }



}
