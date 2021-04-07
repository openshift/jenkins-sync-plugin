/**
 * Copyright (C) 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.jenkins.openshiftsync;

import static org.apache.commons.lang.builder.ToStringStyle.DEFAULT_STYLE;

import java.util.logging.Logger;

import org.apache.commons.lang.builder.ReflectionToStringBuilder;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;

public abstract class BaseWatcher<T> implements Watcher<T> {
    private final Logger LOGGER = Logger.getLogger(BaseWatcher.class.getName());

    // protected ScheduledFuture relister;
    protected final transient Object lock = new Object();
    // protected ConcurrentHashMap<String, Watch> watches;
    protected final String namespace;
    protected Watch watch;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public BaseWatcher(String namespace) {
        this.namespace = namespace;
        // this.watches = new ConcurrentHashMap<>();
    }

//    public abstract Runnable getStartTimerTask();

    public abstract int getListIntervalInSeconds();

    protected abstract void start();

    @Override
    public void onClose(WatcherException cause) {
        Watcher<T> watcher = this;
        LOGGER.info("Closing watcher: cause: " + cause + ", watcher: " + watcher);
        if (cause != null) {
            synchronized (this.lock) {
                LOGGER.info("Watcher stopped unexpectedly for : " + this.namespace + ", will restart:" + cause);
                this.watch.close();
                this.watch = null;
                this.start();
            }
        }
    }

    public void onClose(KubernetesClientException cause) {
        this.onClose(new WatcherException(cause.getMessage(), cause));
    }

    public final Runnable getStartTimerTask() {
        return new SafeTimerTask() {
            @Override
            public void doRun() {
                if (!CredentialsUtils.hasCredentials()) {
                    LOGGER.fine("No Openshift Token credential defined.");
                    return;
                }
                start();
            }
        };
    }

//    public abstract <T> void eventReceived(io.fabric8.kubernetes.client.Watcher.Action action, T resource);
//    public abstract <T> void eventReceived(io.fabric8.kubernetes.client.Watcher.Action action, T resource);

    public void startAfterOnClose(String namespace) {
        synchronized (this.lock) {
            start();
        }
    }

//    @Override
//    public void onClose(WatcherException cause) {
//        Watcher<T> watcher = this;
//        String namespace = getNamespace();
//        LOGGER.info("Closing watcher: cause: " + cause + ", watcher: " + watcher);
//        // TODO implement here what should be done when closing this watcher
//        // TODO Let's reimplement it, using Observer pattern and notifying the
//        // GlobalPluginConfiguration listener
//        // super.onClose(cause);
//    }

    @Override
    public void onClose() {
        Watcher<T> watcher = this;
        String namespace = getNamespace();
        LOGGER.info("Closing watcher without cause: " + watcher);
        WatcherException cause = new WatcherException("Received closed event without exception");
        // TODO implement here what should be done when closing this watcher
        // TODO Let's reimplement it, using Observer pattern and notifying the
        // GlobalPluginConfiguration listener
        // super.onClose(cause);
        // watcher.onClose(cause);
    }

    // public synchronized void start() {
    // lets do this in a background thread to avoid errors like:
    // Tried proxying
    // io.fabric8.jenkins.openshiftsync.GlobalPluginConfiguration to support
    // a circular dependency, but it is not an interface.
    // Runnable task = getStartTimerTask();
    // still do the first run 100 milliseconds in
    // this.relister = Timer.get().scheduleAtFixedRate(task, 100,
    // getListIntervalInSeconds() * 1000, MILLISECONDS);
    // }

    public void stop() {
        if (this.watch != null) {
            synchronized (this.lock) {
                if (this.watch != null) {
                    LOGGER.info("Stopping watcher: " + this + " by closing its watch: " + this.watch);
                    this.watch.close();
                    this.watch = null;
                }
            }
        }
        /*
         * if (relister != null && !relister.isDone()) { relister.cancel(true); relister
         * = null; }
         * 
         * for (Map.Entry<String, Watch> entry : watches.entrySet()) {
         * entry.getValue().close(); watches.remove(entry.getKey()); }
         */
    }

//    public void stop(String namespace) {
//        Watch watch = watches.get(namespace);
//        if (watch != null) {
//            watch.close();
//            watches.remove(namespace);
//        }
//    }
    // @Override
//    public void onClose(WatcherException e, String namespace) {
//        // scans of fabric client confirm this call be called with null
//        // we do not want to totally ignore this, as the closing of the
//        // watch can effect responsiveness
//        LOGGER.info("Watch for type " + this.getClass().getName() + " closed for namespace : " + namespace);
//        if (e != null) {
//            synchronized (this.lock) {
//                LOGGER.severe("Exception while watching namespace: " + namespace + ", " + e.toString());
//                // stop(namespace);
//                // startAfterOnClose(namespace);
//            }
//        }
//    }

//    public void addWatch(String key, Watch desiredWatch) {
//        Watch watch = watches.putIfAbsent(key, desiredWatch);
//        if (watch != null) {
//            watch.close();
//        }
//    }

    public String toString() {
        return ReflectionToStringBuilder.toString(this, DEFAULT_STYLE, false, false)
                + ReflectionToStringBuilder.toString(this.watch);
    }

    public String getNamespace() {
        return namespace;
    }

}
