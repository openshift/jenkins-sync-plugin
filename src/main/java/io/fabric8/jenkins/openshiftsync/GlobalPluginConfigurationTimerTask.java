package io.fabric8.jenkins.openshiftsync;

import static hudson.init.InitMilestone.COMPLETED;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getInformerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import hudson.init.InitMilestone;
import hudson.triggers.SafeTimerTask;
import jenkins.model.Jenkins;

public class GlobalPluginConfigurationTimerTask extends SafeTimerTask {

    private static final Logger logger = Logger.getLogger(GlobalPluginConfigurationTimerTask.class.getName());
    private String[] namespaces;
    private final static List<Lifecyclable> informers = new ArrayList<>();

    public GlobalPluginConfigurationTimerTask(String[] namespaces) {
        this.namespaces = namespaces;
    }

    @Override
    protected void doRun() throws Exception {
        try {
            logger.info("Confirming Jenkins is started");
            while (true) {
                @SuppressWarnings("deprecation")
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
            ConfigMapClusterInformer configMapInformer = new ConfigMapClusterInformer(namespaces);
            configMapInformer.start();

            SecretClusterInformer secretInformer = new SecretClusterInformer(namespaces);
            secretInformer.start();

            BuildConfigClusterInformer buildConfigInformer = new BuildConfigClusterInformer(namespaces);
            buildConfigInformer.start();

            BuildClusterInformer buildInformer = new BuildClusterInformer(namespaces);
            buildInformer.start();

            ImageStreamClusterInformer imageStreamInformer = new ImageStreamClusterInformer(namespaces);
            imageStreamInformer.start();
//

//            List<BaseWatcher<?>> watchers = new ArrayList<>();
            for (String namespace : namespaces) {
//                BuildConfigInformer buildConfigInformer = new BuildConfigInformer(namespace);
//                informers.add(buildConfigInformer);
//                buildConfigInformer.start();

//                BuildInformer buildInformer = new BuildInformer(namespace);
//                buildInformer.start();
//                informers.add(buildInformer);

//                ConfigMapInformer configMapInformer = new ConfigMapInformer(namespace);
//                configMapInformer.start();
//                informers.add(configMapInformer);
//
//                ImageStreamInformer imageStreamInformer = new ImageStreamInformer(namespace);
//                imageStreamInformer.start();
//                informers.add(imageStreamInformer);
//
//                SecretInformer secretInformer = new SecretInformer(namespace);
//                secretInformer.start();
//                informers.add(secretInformer);

            }

            logger.info("All the watchers have been registered!! ... starting all registered informers");
            getInformerFactory().startAllRegisteredInformers();
            logger.info("All registered informers have been started");

        } catch (Exception e) {
            logger.severe(e.toString());
            e.printStackTrace();
        }
    }

    public synchronized void stop() {
        this.cancel();
        for (Lifecyclable informer : informers) {
            informer.stop();
        }
        informers.clear();
    }
}
