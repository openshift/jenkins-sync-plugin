
all: test-e2e


test-e2e:
	KUBERNETES_CONFIG=${KUBECONFIG} go test -timeout 60m -v ./test/e2e/...

verify:
	hack/verify.sh


