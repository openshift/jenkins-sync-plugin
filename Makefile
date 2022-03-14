
all: test-e2e


test-e2e:
	echo "GGM echo JENKINS_IMAGE start"
	echo ${JENKINS_IMAGE}
	echo "GGM echo JENKINS_IMAGE end"
	which oc
	KUBERNETES_CONFIG=${KUBECONFIG} go test -timeout 75m -v ./test/e2e/...

verify:
	hack/verify.sh


