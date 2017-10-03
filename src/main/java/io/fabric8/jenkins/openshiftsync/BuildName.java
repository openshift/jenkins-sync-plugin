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

/**
 */
public class BuildName {
    private final String jobName;
    private final String buildName;

    public static BuildName parseBuildUrl(String url) {
        while (url.startsWith("/")) {
            url = url.substring(1);
        }
        String[] split = url.split("/");
        if (split.length < 3) {
            throw new IllegalArgumentException("Invalid build URL `" + url
                    + " ` expecting at least 2 '/' characters!");
        }
        return new BuildName(split[1], split[2]);
    }

    public BuildName(String jobName, String buildName) {
        this.jobName = jobName;
        this.buildName = buildName;
    }

    @Override
    public String toString() {
        return "BuildName{" + "jobName='" + jobName + '\'' + ", buildName='"
                + buildName + '\'' + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        BuildName buildName1 = (BuildName) o;

        if (!jobName.equals(buildName1.jobName))
            return false;
        return buildName.equals(buildName1.buildName);

    }

    @Override
    public int hashCode() {
        int result = jobName.hashCode();
        result = 31 * result + buildName.hashCode();
        return result;
    }

    public String getBuildName() {
        return buildName;
    }

    public String getJobName() {
        return jobName;
    }
}
