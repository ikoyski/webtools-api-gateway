pipeline {
    agent any
    tools {
        maven 'Maven-3.9.6'
    }
    environment {
        DOCKERHUB_CREDENTIALS = credentials('Dockerhub-Credentials') //username and password
        K8S_HOST_IP = credentials('K8s-Host-IP') //username and ip
            
        DOCKERHUB_IMAGE = 'ikoyski/webtools-api-gateway:latest'        
        DEPLOYMENT_FILENAME = 'Deploy-webtools-api-gateway-private.yaml'
    }
    stages {
        stage('Git Stuff') {
            steps {
                script {
                	checkout scmGit(
                		branches: [[name: '*/master']], 
                		extensions: [], 
                		userRemoteConfigs: [[url: 'https://github.com/ikoyski/webtools-api-gateway.git']]
                	)
                }
            }
        }
        stage('Maven Stuff') {
            steps {
                script {
                	sh 'mvn clean install'
                }
            }
        }
        stage('Docker Stuff') {
            steps {
                script {
                    sh 'docker build -t $DOCKERHUB_IMAGE .'
                    sh 'echo $DOCKERHUB_CREDENTIALS_PSW | docker login -u $DOCKERHUB_CREDENTIALS_USR --password-stdin'
                    sh 'docker push $DOCKERHUB_IMAGE'
                }
            }
        }
        stage('K8s Stuff') {
        	steps {        		
        		script {
        			sshagent(['K8s-Host-User-With-Key']) {
					sh 'scp -o StrictHostKeyChecking=no $DEPLOYMENT_FILENAME $K8S_HOST_IP_USR@$K8S_HOST_IP_PSW:/home/ikoyski'
					sh 'ssh -o StrictHostKeyChecking=no $K8S_HOST_IP_USR@$K8S_HOST_IP_PSW kubectl apply -f $DEPLOYMENT_FILENAME'
		        	}
			}
        	}
        }
    }
}
