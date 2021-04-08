package io.fabric8.jenkins.openshiftsync;

import static hudson.init.InitMilestone.COMPLETED;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAuthenticatedOpenShiftClient;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import hudson.init.InitMilestone;
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.openshift.client.OpenShiftClient;
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
                BuildConfigInformer buildConfigInformer = new BuildConfigInformer(namespace);
                watchers.add(buildConfigInformer);
                buildConfigInformer.start();

                BuildInformer buildInformer = new BuildInformer(namespace);
                buildInformer.start();
                watchers.add(buildInformer);

                ConfigMapInformer configMapInformer = new ConfigMapInformer(namespace);
                configMapInformer.start();
                watchers.add(configMapInformer);

                ImageStreamInformer imageStreamInformer = new ImageStreamInformer(namespace);
                imageStreamInformer.start();
                watchers.add(imageStreamInformer);

                SecretInformer secretInformer = new SecretInformer(namespace);
                secretInformer.start();
                watchers.add(secretInformer);

            }
            logger.info("All the watchers have been initialized!!");
            OpenShiftClient client = getAuthenticatedOpenShiftClient();
            SharedInformerFactory informerFactory = client.informers();
            informerFactory.startAllRegisteredInformers();

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
