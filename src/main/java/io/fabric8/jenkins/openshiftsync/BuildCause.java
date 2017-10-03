/**
 * Copyright (C) 2016 Red Hat, Inc.
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

import hudson.model.Cause;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.GitBuildSource;
import org.apache.commons.lang.StringUtils;

public class BuildCause extends Cause {

    private String uid;

    private String namespace;

    private String name;

    private String gitUri;

    private String commit;

    private String buildConfigUid;

    private int numStages = -1;

    private int numFlowNodes = -1;

    private long lastUpdateToOpenshift = -1;

    public BuildCause(String uid, String namespace, String name, String gitUri,
            String commit, String buildConfigUid) {
        this.uid = uid;
        this.namespace = namespace;
        this.name = name;
        this.gitUri = gitUri;
        this.commit = commit;
        this.buildConfigUid = buildConfigUid;
    }

    public BuildCause(String uid, String namespace, String name, String gitUri,
            String commit, String buildConfigUid, int numStages,
            int numFlowNodes, long lastUpdateToOpenshift) {
        this(uid, namespace, name, gitUri, commit, buildConfigUid);
        this.numStages = numStages;
        this.numFlowNodes = numFlowNodes;
        this.lastUpdateToOpenshift = lastUpdateToOpenshift;
    }

    public BuildCause(Build build, String buildConfigUid) {
        this.buildConfigUid = buildConfigUid;
        if (build == null || build.getMetadata() == null) {
            return;
        }
        ObjectMeta meta = build.getMetadata();
        uid = meta.getUid();
        namespace = meta.getNamespace();
        name = meta.getName();

        if (build.getSpec() != null) {
            if (build.getSpec().getSource() != null
                    && build.getSpec().getSource().getGit() != null) {
                GitBuildSource git = build.getSpec().getSource().getGit();
                gitUri = git.getUri();
            }

            if (build.getSpec().getRevision() != null
                    && build.getSpec().getRevision().getGit() != null) {
                commit = build.getSpec().getRevision().getGit().getCommit();
            }
        }
    }

    @Override
    public String getShortDescription() {
        StringBuilder sb = new StringBuilder("OpenShift Build ")
                .append(namespace).append("/").append(name);

        if (StringUtils.isNotBlank(gitUri)) {
            sb.append(" from ").append(gitUri);
            if (StringUtils.isNotBlank(commit)) {
                sb.append(", commit ").append(commit);
            }
        }

        return sb.toString();
    }

    public String getUid() {
        return uid;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getName() {
        return name;
    }

    public String getGitUri() {
        return gitUri;
    }

    public String getCommit() {
        return commit;
    }

    public String getBuildConfigUid() {
        return buildConfigUid;
    }

    public int getNumStages() {
        return numStages;
    }

    public void setNumStages(int numStages) {
        this.numStages = numStages;
    }

    public int getNumFlowNodes() {
        return numFlowNodes;
    }

    public void setNumFlowNodes(int numFlowNodes) {
        this.numFlowNodes = numFlowNodes;
    }

    public long getLastUpdateToOpenshift() {
        return lastUpdateToOpenshift;
    }

    public void setLastUpdateToOpenshift(long lastUpdateToOpenshift) {
        this.lastUpdateToOpenshift = lastUpdateToOpenshift;
    }

}
