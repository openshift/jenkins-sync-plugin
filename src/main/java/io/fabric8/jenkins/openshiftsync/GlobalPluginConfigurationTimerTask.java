package io.fabric8.jenkins.openshiftsync;

import static hudson.init.InitMilestone.COMPLETED;

import java.util.logging.Logger;

import hudson.init.InitMilestone;
import hudson.triggers.SafeTimerTask;
import jenkins.model.Jenkins;

public class GlobalPluginConfigurationTimerTask extends SafeTimerTask {

    private static final Logger logger = Logger.getLogger(GlobalPluginConfigurationTimerTask.class.getName());

    private GlobalPluginConfiguration globalPluginConfiguration;

    public GlobalPluginConfigurationTimerTask(GlobalPluginConfiguration globalPluginConfiguration) {
        this.globalPluginConfiguration = globalPluginConfiguration;
    }

    @Override
    protected void doRun() throws Exception {
        logger.info("Confirming Jenkins is started");
        while (true) {
            final Jenkins instance = Jenkins.getActiveInstance();
            // We can look at Jenkins Init Level to see if we are ready to start. If we do
            // not wait, we risk the chance of a deadlock.
            InitMilestone initLevel = instance.getInitLevel();
            logger.fine("Jenkins init level: " + initLevel);
            if (initLevel == COMPLETED) {
                break;
            }
            logger.fine("Jenkins not ready...");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                // ignore
            }
        }
        intializeAndStartWatchers();
    }

    private void intializeAndStartWatchers() {
        String[] namespaces = globalPluginConfiguration.getNamespaces();
        BuildConfigWatcher buildConfigWatcher = new BuildConfigWatcher(namespaces);
        globalPluginConfiguration.setBuildConfigWatcher(buildConfigWatcher);
        buildConfigWatcher.start();

        BuildWatcher buildWatcher = new BuildWatcher(namespaces);
        globalPluginConfiguration.setBuildWatcher(buildWatcher);
        buildWatcher.start();

        ConfigMapWatcher configMapWatcher = new ConfigMapWatcher(namespaces);
        globalPluginConfiguration.setConfigMapWatcher(configMapWatcher);
        configMapWatcher.start();

        ImageStreamWatcher imageStreamWatcher = new ImageStreamWatcher(namespaces);
        globalPluginConfiguration.setImageStreamWatcher(imageStreamWatcher);
        imageStreamWatcher.start();

        SecretWatcher secretWatcher = new SecretWatcher(namespaces);
        globalPluginConfiguration.setSecretWatcher(secretWatcher);
        secretWatcher.start();
    }
}
