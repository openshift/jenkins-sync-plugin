
all: test-e2e

# TODO: Need to debug why test pod does not set `JENKINS_AGENT_BASE_IMAGE` enviornment variable
test-e2e:
	oc version
	go version
	JENKINS_AGENT_BASE_IMAGE=registry.redhat.io/ocp-tools-4/jenkins-agent-base-rhel9:v4.17.0 hack/tag-ci-image.sh
	KUBERNETES_CONFIG=${KUBECONFIG} go test -timeout 75m -v ./test/e2e/...

verify:
	hack/verify.sh
