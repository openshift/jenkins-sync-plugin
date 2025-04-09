
all: test-e2e


test-e2e:
	oc version
	go version
	export JENKINS_AGENT_BASE_IMAGE=registry.redhat.io/ocp-tools-4/jenkins-agent-base-rhel8:v4.15.0 && \
	hack/tag-ci-image.sh
	KUBERNETES_CONFIG=${KUBECONFIG} go test -timeout 75m -v ./test/e2e/...

verify:
	hack/verify.sh
