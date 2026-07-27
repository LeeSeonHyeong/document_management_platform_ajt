pipeline {
    agent any

    stages {
        stage('Hello') {
            steps {
                echo '안녕 Jenkins! 파이프라인이 돌고 있어요'
            }
        }
        stage('Test') {
            steps {
                echo '두 번째 단계도 정상'
                sh 'date'          // 서버에서 현재 시간 출력
                sh 'whoami'        // 어떤 유저로 실행되는지
            }
        }
    }

    post {
        success {
            echo '✅ 성공적으로 끝났습니다'
        }
        failure {
            echo '❌ 뭔가 실패했어요'
        }
    }
}