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
public class BuildPhases {
    // TODO it'd be nice to code generate these from the go source code
    // in kubernetes-model
    // e.g. from this
    // https://github.com/openshift/origin/blob/master/pkg/build/api/types.go#L120

    // NEW is automatically assigned to a newly created build.
    public static final String NEW = "New";

    // PENDING indicates that a pod name has been assigned and a build is
    // about to start running.
    public static final String PENDING = "Pending";

    // RUNNING indicates that a pod has been created and a build is running.
    public static final String RUNNING = "Running";

    // COMPLETE indicates that a build has been successful.
    public static final String COMPLETE = "Complete";

    // FAILED indicates that a build has executed and failed.
    public static final String FAILED = "Failed";

    // ERROR indicates that an error prevented the build from executing.
    public static final String ERROR = "Error";

    // CANCELLED indicates that a running/pending build was stopped from
    // executing.
    public static final String CANCELLED = "Cancelled";

}
