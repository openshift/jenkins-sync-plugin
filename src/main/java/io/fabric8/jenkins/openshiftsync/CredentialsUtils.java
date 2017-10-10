package io.fabric8.jenkins.openshiftsync;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.*;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.remoting.Base64;
import hudson.security.ACL;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.openshift.api.model.BuildConfig;
import jenkins.model.Jenkins;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static hudson.Util.fixNull;
import static io.fabric8.jenkins.openshiftsync.Constants.*;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class CredentialsUtils {

	private final static Logger logger = Logger.getLogger(CredentialsUtils.class.getName());

	public static synchronized String updateSourceCredentials(BuildConfig buildConfig) throws IOException {
		if (buildConfig.getSpec() != null && buildConfig.getSpec().getSource() != null
				&& buildConfig.getSpec().getSource().getSourceSecret() != null
				&& !buildConfig.getSpec().getSource().getSourceSecret().getName().isEmpty()) {
			String secretName = buildConfig.getSpec().getSource().getSourceSecret().getName();
			String namespace = buildConfig.getMetadata().getNamespace();
			Secret sourceSecret = getAuthenticatedOpenShiftClient().secrets()
					.inNamespace(buildConfig.getMetadata().getNamespace())
					.withName(buildConfig.getSpec().getSource().getSourceSecret().getName()).get();
			return upsertCredential(sourceSecret, namespace, secretName);

		}
		return null;
	}

	/**
	 * Inserts or creates a Jenkins Credential for the given Secret
	 */
	public static synchronized String upsertCredential(Secret secret) throws IOException {
		if (secret != null) {
			ObjectMeta metadata = secret.getMetadata();
			if (metadata != null) {
				return upsertCredential(secret, metadata.getNamespace(), metadata.getName());
			}
		}
		return null;
	}

	private static String upsertCredential(Secret secret, String namespace, String secretName) throws IOException {
		String id = null;
		if (secret != null) {
			Credentials creds = secretToCredentials(secret);
			id = secretName(namespace, secretName);
			Credentials existingCreds = lookupCredentials(id);
			final SecurityContext previousContext = ACL.impersonate(ACL.SYSTEM);
			try {
				CredentialsStore s = CredentialsProvider.lookupStores(Jenkins.getActiveInstance()).iterator().next();
				if (existingCreds != null) {
					s.updateCredentials(Domain.global(), existingCreds, creds);
				} else {
					s.addCredentials(Domain.global(), creds);
				}
			} finally {
				SecurityContextHolder.setContext(previousContext);
			}
		}
		return id;
	}

	// getCurrentToken returns the ServiceAccount token currently selected by
	// the user. A return value of empty string
	// implies no token is configured.
	public static String getCurrentToken() {
		String credentialsId = GlobalPluginConfiguration.get().getCredentialsId();
		if (credentialsId.equals("")) {
			return "";
		}

		OpenShiftToken token = CredentialsMatchers
				.firstOrNull(
						CredentialsProvider.lookupCredentials(OpenShiftToken.class, Jenkins.getActiveInstance(),
								ACL.SYSTEM, Collections.<DomainRequirement>emptyList()),
						CredentialsMatchers.withId(credentialsId));

		if (token != null) {
			return token.getToken();
		}

		return "";
	}

	private static Credentials lookupCredentials(String id) {
		return CredentialsMatchers
				.firstOrNull(
						CredentialsProvider.lookupCredentials(Credentials.class, Jenkins.getActiveInstance(),
								ACL.SYSTEM, Collections.<DomainRequirement>emptyList()),
						CredentialsMatchers.withId(id));
	}

	private static String secretName(String namespace, String name) {
		return namespace + "-" + name;
	}

	private static Credentials secretToCredentials(Secret secret) {
		String namespace = secret.getMetadata().getNamespace();
		String name = secret.getMetadata().getName();
		final Map<String, String> data = secret.getData();
		final String secretName = secretName(namespace, name);
		switch (secret.getType()) {
		case OPENSHIFT_SECRETS_TYPE_OPAQUE:
			String usernameData = data.get(OPENSHIFT_SECRETS_DATA_USERNAME);
			String passwordData = data.get(OPENSHIFT_SECRETS_DATA_PASSWORD);
			if (isNotBlank(usernameData) && isNotBlank(passwordData)) {
				return newUsernamePasswordCredentials(secretName, usernameData, passwordData);
			}
			String sshKeyData = data.get(OPENSHIFT_SECRETS_DATA_SSHPRIVATEKEY);
			if (isNotBlank(sshKeyData)) {
				return newSSHUserCredential(secretName, data.get(OPENSHIFT_SECRETS_DATA_USERNAME), sshKeyData);
			}

			logger.log(Level.WARNING,
					"Opaque secret either requires {0} and {1} fields for basic auth or {2} field for SSH key",
					new Object[] { OPENSHIFT_SECRETS_DATA_USERNAME, OPENSHIFT_SECRETS_DATA_PASSWORD,
							OPENSHIFT_SECRETS_DATA_SSHPRIVATEKEY });
			return null;
		case OPENSHIFT_SECRETS_TYPE_BASICAUTH:
			return newUsernamePasswordCredentials(secretName, data.get(OPENSHIFT_SECRETS_DATA_USERNAME),
					data.get(OPENSHIFT_SECRETS_DATA_PASSWORD));
		case OPENSHIFT_SECRETS_TYPE_SSH:
			return newSSHUserCredential(secretName, data.get(OPENSHIFT_SECRETS_DATA_USERNAME),
					data.get(OPENSHIFT_SECRETS_DATA_SSHPRIVATEKEY));
		default:
			logger.log(Level.WARNING, "Unknown secret type: " + secret.getType());
			return null;
		}
	}

	private static Credentials newSSHUserCredential(String secretName, String username, String sshKeyData) {
		return new BasicSSHUserPrivateKey(CredentialsScope.GLOBAL, secretName, fixNull(username),
				new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(
						new String(Base64.decode(sshKeyData), StandardCharsets.UTF_8)),
				null, secretName);
	}

	private static Credentials newUsernamePasswordCredentials(String secretName, String usernameData,
			String passwordData) {
		return new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, secretName, secretName,
				new String(Base64.decode(usernameData), StandardCharsets.UTF_8),
				new String(Base64.decode(passwordData), StandardCharsets.UTF_8));
	}

	/**
	 * Does our configuration have credentials?
	 * 
	 * @return true if found.
	 */
	public static boolean hasCredentials() {
		return !StringUtils.isEmpty(getAuthenticatedOpenShiftClient().getConfiguration().getOauthToken());
	}

}
