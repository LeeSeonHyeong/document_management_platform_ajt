pipeline {
    agent any

    environment {
        COMPOSE_PROJECT_NAME = 's15p11b106'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Inspect Project') {
            steps {
                sh '''
                    echo "Workspace:"
                    pwd
                    ls -la

                    if [ -d backend ]; then
                      echo "backend directory found"
                    else
                      echo "backend directory not found. Skipping backend build for now."
                    fi

                    if [ -d frontend ]; then
                      echo "frontend directory found"
                    else
                      echo "frontend directory not found. Skipping frontend build for now."
                    fi

                    if [ -f docker-compose.yml ] || [ -f compose.yml ]; then
                      echo "compose file found"
                    else
                      echo "compose file not found. Skipping deploy for now."
                    fi
                '''
            }
        }

        stage('Build Backend') {
            when {
                expression { fileExists('backend') }
            }
            steps {
                dir('backend') {
                    sh '''
                        if [ -f gradlew ]; then
                          chmod +x gradlew
                          ./gradlew clean build -x test
                        elif [ -f build.gradle ] || [ -f build.gradle.kts ]; then
                          gradle clean build -x test
                        else
                          echo "No Gradle build file found. Skipping backend build."
                        fi
                    '''
                }
            }
        }

        stage('Build Frontend') {
            when {
                expression { fileExists('frontend') }
            }
            steps {
                dir('frontend') {
                    sh '''
                        if [ -f package.json ]; then
                          npm ci || npm install
                          npm run build
                        else
                          echo "No package.json found. Skipping frontend build."
                        fi
                    '''
                }
            }
        }

        stage('Deploy') {
            when {
                expression { fileExists('docker-compose.yml') || fileExists('compose.yml') }
            }
            steps {
                sh '''
                    docker compose build
                    docker compose up -d
                '''
            }
        }

        stage('Deployment Status') {
            steps {
                sh '''
                    docker ps || true
                '''
            }
        }
    }

    post {
        success {
            echo 'CI/CD preparation pipeline completed successfully'
        }
        failure {
            echo 'CI/CD preparation pipeline failed'
        }
    }
}
