// Additional libraries
import pluggable.scm.*;
SCMProvider scmProvider = SCMProviderHandler.getScmProvider("${SCM_PROVIDER_ID}", binding.variables)

// Folders
def scmNameSpace = "${SCM_NAMESPACE}"
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"
def GitRepo = "adop-c-base-networking"

// Variables
def projectNameKey = projectFolderName.toLowerCase().replace("/", "-")

// Jobs
def getCumulus = freeStyleJob(projectFolderName + "/Get_Cumulus")
def validateCumulus = freeStyleJob(projectFolderName + "/Validate_Cumulus")
def runCumulus = freeStyleJob(projectFolderName + "/Run_Cumulus")
def smokeTest = freeStyleJob(projectFolderName + "/Smoke_Test")

// Views
def pipelineView = buildPipelineView(projectFolderName + "/Cumulus_Pipeline")

pipelineView.with {
    title('Cumulus Pipeline')
    displayedBuilds(5)
    selectedJob(projectFolderName + "/Get_Cumulus")
    showPipelineParameters()
    showPipelineDefinitionHeader()
    refreshFrequency(5)
}

getCumulus.with {
    description("This job gets the cumulus file and installs the required dependencies.")
    parameters {
        choiceParam("AWS_REGION", ['eu-west-1', 'eu-west-2','us-east-1', 'us-east-2', 'us-west-1', 'us-west-2', 'ca-central-1', 'ap-northeast-2', 'ap-south-1', 'ap-southeast-1', 'ap-southeast-2', 'ap-northeast-1', 'eu-central-1', 'sa-east-1'] , "Region where cumulus will deploy the infrastructure.")
        choiceParam("ENVIRONMENT", [ 'dev', 'uat', 'prod' ], "name of the environment to deploy infrastructure to.")
        stringParam("KEY_NAMESPACE", "adop-c", "The name space in the key store where all the keys and input parameters for stack are stored. The job also adds the CF outputs to the same key space after the pipeline finishes.")
        choiceParam("FORCE_UPDATE", [ 'false', 'true' ], "This parameter controls whether the stack will be updated or not. With this parameter set to true jenkins will tru to run cumulus update.")
        //stringParam("GIT_REPO", GitRepo, "The git repository to get the cumulus file and templates from.")
        //stringParam("GIT_BRANCH", 'master', "The git repository to get the cumulus file and templates from.")
    }
    wrappers {
        preBuildCleanup()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    scm scmProvider.get(scmNameSpace, GitRepo, 'master', "adop-jenkins-master", null)
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
    }
    label("java8")
    triggers scmProvider.trigger(projectFolderName, '${GIT_REPO}', '${GIT_BRANCH}')
    steps {
        shell('''#!/bin/bash
            |# Install cumulus if doesn't exist
            |set +e
            |cumulus -h || (git clone git://github.com/cotdsa/cumulus.git cumulus-install && \\
            |           cd cumulus-install && \\
            |           git checkout 7a38d2576f5e7a2b61b6adfada184f6b2414d0ce && \\
            |           python setup.py install && \\
            |           cd ${WORKSPACE} && \\
            |           rm -rf cumulus-install)
            |
            |# Install aws if doesn't exist
            |
            |aws --version || (curl "https://s3.amazonaws.com/aws-cli/awscli-bundle.zip" -o "awscli-bundle.zip" && \\
            |    unzip awscli-bundle.zip && \\
            |    ./awscli-bundle/install -b ./aws 
            |    ) 
            |
            |# Install consul-template if doesn't exist
            |/usr/local/bin/consul-template -v || (curl "https://releases.hashicorp.com/consul-template/0.18.2/consul-template_0.18.2_linux_amd64.tgz" -o "consul-template.tgz" && \\ 
            |    tar -zxvf consul-template.tgz -C /usr/local/bin/ && \\
            |    chmod +x /usr/local/bin/consul-template)
            |# Get the dependencies for the cumulus.
            |
            |REPOS=$(egrep '^REPO_DEPENDENCIES=' ${WORKSPACE}/metadata | cut -d'=' -f2 | sed 's/,/ /g')
            |
            |echo "Depedencies : ${REPOS}"
            |mkdir -p ${WORKSPACE}/repo_deps && cd ${WORKSPACE}/repo_deps
            |
            |for repo in ${REPOS}
            |do
            |   echo "Cloning : ${repo}"
            |   git clone ${repo}
            |done
            |
            |mkdir -p ${WORKSPACE}/cf_templates && cd ${WORKSPACE}
            |
            |find ${WORKSPACE}/repo_deps -regex ".*\\.yaml\\|.*\\.yml\\|.*\\.json" -exec cp {} ${WORKSPACE}/cf_templates/. \\;
            |find ${WORKSPACE}/cf_templates -regex ".*\\.cumulus\\.yaml\\|.*\\.cumulus\\.yml" -exec rm -f {} \\;
            |rm -rf ${WORKSPAE}/repo_deps
            |set -e'''.stripMargin()
        )        
    }
    publishers {
        archiveArtifacts("**/*")
        downstreamParameterized {
            trigger(projectFolderName + "/Validate_Cumulus") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${BUILD_NUMBER}')
                    predefinedProp("PARENT_BUILD", '${JOB_NAME}')
                    predefinedProp("AWS_REGION", '${AWS_REGION}')
                    predefinedProp("ENVIRONMENT", '${ENVIRONMENT}')
                    predefinedProp("KEY_NAMESPACE", '${KEY_NAMESPACE}')
                    predefinedProp("FORCE_UPDATE", '${FORCE_UPDATE}')
                }
            }
        }
    }
}

validateCumulus.with {
    description("This job runs validation on the cumulus yaml file.")
    parameters {
        stringParam("B", '', "Parent build number")
        stringParam("PARENT_BUILD", "Get_Cumulus", "Parent build name")
        choiceParam("AWS_REGION", ['eu-west-1', 'eu-west-2','us-east-1', 'us-east-2', 'us-west-1', 'us-west-2', 'ca-central-1', 'ap-northeast-2', 'ap-south-1', 'ap-southeast-1', 'ap-southeast-2', 'ap-northeast-1', 'eu-central-1', 'sa-east-1'] , "Region where cumulus will deploy the infrastructure.")
        choiceParam("ENVIRONMENT", [ 'dev', 'uat', 'prod' ], "name of the environment to deploy infrastructure to.")
        choiceParam("FORCE_UPDATE", [ 'false', 'true' ], "This parameter controls whether the stack will be updated or not. With this parameter set to true jenkins will tru to run cumulus update.")
        stringParam("KEY_NAMESPACE", "adop-c", "The name space in the key store where all the keys and input parameters for stack are stored. The job also adds the CF outputs to the same key space after the pipeline finishes.")
        credentialsParam("AWS_LOGIN"){
            type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
            required()
            defaultValue('aws-cumulus-credentials')
            description('AWS secret key and access key to allow cumulus to provision the CF stacks. Please make sure the credentials are added with ID "aws-cumulus-credentials"')
        }
        credentialsParam("VAULT_LOGIN"){
            type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
            required()
            defaultValue('vault-credentials')
            description('Vault credentials so that consul template can git initise the cumulus environment templates. Please make sure the credentials are added with ID "vault-credentials"')
        }

    }
    wrappers {
        preBuildCleanup()
        maskPasswords()
        sshAgent("adop-jenkins-master")
        credentialsBinding {
            usernamePassword("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", '${AWS_LOGIN}')
            usernamePassword("VAULT_USER", "VAULT_TOKEN", '${VAULT_LOGIN}')
        }
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
    }
    label("java8")
    steps {
        copyArtifacts("Get_Cumulus") {
            buildSelector {
                buildNumber('${B}')
            }
        }
        shell('''#!/bin/bash -e
            |
            |find ${WORKSPACE} -regex ".*\\.cumulus\\.yaml\\|.*\\.cumulus\\.yml" | while read file
            |do
            |  cd $(dirname ${file})
            |  set +e
            |  cp -f ${WORKSPACE}/cf_templates/* .
            |  set -e
            |  /usr/local/bin/consul-template \\
            |    -vault-addr=http://vault:8200 \\
            |    -vault-token=${VAULT_TOKEN} \\
            |    -template "env.config.sh.tmpl:env.config.sh" -vault-renew-token=false -once
            |  export Region="${AWS_REGION}"
            |  export Environment="${ENVIRONMENT}"
            |  source env.config.sh
            |  rm -f env.config.sh
            |  echo "=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=."
            |  echo "Validating cumulus syntax : $(basename ${file})"
            |  echo "=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=."
            |  cumulus -y $(basename ${file}) -a check
            |  echo "=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=."
            |done'''.stripMargin()
        ) 
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/Run_Cumulus") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${B}')
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                    predefinedProp("AWS_REGION", '${AWS_REGION}')
                    predefinedProp("ENVIRONMENT", '${ENVIRONMENT}')
                    predefinedProp("KEY_NAMESPACE", '${KEY_NAMESPACE}')
                    predefinedProp("FORCE_UPDATE", '${FORCE_UPDATE}')
                }
            }
        }
    }
}

runCumulus.with {
    description("This job runs the cumulus file to create the infrastructure.")
    parameters {
        stringParam("B", '', "Parent build number")
        stringParam("PARENT_BUILD", "Get_Cumulus", "Parent build name")
        choiceParam("AWS_REGION", ['eu-west-1', 'eu-west-2','us-east-1', 'us-east-2', 'us-west-1', 'us-west-2', 'ca-central-1', 'ap-northeast-2', 'ap-south-1', 'ap-southeast-1', 'ap-southeast-2', 'ap-northeast-1', 'eu-central-1', 'sa-east-1'] , "Region where cumulus will deploy the infrastructure.")
        choiceParam("ENVIRONMENT", [ 'dev', 'uat', 'prod' ], "name of the environment to deploy infrastructure to.")
        choiceParam("FORCE_UPDATE", [ 'false', 'true' ], "This parameter controls whether the stack will be updated or not. With this parameter set to true jenkins will tru to run cumulus update.")
        stringParam("KEY_NAMESPACE", "adop-c", "The name space in the key store where all the keys and input parameters for stack are stored. The job also adds the CF outputs to the same key space after the pipeline finishes.")
        credentialsParam("AWS_LOGIN"){
            type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
            required()
            defaultValue('aws-cumulus-credentials')
            description('AWS secret key and access key to allow cumulus to provision the CF stacks. Please make sure the credentials are added with ID "aws-cumulus-credentials"')
        }
        credentialsParam("VAULT_LOGIN"){
            type('com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl')
            required()
            defaultValue('vault-credentials')
            description('Vault credentials so that consul template can tokenise the cumulus environment templates. Please make sure the credentials are added with ID "vault-credentials"')
        }
    }
    wrappers {
        preBuildCleanup()
        maskPasswords()
        sshAgent("adop-jenkins-master")
        credentialsBinding {
            usernamePassword("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", '${AWS_LOGIN}')
            usernamePassword("VAULT_USER", "VAULT_TOKEN", '${VAULT_LOGIN}')
        }
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
    }
    label("docker")
    steps {
        copyArtifacts("Get_Cumulus") {
            buildSelector {
                buildNumber('${B}')
            }
        }
        shell('''#!/bin/bash -e
            |
            |find ${WORKSPACE} -regex ".*\\.cumulus\\.yaml\\|.*\\.cumulus\\.yml" | while read file
            |do
            |  cd $(dirname ${file})
            |  set +e
            |  cp -f ${WORKSPACE}/cf_templates/* .
            |  set -e
            |  /usr/local/bin/consul-template \\
            |    -vault-addr=http://vault:8200 \\
            |    -vault-token=${VAULT_TOKEN} \\
            |    -template "env.config.sh.tmpl:env.config.sh" -vault-renew-token=false -once
            |  export Region="${AWS_REGION}"
            |  export Environment="${ENVIRONMENT}"
            |  source env.config.sh
            |  rm -f env.config.sh
            |  cumulus -y $(basename ${file}) -a create
            |  if [[ ${FORCE_UPDATE} == "true" ]]; then
            |     echo "Updating the CF stack..."
            |     cumulus -y $(basename ${file}) -a update
            |  fi
            |  # Update the key store
            |  output=$(cumulus -y $(basename ${file}) -a check 2>&1 )
            |  echo "=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=."
            |  echo ${output} | perl -ple 's|INFO:cumulus|\\nINFO:cumulus|g' | grep 'would be created' | \\
            |  perl -ple 's/.*cumulus.MegaStack:Stack (.*?) would be created.*/$1/' | while read stackname
            |  do
            |     aws cloudformation describe-stacks --stack-name ${stackname} --query 'Stacks[0].Outputs[*].[OutputKey,OutputValue]' --output text | while read keys
            |     do
            |        key=$(echo $keys | cut -d' ' -f1)
            |        value=$(echo $keys | cut -d' ' -f2)
            |        echo "Updating Key '$key' with value : '$value'"
            |        curl --silent \\
            |        -H "X-Vault-Token: ${VAULT_TOKEN}" \\
            |        -H "Content-Type: application/json" \\
            |        -X POST \\
            |        -d "{\\"value\\":\\"$value\\"}" \\
            |        http://vault:8200/v1/secret/${KEY_NAMESPACE}/${Region}/${Environment}/${key}
            |     done
            |  done
            |done
            |set -x'''.stripMargin()
        )
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/Smoke_Test") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${B}')
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                    predefinedProp("KEY_NAMESPACE", '${KEY_NAMESPACE}')
                    predefinedProp("AWS_REGION", '${AWS_REGION}')
                    predefinedProp("ENVIRONMENT", '${ENVIRONMENT}')
                }
            }
        }
    }
}

smokeTest.with {
    description("This job runs regression tests on deployed infrastructure")
    parameters {
        stringParam("B", '', "Parent build number")
        stringParam("PARENT_BUILD", "Reference_Application_Build", "Parent build name")
        choiceParam("AWS_REGION", ['eu-west-1', 'eu-west-2','us-east-1', 'us-east-2', 'us-west-1', 'us-west-2', 'ca-central-1', 'ap-northeast-2', 'ap-south-1', 'ap-southeast-1', 'ap-southeast-2', 'ap-northeast-1', 'eu-central-1', 'sa-east-1'] , "Region where cumulus will deploy the infrastructure.")
        choiceParam("ENVIRONMENT", [ 'dev', 'uat', 'prod' ], "name of the environment to deploy infrastructure to.")
        stringParam("KEY_NAMESPACE", "adop-c", "The name space in the key store where all the keys and input parameters for stack are stored. The job also adds the CF outputs to the same key space after the pipeline finishes.")
    }
    wrappers {
        preBuildCleanup()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
    }
    label("java8")
    steps {
        copyArtifacts("Get_Cumulus") {
           buildSelector {
               buildNumber('${B}')
           }
        }
        shell('''#!/bin/bash -e
            |echo "=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=."
            |echo "Running smoke tests for deployed infra."
            |echo "=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=."
            |if [[ -f "${WORKSPACE}/test.sh" ]]; then
            |   chmod +x ${WORKSPACE}/test.sh
            |   ${WORKSPACE}/test.sh
            |else
            |   echo "=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=."
            |   echo "Couldn't find test.sh in the repository. No smoke test to run. Continue.."
            |   echo "=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=.=."
            |fi
            |'''.stripMargin()
        )
    }
}

