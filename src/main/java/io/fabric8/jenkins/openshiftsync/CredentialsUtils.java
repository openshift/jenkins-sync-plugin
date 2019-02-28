package io.fabric8.jenkins.openshiftsync;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.*;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cloudbees.plugins.credentials.impl.CertificateCredentialsImpl;

import hudson.model.Fingerprint;
import hudson.remoting.Base64;
import hudson.security.ACL;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.openshift.api.model.BuildConfig;
import jenkins.model.Jenkins;

import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.openshift.jenkins.plugins.OpenShiftTokenCredentials;

import static hudson.Util.fixNull;
import static io.fabric8.jenkins.openshiftsync.Constants.*;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class CredentialsUtils {

    private final static Logger logger = Logger.getLogger(CredentialsUtils.class.getName());

    public static synchronized Secret getSourceCredentials(BuildConfig buildConfig) {
        if (buildConfig.getSpec() != null && buildConfig.getSpec().getSource() != null
                && buildConfig.getSpec().getSource().getSourceSecret() != null
                && !buildConfig.getSpec().getSource().getSourceSecret().getName().isEmpty()) {
            Secret sourceSecret = getAuthenticatedOpenShiftClient().secrets()
                    .inNamespace(buildConfig.getMetadata().getNamespace())
                    .withName(buildConfig.getSpec().getSource().getSourceSecret().getName()).get();
            return sourceSecret;
        }
        return null;
    }

    public static synchronized String updateSourceCredentials(BuildConfig buildConfig) throws IOException {
        Secret sourceSecret = getSourceCredentials(buildConfig);
        String credID = null;
        if (sourceSecret != null) {
            credID = upsertCredential(sourceSecret, sourceSecret.getMetadata().getNamespace(),
                    sourceSecret.getMetadata().getName(),
                    sourceSecret.getMetadata().getAnnotations().get(Annotations.SECRET_NAME));
            if (credID != null)
                BuildConfigSecretToCredentialsMap.linkBCSecretToCredential(NamespaceName.create(buildConfig).toString(),
                        credID);

        } else {
            // call delete and remove any credential that fits the
            // project/bcname pattern
            credID = BuildConfigSecretToCredentialsMap
                    .unlinkBCSecretToCrendential(NamespaceName.create(buildConfig).toString());
            if (credID != null)
                deleteCredential(credID, NamespaceName.create(buildConfig),
                        buildConfig.getMetadata().getResourceVersion());
        }
        return credID;
    }

    public static synchronized void deleteSourceCredentials(BuildConfig buildConfig) throws IOException {
        Secret sourceSecret = getSourceCredentials(buildConfig);
        if (sourceSecret != null) {
            String labelValue = sourceSecret.getMetadata().getLabels()
                    .get(Constants.OPENSHIFT_LABELS_SECRET_CREDENTIAL_SYNC);
            boolean watching = labelValue != null && labelValue.equalsIgnoreCase(Constants.VALUE_SECRET_SYNC);
            // for a bc delete, if we are watching this secret, do not delete
            // credential until secret is actually deleted
            if (watching)
                return;
            deleteCredential(sourceSecret);
        }
    }

    /**
     * Inserts or creates a Jenkins Credential for the given Secret
     */
    public static synchronized String upsertCredential(Secret secret) throws IOException {
        if (secret != null) {
            ObjectMeta metadata = secret.getMetadata();
            if (metadata != null) {
                return upsertCredential(secret, metadata.getNamespace(), metadata.getName(), metadata.getAnnotations().get(Annotations.SECRET_NAME));
            }
        }
        return null;
    }

    private static String upsertCredential(Secret secret, String namespace, String secretName, String customSecretName) throws IOException {
        String id = null;
        if (secret != null) {
            Credentials creds = secretToCredentials(secret);
            if (creds == null)
                return null;
            id = secretName(namespace, secretName, customSecretName);
            Credentials existingCreds = lookupCredentials(id);
            final SecurityContext previousContext = ACL.impersonate(ACL.SYSTEM);
            try {
                CredentialsStore s = CredentialsProvider.lookupStores(Jenkins.getActiveInstance()).iterator().next();
                if (existingCreds != null) {
                    s.updateCredentials(Domain.global(), existingCreds, creds);
                    logger.info("Updated credential " + id + " from Secret " + NamespaceName.create(secret)
                            + " with revision: " + secret.getMetadata().getResourceVersion());
                } else {
                    s.addCredentials(Domain.global(), creds);
                    logger.info("Created credential " + id + " from Secret " + NamespaceName.create(secret)
                            + " with revision: " + secret.getMetadata().getResourceVersion());
                }
                s.save();
            } finally {
                SecurityContextHolder.setContext(previousContext);
            }
        }
        return id;
    }

    private static void deleteCredential(String id, NamespaceName name, String resourceRevision) throws IOException {
        Credentials existingCred = lookupCredentials(id);
        if (existingCred != null) {
            final SecurityContext previousContext = ACL.impersonate(ACL.SYSTEM);
            try {
                Fingerprint fp = CredentialsProvider.getFingerprintOf(existingCred);
                if (fp != null && fp.getJobs().size() > 0) {
                    // per messages in credentials console, it is not a given,
                    // but
                    // it is possible for job refs to a credential to be
                    // tracked;
                    // if so, we will not prevent deletion, but at least note
                    // things
                    // for potential diagnostics
                    StringBuffer sb = new StringBuffer();
                    for (String job : fp.getJobs())
                        sb.append(job).append(" ");
                    logger.info("About to delete credential " + id + "which is referenced by jobs: " + sb.toString());
                }
                CredentialsStore s = CredentialsProvider.lookupStores(Jenkins.getActiveInstance()).iterator().next();
                s.removeCredentials(Domain.global(), existingCred);
                logger.info(
                        "Deleted credential " + id + " from Secret " + name + " with revision: " + resourceRevision);
                s.save();
            } finally {
                SecurityContextHolder.setContext(previousContext);
            }
        }
    }

    public static void deleteCredential(Secret secret) throws IOException {
        if (secret != null) {
            String id = secretName(secret.getMetadata().getNamespace(), secret.getMetadata().getName(), secret.getMetadata().getAnnotations().get(Annotations.SECRET_NAME));
            deleteCredential(id, NamespaceName.create(secret), secret.getMetadata().getResourceVersion());
        }
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

    private static String secretName(String namespace, String name, String customName) {
        return (customName == null) ? namespace + "-" + name : customName;
    }

    private static Credentials arbitraryKeyValueTextCredential(Map<String, String> data, String secretName) {
        String text = "";
        if (data != null && data.size() > 0) {
            // convert to JSON for parsing ease in pipelines
            try {
                text = new ObjectMapper().writeValueAsString(data);
            } catch (JsonProcessingException e) {
                logger.log(Level.WARNING, "Arbitrary opaque secret " + secretName + " had issue converting json", e);
            }
        }
        if (StringUtils.isBlank(text)) {
            logger.log(
                    Level.WARNING,
                    "Opaque secret {0} did not provide any data that could be processed into a Jenkins credential",
                    new Object[] { secretName });

            return null;
        }
        return newSecretTextCredential(secretName, text);
    }

    private static Credentials secretToCredentials(Secret secret) {
        String namespace = secret.getMetadata().getNamespace();
        String name = secret.getMetadata().getName();
        String customName = secret.getMetadata().getAnnotations().get(Annotations.SECRET_NAME);

        Map<String, String> data = secret.getData();

        if (data == null) {
            logger.log(Level.WARNING,
                    "An OpenShift secret was marked for import, but it has no secret data.  No credential will be created.");
            return null;
        }

        final String secretName = secretName(namespace, name, customName);
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
            String fileData = data.get(OPENSHIFT_SECRETS_DATA_FILENAME);
            if (isNotBlank(fileData)) {
                return newSecretFileCredential(secretName, fileData);
            }
            String certificateDate = data.get(OPENSHIFT_SECRETS_DATA_CERTIFICATE);
            if (isNotBlank(certificateDate)) {
                return newCertificateCredential(secretName, passwordData, certificateDate);
            }
            String secretTextData = data.get(OPENSHIFT_SECRETS_DATA_SECRET_TEXT);
            if (isNotBlank(secretTextData)) {
                return newSecretTextCredential(secretName, secretTextData);
            }
            String openshiftTokenData = data.get(OPENSHIFT_SECRETS_DATA_CLIENT_TOKEN);
            if (isNotBlank(openshiftTokenData)) {
              return newOpenshiftTokenCredentials(secretName, openshiftTokenData);
            }

            return arbitraryKeyValueTextCredential(data, secretName);

        case OPENSHIFT_SECRETS_TYPE_BASICAUTH:
            return newUsernamePasswordCredentials(secretName, data.get(OPENSHIFT_SECRETS_DATA_USERNAME),
                    data.get(OPENSHIFT_SECRETS_DATA_PASSWORD));
        case OPENSHIFT_SECRETS_TYPE_SSH:
            return newSSHUserCredential(secretName, data.get(OPENSHIFT_SECRETS_DATA_USERNAME),
                    data.get(OPENSHIFT_SECRETS_DATA_SSHPRIVATEKEY));
        default:
            // the type field is marked optional in k8s.io/api/core/v1/types.go,
            // default to OPENSHIFT_SECRETS_DATA_SECRET_TEXT in this case
            return arbitraryKeyValueTextCredential(data, secretName);
        }
    }

    private static Credentials newOpenshiftTokenCredentials(String secretName, String secretText) {
      if (secretName == null || secretName.length() == 0 || secretText == null || secretText.length() == 0) {
        logger.log(Level.WARNING,
          "Invalid secret data, secretName: " + secretName + " secretText is null: " + (secretText == null)
            + " secretText is empty: " + (secretText != null ? secretText.length() == 0 : false));
        return null;

      }

      return new OpenShiftTokenCredentials(CredentialsScope.GLOBAL, secretName, secretName,
        hudson.util.Secret.fromString(new String(Base64.decode(secretText), StandardCharsets.UTF_8)));
    }

    private static Credentials newSecretFileCredential(String secretName, String fileData) {
        if (secretName == null || secretName.length() == 0 || fileData == null || fileData.length() == 0) {
            logger.log(Level.WARNING,
                    "Invalid secret data, secretName: " + secretName + " filename is null: " + (fileData == null)
                            + " filename is empty: " + (fileData != null ? fileData.length() == 0 : false));
            return null;

        }
        return new FileCredentialsImpl(CredentialsScope.GLOBAL, secretName, secretName, secretName,
                SecretBytes.fromString(fileData));
    }

    private static Credentials newSecretTextCredential(String secretName, String secretText) {
        if (secretName == null || secretName.length() == 0 || secretText == null || secretText.length() == 0) {
            logger.log(Level.WARNING,
                    "Invalid secret data, secretName: " + secretName + " secretText is null: " + (secretText == null)
                            + " secretText is empty: " + (secretText != null ? secretText.length() == 0 : false));
            return null;

        }
        return new StringCredentialsImpl(CredentialsScope.GLOBAL, secretName, secretName,
                hudson.util.Secret.fromString(new String(Base64.decode(secretText), StandardCharsets.UTF_8)));
    }

    private static Credentials newCertificateCredential(String secretName, String passwordData,
            String certificateData) {
        if (secretName == null || secretName.length() == 0 || certificateData == null
                || certificateData.length() == 0) {
            logger.log(Level.WARNING,
                    "Invalid secret data, secretName: " + secretName + " certificate is null: "
                            + (certificateData == null) + " certificate is empty: "
                            + (certificateData != null ? certificateData.length() == 0 : false));
            return null;
        }
        String certificatePassword = passwordData != null ? new String(Base64.decode(passwordData)) : null;
        return new CertificateCredentialsImpl(CredentialsScope.GLOBAL, secretName, secretName, certificatePassword,
                new CertificateCredentialsImpl.UploadedKeyStoreSource(SecretBytes.fromString(certificateData)));
    }

    private static Credentials newSSHUserCredential(String secretName, String username, String sshKeyData) {
        if (secretName == null || secretName.length() == 0 || sshKeyData == null || sshKeyData.length() == 0) {
            logger.log(Level.WARNING,
                    "Invalid secret data, secretName: " + secretName + " sshKeyData is null: " + (sshKeyData == null)
                            + " sshKeyData is empty: " + (sshKeyData != null ? sshKeyData.length() == 0 : false));
            return null;

        }
        return new BasicSSHUserPrivateKey(CredentialsScope.GLOBAL, secretName,
                fixNull(username).isEmpty() ? "" : new String(Base64.decode(username), StandardCharsets.UTF_8),
                new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(
                        new String(Base64.decode(sshKeyData), StandardCharsets.UTF_8)),
                null, secretName);
    }

    private static Credentials newUsernamePasswordCredentials(String secretName, String usernameData,
            String passwordData) {
        if (secretName == null || secretName.length() == 0 || usernameData == null || usernameData.length() == 0
                || passwordData == null || passwordData.length() == 0) {
            logger.log(Level.WARNING,
                    "Invalid secret data, secretName: " + secretName + " usernameData is null: "
                            + (usernameData == null) + " usernameData is empty: "
                            + (usernameData != null ? usernameData.length() == 0 : false) + " passwordData is null: "
                            + (passwordData == null) + " passwordData is empty: "
                            + (passwordData != null ? passwordData.length() == 0 : false));
            return null;

        }
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
