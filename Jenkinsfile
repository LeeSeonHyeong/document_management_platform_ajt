pipeline {
    agent none

    options {
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
        skipDefaultCheckout(true)
        timestamps()
    }

    environment {
        BACKEND_IMAGE = 'ajt-backend'
        FRONTEND_IMAGE = 'ajt-frontend'
        DEPLOY_ENV_FILE = '/var/lib/jenkins/ajt-secrets/prod.env'
        DEPLOY_STATE_DIR = '/var/lib/jenkins/ajt-deploy'
        DEPLOY_HEALTHCHECK_URL = 'https://127.0.0.1/api/v1/health'
        COMPOSE_PROJECT_NAME = 'ajt-prod'
    }

    stages {
        stage('Build and Test') {
            agent any

            options {
                timeout(time: 60, unit: 'MINUTES')
            }

            stages {
                stage('Checkout') {
                    steps {
                        checkout scm
                        script {
                            env.IMAGE_TAG = sh(
                                script: 'git rev-parse --short=12 HEAD',
                                returnStdout: true
                            ).trim()
                            env.IS_MASTER = sh(
                                script: '''
                                    branch="${BRANCH_NAME:-${GIT_BRANCH#origin/}}"
                                    if [ "$branch" = "master" ]; then
                                        printf true
                                    else
                                        printf false
                                    fi
                                ''',
                                returnStdout: true
                            ).trim()
                        }
                        echo "검증 대상 이미지 태그: ${env.IMAGE_TAG}"
                    }
                }

                stage('Backend Test') {
                    steps {
                        dir('backend') {
                            sh 'chmod +x gradlew'
                            sh './gradlew clean test --no-daemon'
                        }
                    }
                }

                stage('Frontend Check') {
                    steps {
                        dir('frontend') {
                            sh '''
                                docker run --rm \
                                    --user "$(id -u):$(id -g)" \
                                    --env HOME=/tmp \
                                    --volume "$PWD:/app" \
                                    --workdir /app \
                                    node:22-alpine \
                                    sh -c 'npm ci && npm run lint && npm run test && npm run build'
                            '''
                        }
                    }
                }

                stage('Docker Build') {
                    steps {
                        sh '''
                            docker build --tag "${BACKEND_IMAGE}:${IMAGE_TAG}" backend
                            docker build --tag "${FRONTEND_IMAGE}:${IMAGE_TAG}" frontend
                        '''
                    }
                }
            }
        }

        stage('Production Approval') {
            agent none

            when {
                beforeInput true
                expression {
                    env.IS_MASTER == 'true'
                }
            }

            options {
                timeout(time: 24, unit: 'HOURS')
            }

            input {
                message "${env.IMAGE_TAG} 이미지를 운영 443 포트에 배포하시겠습니까?"
                ok '운영 배포 승인'
                submitterParameter 'APPROVED_BY'
            }

            steps {
                echo "운영 배포 승인자: ${env.APPROVED_BY}"
            }
        }

        stage('Production Deploy') {
            agent any

            when {
                beforeAgent true
                expression {
                    env.IS_MASTER == 'true'
                }
            }

            options {
                timeout(time: 10, unit: 'MINUTES')
            }

            steps {
                checkout scm
                script {
                    def deployTag = sh(
                        script: 'git rev-parse --short=12 HEAD',
                        returnStdout: true
                    ).trim()
                    if (deployTag != env.IMAGE_TAG) {
                        error("빌드 커밋(${env.IMAGE_TAG})과 배포 커밋(${deployTag})이 다릅니다.")
                    }
                }
                sh 'chmod +x scripts/deploy.sh'
                sh '''
                    DEPLOY_ENV_FILE="${DEPLOY_ENV_FILE}" \
                    DEPLOY_STATE_DIR="${DEPLOY_STATE_DIR}" \
                    DEPLOY_HEALTHCHECK_URL="${DEPLOY_HEALTHCHECK_URL}" \
                    COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME}" \
                    BACKEND_IMAGE="${BACKEND_IMAGE}" \
                    FRONTEND_IMAGE="${FRONTEND_IMAGE}" \
                    bash scripts/deploy.sh "${IMAGE_TAG}"
                '''
            }
        }
    }

    post {
        success {
            echo "Pipeline 성공: ${env.IMAGE_TAG ?: 'unknown'}"
        }
        failure {
            echo "Pipeline 실패: ${env.IMAGE_TAG ?: 'unknown'}"
        }
        aborted {
            echo "Pipeline 중단: 승인 거절 또는 시간 초과"
        }
    }
}
