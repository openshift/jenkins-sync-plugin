- 
## 1.0.41
- Fixes conccurency issue with multiple-thread trying to trigger already running jobs
- Refactor: Extracted Method for updating folder which contains jobs in the context of a namespace
- Refactor : Extracted method for creating or updating job
- Refactor: buildConfigProjectProperty updated using a method
- Refactor 'bk' to 'bulkJob'
- Refactor upsertJob to use new class
- CVE-2019-12384 : FasterXML jackson-databind 2.x before 2.9.9.1 might allow attackers to have a variety of impacts by leveraging failure to block the logback-core class from polymorphic deserialization
- Update javadoc
- CVE-2019-12384 : FasterXML jackson-databind 2.x before 2.9.9.1 might allow attackers to have a variety of impacts by leveraging failure to block the logback-core class from polymorphic deserialization
- 
## 1.0.40
- Bump kubernetes-client version (openshift-client) to 4.3 to support OpenShift 4.2. /oapi endpoint deprecation
- add devtools to owners
- 
## 1.0.39
- synchronization fixes for recently discovered deadlock
- 
## 1.0.38
- convert any mvn/plugin repos from http to https to prevent mitm attacks
- travis/prow no longer copacetic
- 
## 1.0.37
- Bug 1709626: fix pod template tracking in the sync plugin so pod templates are not inadvertently delete when config maps and imagestreams have the same name; also grants ownership of the pod template to whichever type's event comes in first
- 
## 1.0.36
- Secret Text is not encoded when it is passed to newSecretTextCredential.
- add link to env->param mapping
- README clarifications on is/ist image for pod templates; add ist label inheritance description
- 
## 1.0.35
- check ist labels as well in case inherited from is
- create Dockerfile to enable prow based PR jobs against v4.x clusters
- 
## 1.0.34
- fix NPE introduced by https://github.com/openshift/jenkins-sync-plugin/pull/283
- Fixes https://github.com/openshift/jenkins-sync-plugin/issues/282 possibly avoids duplicate build entries in the skip list by indexing off of build name vs. obj synchronizes on the flush method since it possible can get called by multiple folks
- Add adambkaplan as approver
- 
## 1.0.33
- massage doc around custom name for secret to credential
- fix for #286
- Updated README
- Feature : Customize Secret name using Annotations
- 
## 1.0.32
- add validation
- updating readme
- adding openshift client token sync
- 
## 1.0.30
- 
## 1.0.29
- 
## 1.0.28
- 
## 1.0.27
- 
## 1.0.26
- Update README.md (#252)
- 
## 1.0.25
- 
## 1.0.24
- 
## 1.0.23
- 
## 1.0.21
- 
## 1.0.20
- 
## 1.0.19
- Fixed the issue of duplicate pipeline (#239)
- Updating to latest OpenShift Client (#238)
- 
## 1.0.18
- README updates for recent changes
- 
## 1.0.17
- 
## 1.0.16
- unblocking - use java.concurrent without blocking. (#234)
- update readme on 'build' step restriction (#232)
- 
## 1.0.15
- 
## 1.0.14
- bump okhttp max concurrent watch conns past 5 total (#228)
- 
## 1.0.13
- 
## 1.0.12
- 
## 1.0.11
- 
## 1.0.10
- Cfg list interval (#219)
- impl cache hit for job from bc from build lookup (#218)
- some cleanup to flushBuildsWithNoBCList optimization (#217)
- 
## 1.0.9
- more logging around closed watches; lazy reconnect on closed watch (#214)
- 
## 1.0.8
- Bootstrap an OWNERS file (#213)
- 
## 1.0.7
- 
## 1.0.6
- 
## 1.0.5
- 
## 1.0.4
- 
## 1.0.3
- 
## 1.0.2
- 
## 1.0.1
- 
## 1.0.0
- 
## 0.9.2
- 
## 0.9.1
- 
## 0.1.32
- fix log info npe
- Resolves https://github.com/openshift/jenkins-sync-plugin/issues/131 (#137)
- 
## 0.1.31
- 
## 0.1.30
- 
## 0.1.29
- 
## 0.1.28
- 
## 0.1.27
- 
## 0.1.26
- 
## 0.1.25
- fix string format
- 
## 0.1.22
- fix ist pod name; fix npe with missing annotation map
- 
## 0.1.20
- 
## 0.1.19
- 
## 0.1.18
- 
- mvn findbugs hoopla
- 
## 0.1.16
- 
## 0.1.15
- 
## 0.1.14
- 
## 0.1.13
- 
## 0.1.12
- 
## 0.1.11
- 
## 0.1.10
- 
## 0.1.9
- 
## 0.1.8
- 
## 0.1.7
- 
## 0.1.6
- 
## 0.1.5
- 
## 0.1.4
- 
## 0.1.3
- 
## 0.1.2
- 
## 0.1.1
- 
## 0.1.0
- 
## 0.0.17
- 
## 0.0.16
- 
## 0.0.15
- 
## 0.0.14
- 
## 0.0.13
- 
## 0.0.12
- 
## 0.0.11
- Do not poll non-pipeline builds (#5)
- Add LGTM config (#6)
- 
## 0.0.10
- Upgrade kube client (#4)
- 
## 0.0.9
- 
## 0.0.8
- Remove distribution management - use Jenkins infra (#3)
- Update distribution repos
- FindBugs suppression
- Add license headers
- Add httpclient test dependency - transitive was broken
- Bump required Jenkins version
- Ensure released version of Jenkins client
- Add circleci config
- Switch to upsert to ensure kept in sync & handle deletes properly
- Delete example config.xml
- Modify jobs on build config changes
- Typesafe deletion of jobs
- Only create jobs for builds with external build strategy
- Delete jobs when BuildConfigs are deleted
- First working create build
- Add license headers
- Enable/disable sync via global config
- Initial commit
