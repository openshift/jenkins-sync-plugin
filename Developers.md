## Testing locally

If you wish to try out this plug-in against a running Jenkins image you can do this:

    export JENKINS_CONTAINER=`docker ps | grep jenkins | { read first rest ; echo $first ; }`
    mvn install
    docker cp target/openshift-jenkins-sync-plugin.hpi $JENKINS_CONTAINER:/var/jenkins_home/plugins    


Then you need to go into jenkins and reload the configuration which can be done via the `Reload Configuration from disk` option here: http://jenkins.vagrant.f8/manage

    curl -X POST http://jenkins.vagrant.f8/safeRestart
