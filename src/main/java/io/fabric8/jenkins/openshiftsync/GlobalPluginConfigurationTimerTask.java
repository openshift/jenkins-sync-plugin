package io.fabric8.jenkins.openshiftsync;

import static hudson.init.InitMilestone.COMPLETED;

import java.util.ArrayList;
import java.util.List;
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
        try {
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
                logger.info("Jenkins not ready...");
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    logger.info("Interrupted while sleeping");
                }
            }
            logger.info("Initializing all the watchers...");
            String[] namespaces = globalPluginConfiguration.getNamespaces();
            List<BaseWatcher<?>> watchers = new ArrayList<>();
            for (String namespace : namespaces) {
                BuildConfigWatcher buildConfigWatcher = new BuildConfigWatcher(namespace);
                watchers.add(buildConfigWatcher);
                buildConfigWatcher.start();

                BuildWatcher buildWatcher = new BuildWatcher(namespace);
                buildWatcher.start();
                watchers.add(buildWatcher);

                ConfigMapInformer configMapInformer = new ConfigMapInformer(namespace);
                configMapInformer.start();
                watchers.add(configMapInformer);

                ImageStreamWatcher imageStreamWatcher = new ImageStreamWatcher(namespace);
                imageStreamWatcher.start();
                watchers.add(imageStreamWatcher);

                SecretWatcher secretWatcher = new SecretWatcher(namespace);
                secretWatcher.start();
                watchers.add(secretWatcher);

            }
            logger.info("All the watchers have been initialized!!");
            synchronized (watchers) {
                List<BaseWatcher<?>> globalWatchers = GlobalPluginConfiguration.getWatchers();
                synchronized (globalWatchers) {
                    logger.info("Existing watchers: " + globalWatchers);
                    for (BaseWatcher<?> watch : globalWatchers) {
                        watch.stop();
                    }
                    globalWatchers.clear();
                    logger.info("Existing watchers: stopped and cleared : " + globalWatchers);
                    globalWatchers.addAll(watchers);
                    logger.info("New watchers created : " + globalWatchers.size());

                }
            }
        } catch (Exception e) {
            logger.severe(e.toString());
        }
    }
}
