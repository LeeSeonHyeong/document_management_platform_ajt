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
        AI_IMAGE = 'ajt-ai'
        DEPLOY_LOCK_FILE = '/var/lib/jenkins/ajt-deploy/deploy.lock'
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
                        script {
                            def scmVars = checkout scm
                            env.IMAGE_TAG = sh(
                                script: 'git rev-parse --short=12 HEAD',
                                returnStdout: true
                            ).trim()
                            env.DEPLOY_BRANCH = scmVars.GIT_BRANCH?.trim()
                            if (!env.DEPLOY_BRANCH) {
                                error('Git checkout 결과에서 배포 브랜치를 확인할 수 없습니다.')
                            }

                            def targetOutput = withEnv(["RESOLVED_BRANCH=${env.DEPLOY_BRANCH}"]) {
                                sh(
                                    script: 'bash scripts/resolve-deploy-target.sh "$RESOLVED_BRANCH"',
                                    returnStdout: true
                                ).trim()
                            }

                            targetOutput.readLines().each { line ->
                                def pair = line.split('=', 2)
                                switch (pair[0]) {
                                    case 'DEPLOY_ENV_FILE':
                                        env.DEPLOY_ENV_FILE = pair[1]
                                        break
                                    case 'DEPLOY_STATE_DIR':
                                        env.DEPLOY_STATE_DIR = pair[1]
                                        break
                                    case 'DEPLOY_HEALTHCHECK_URL':
                                        env.DEPLOY_HEALTHCHECK_URL = pair[1]
                                        break
                                    case 'COMPOSE_PROJECT_NAME':
                                        env.COMPOSE_PROJECT_NAME = pair[1]
                                        break
                                    case 'DEPLOY_TARGET_LABEL':
                                        env.DEPLOY_TARGET_LABEL = pair[1]
                                        break
                                    default:
                                        error("알 수 없는 배포 대상 설정입니다: ${pair[0]}")
                                }
                            }
                        }
                        echo "검증 대상 이미지 태그: ${env.IMAGE_TAG}"
                        echo "배포 대상: ${env.DEPLOY_TARGET_LABEL} (${env.COMPOSE_PROJECT_NAME})"
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

                stage('AI Test') {
                    steps {
                        sh '''
                            docker build --target test --tag "ajt-ai-test:${IMAGE_TAG}" ai
                            docker run --rm "ajt-ai-test:${IMAGE_TAG}"
                        '''
                    }
                }

                stage('Docker Build') {
                    steps {
                        sh '''
                            docker build --tag "${BACKEND_IMAGE}:${IMAGE_TAG}" backend
                            docker build --tag "${FRONTEND_IMAGE}:${IMAGE_TAG}" frontend
                            docker build --target runtime --tag "${AI_IMAGE}:${IMAGE_TAG}" ai
                        '''
                    }
                }
            }
        }

        stage('Deployment Approval') {
            agent none

            options {
                timeout(time: 24, unit: 'HOURS')
            }

            input {
                message "${env.IMAGE_TAG} 이미지를 ${env.DEPLOY_TARGET_LABEL} 환경에 배포하시겠습니까?"
                ok '배포 승인'
                submitterParameter 'APPROVED_BY'
            }

            steps {
                echo "배포 승인자: ${env.APPROVED_BY}"
            }
        }

        stage('Deploy') {
            agent any

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
                    DEPLOY_LOCK_FILE="${DEPLOY_LOCK_FILE}" \
                    COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME}" \
                    BACKEND_IMAGE="${BACKEND_IMAGE}" \
                    FRONTEND_IMAGE="${FRONTEND_IMAGE}" \
                    AI_IMAGE="${AI_IMAGE}" \
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
