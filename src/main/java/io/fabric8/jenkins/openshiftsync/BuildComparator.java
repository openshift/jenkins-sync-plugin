package io.fabric8.jenkins.openshiftsync;

import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_BUILD_NUMBER;

import java.util.Comparator;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.openshift.api.model.Build;

public class BuildComparator implements Comparator<Build> {
	private static final Logger LOGGER = Logger.getLogger(BuildInformer.class.getName());

	@Override
	public int compare(Build b1, Build b2) {
		ObjectMeta b1Metadata = b1.getMetadata();
		Map<String, String> b1Annotations = b1Metadata.getAnnotations();
		String b1BuildNumber = b1Annotations.get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER);
		if (b1Annotations == null || b1BuildNumber == null) {
			String b1Namespace = b1Metadata.getNamespace();
			String b1Name = b1Metadata.getName();
			LOGGER.warning("Build " + b1Namespace + "/" + b1Name + ", has bad annotations: " + b1Annotations);
			return 0;
		}
		ObjectMeta b2Metadata = b2.getMetadata();
		Map<String, String> b2Annotations = b2Metadata.getAnnotations();
		String b2BuildNumber = b2Annotations.get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER);
		if (b2Annotations == null || b2BuildNumber == null) {
			String b2Namespace = b2Metadata.getNamespace();
			String b2Name = b2Metadata.getName();
			LOGGER.warning("Build " + b2Namespace + "/" + b2Name + ", has bad annotations: " + b2Annotations);
			return 0;
		}
		int rc = 0;
		try {
			rc = Long.compare(Long.parseLong(b1BuildNumber), Long.parseLong(b2BuildNumber));
		} catch (Throwable t) {
			LOGGER.log(Level.FINE, "onInitialBuilds", t);
		}
		return rc;
	}
}
