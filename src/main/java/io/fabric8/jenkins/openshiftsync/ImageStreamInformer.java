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

import static io.fabric8.jenkins.openshiftsync.Constants.IMAGESTREAM_AGENT_LABEL;
import static io.fabric8.jenkins.openshiftsync.Constants.IMAGESTREAM_AGENT_LABEL_VALUE;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getInformerFactory;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenshiftClient;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.IMAGESTREAM_TYPE;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.addAgents;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.addPodTemplate;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.deleteAgents;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.getPodTemplatesListFromImageStreams;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.hasPodTemplate;
import static io.fabric8.jenkins.openshiftsync.PodTemplateUtils.updateAgents;
import static java.util.Collections.singletonMap;
import static java.util.logging.Level.SEVERE;

import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.dsl.base.OperationContext;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.openshift.api.model.ImageStream;
import io.fabric8.openshift.api.model.ImageStreamList;

public class ImageStreamInformer extends ImageStreamWatcher implements ResourceEventHandler<ImageStream> {
    public ImageStreamInformer(String namespace) {
        super(namespace);
    }

    private final static Logger LOGGER = Logger.getLogger(ImageStreamInformer.class.getName());
    private SharedIndexInformer<ImageStream> informer;

    @Override
    public int getListIntervalInSeconds() {
        return 1_000 * GlobalPluginConfiguration.get().getImageStreamListInterval();
    }

    public void start() {
        LOGGER.info("Starting ImageStream informer for {} !!" + namespace);
        LOGGER.fine("Listing ImageStream resources");
        SharedInformerFactory factory = getInformerFactory().inNamespace(namespace);
        Map<String, String> labels = singletonMap(IMAGESTREAM_AGENT_LABEL, IMAGESTREAM_AGENT_LABEL_VALUE);
        OperationContext withLabels = new OperationContext().withLabels(labels);
        this.informer = factory.sharedIndexInformerFor(ImageStream.class, withLabels, getListIntervalInSeconds());
        informer.addEventHandler(this);
        factory.startAllRegisteredInformers();
        LOGGER.info("ImageStream informer started for namespace: {}" + namespace);
        ImageStreamList list = getOpenshiftClient().imageStreams().inNamespace(namespace).withLabels(labels).list();
        onInit(list.getItems());
    }

    public void startAfterOnClose(String namespace) {
        synchronized (this.lock) {
            start();
        }
    }

    public void stop() {
        LOGGER.info("Stopping secret informer {} !!" + namespace);
        this.informer.stop();
    }

    @Override
    public void onAdd(ImageStream obj) {
        LOGGER.fine("ImageStream informer  received add event for: {}" + obj);
        ObjectMeta metadata = obj.getMetadata();
        String name = metadata.getName();
        String uid = metadata.getUid();
        LOGGER.info("ImageStream informer received add event for: {}" + name);
        List<PodTemplate> slaves = PodTemplateUtils.getPodTemplatesListFromImageStreams(obj);
        addAgents(slaves, IMAGESTREAM_TYPE, uid, name, namespace);
    }

    @Override
    public void onUpdate(ImageStream oldObj, ImageStream newObj) {
        LOGGER.info("ImageStream informer received update event for: {} to: {}" + oldObj + newObj);
        List<PodTemplate> slaves = PodTemplateUtils.getPodTemplatesListFromImageStreams(newObj);
        ObjectMeta metadata = newObj.getMetadata();
        String uid = metadata.getUid();
        String name = metadata.getName();
        String namespace = metadata.getNamespace();
        updateAgents(slaves, IMAGESTREAM_TYPE, uid, name, namespace);
    }

    @Override
    public void onDelete(ImageStream obj, boolean deletedFinalStateUnknown) {
        LOGGER.info("ImageStream informer received delete event for: {}" + obj);
        List<PodTemplate> slaves = PodTemplateUtils.getPodTemplatesListFromImageStreams(obj);
        ObjectMeta metadata = obj.getMetadata();
        String uid = metadata.getUid();
        String name = metadata.getName();
        String namespace = metadata.getNamespace();
        deleteAgents(slaves, IMAGESTREAM_TYPE, uid, name, namespace);

    }

    private void onInit(List<ImageStream> list) {
        for (ImageStream imageStream : list) {
            try {
                List<PodTemplate> agents = getPodTemplatesListFromImageStreams(imageStream);
                for (PodTemplate podTemplate : agents) {
                    // watch event might beat the timer - put call is technically fine, but not
                    // addPodTemplate given k8s plugin issues
                    if (!hasPodTemplate(podTemplate)) {
                        addPodTemplate(podTemplate);
                    }
                }
            } catch (Exception e) {
                LOGGER.log(SEVERE, "Failed to update job", e);
            }
        }
    }
}