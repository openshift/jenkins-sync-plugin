package io.fabric8.jenkins.openshiftsync;

import io.fabric8.openshift.client.OpenShiftConfigBuilder;
import org.junit.Test;

import io.fabric8.kubernetes.client.Config;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpenShiftUtilsTest {

    private final static Logger logger = Logger.getLogger(OpenShiftUtils.class.getName());

    public static final String FORBIDDEN = "forbidden";
    public static final String FORBIDDEN1 = FORBIDDEN + "1";
    public static final String FORBIDDEN2 = FORBIDDEN + "2";
    public static final String FORBIDDEN3 = FORBIDDEN + "3";
    public static final String FORBIDDEN4 = FORBIDDEN + "4";

    @Test
    public void expectDetailsOnLoglevelFiner() throws Exception {
        // given
        OpenShiftConfigBuilder configBuilder = new OpenShiftConfigBuilder();
        Config config = configBuilder.build();
        config.setPassword("forbidden1");
        config.setProxyPassword("forbidden2");
        config.setOauthToken("forbidden3");
        config.setAutoOAuthToken("forbidden4");
        logger.setLevel(Level.FINER);

        // when
        String result = OpenShiftUtils.configAsString(config);

        // then
        assertTrue("Config string should contain '" + FORBIDDEN1 + "'", result.contains(FORBIDDEN1));
        assertTrue("Config string should contain '" + FORBIDDEN2 + "'", result.contains(FORBIDDEN2));
        assertTrue("Config string should contain '" + FORBIDDEN3 + "'", result.contains(FORBIDDEN3));
        assertTrue("Config string should contain '" + FORBIDDEN4 + "'", result.contains(FORBIDDEN4));


    }

    @Test
    public void expectDetailsOnLoglevelFinest() throws Exception {
        // given
        OpenShiftConfigBuilder configBuilder = new OpenShiftConfigBuilder();
        Config config = configBuilder.build();
        config.setPassword("forbidden1");
        config.setProxyPassword("forbidden2");
        config.setOauthToken("forbidden3");
        config.setAutoOAuthToken("forbidden4");
        logger.setLevel(Level.FINEST);

        // when
        String result = OpenShiftUtils.configAsString(config);

        // then
        assertTrue("Config string should contain '" + FORBIDDEN1 + "'", result.contains(FORBIDDEN1));
        assertTrue("Config string should contain '" + FORBIDDEN2 + "'", result.contains(FORBIDDEN2));
        assertTrue("Config string should contain '" + FORBIDDEN3 + "'", result.contains(FORBIDDEN3));
        assertTrue("Config string should contain '" + FORBIDDEN4 + "'", result.contains(FORBIDDEN4));

    }

    
    @Test
    public void notExpectDetailsOnLoglevelInfo() throws Exception {
        // given
        OpenShiftConfigBuilder configBuilder = new OpenShiftConfigBuilder();
        Config config = configBuilder.build();
        config.setPassword("forbidden1");
        config.setProxyPassword("forbidden2");
        config.setOauthToken("forbidden3");
        config.setAutoOAuthToken("forbidden4");
        logger.setLevel(Level.INFO);

        // when
        String result = OpenShiftUtils.configAsString(config);

        // then
        assertFalse("Config string should not contain '" + FORBIDDEN1 + "'", result.contains(FORBIDDEN1));
        assertFalse("Config string should not contain '" + FORBIDDEN2 + "'", result.contains(FORBIDDEN2));
        assertFalse("Config string should not contain '" + FORBIDDEN3 + "'", result.contains(FORBIDDEN3));
        assertFalse("Config string should not contain '" + FORBIDDEN4 + "'", result.contains(FORBIDDEN4));


    }



}
