# This Dockerfile is intended for use by openshift/ci-operator config files defined
# in openshift/release for v4.x prow based PR CI jobs

FROM registry.access.redhat.com/ubi9/openjdk-21:1.20 AS builder
WORKDIR /java/src/github.com/openshift/jenkins-sync-plugin
COPY . .
USER 0

# Use the downloaded version of maven to build the package
RUN mvn --version
RUN mvn clean package

FROM registry.redhat.io/ocp-tools-4/jenkins-rhel9:v4.17.0
RUN rm /opt/openshift/plugins/openshift-sync.jpi
COPY --from=builder /java/src/github.com/openshift/jenkins-sync-plugin/target/openshift-sync.hpi /opt/openshift/plugins
RUN mv /opt/openshift/plugins/openshift-sync.hpi /opt/openshift/plugins/openshift-sync.jpi
