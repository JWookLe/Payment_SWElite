pipeline {
  agent any

  parameters {
    booleanParam(
      name: 'RUN_LOAD_TEST',
      defaultValue: false,
      description: 'k6 부하 테스트 실행 여부 (체크하면 실행)'
    )
    booleanParam(
      name: 'AUTO_CLEANUP',
      defaultValue: false,
      description: '빌드 완료 후 자동으로 서비스 중지 (체크하면 docker compose down 실행)'
    )
  }

  environment {
    FRONTEND_DIR = "frontend"
    BACKEND_DIR = "backend"
  }

  tools {
    gradle 'gradle-8.13'
  }

  stages {
    stage('Checkout') {
      steps {
        checkout scm
      }
    }

    stage('Install Frontend Dependencies') {
      steps {
        dir(FRONTEND_DIR) {
          sh 'npm install'
          sh 'npm run build'
        }
      }
    }

    stage('Build Spring Boot Services') {
      steps {
        sh './gradlew :backend:ingest-service:bootJar :backend:consumer-worker:bootJar'
      }
    }

    stage('Docker Build & Compose') {
      steps {
        sh '''
          docker compose down --remove-orphans || true
          docker compose up -d mariadb redis zookeeper kafka
          sleep 20
          docker compose up -d ingest-service consumer-worker frontend prometheus grafana
        '''
      }
    }

    stage('Wait for Services') {
      steps {
        script {
          echo 'Waiting for ingest-service to be ready...'
          sh '''
            # Health check with retry (최대 60초)
            for i in $(seq 1 30); do
              if docker compose exec -T ingest-service curl -f http://localhost:8080/actuator/health 2>/dev/null; then
                echo "ingest-service is ready!"
                break
              fi
              echo "Waiting for ingest-service... attempt $i/30"
              sleep 2
            done
          '''
        }
      }
    }

    stage('Smoke Test') {
      steps {
        sh '''
          echo "Running smoke test..."
          docker compose exec -T ingest-service curl -X POST -H 'Content-Type: application/json' \
            -d '{"merchantId":"JENKINS","amount":1000,"currency":"KRW","idempotencyKey":"smoke-test-'$(date +%s)'"}' \
            http://localhost:8080/payments/authorize
          echo "Smoke test completed successfully"
        '''
      }
    }

    stage('Load Test (k6)') {
      when {
        expression {
          return params.RUN_LOAD_TEST == true
        }
      }
      steps {
        script {
          echo 'Preparing for load test...'
          sleep 5  // 추가 안정화 시간

          // Jenkins workspace의 실제 호스트 경로 찾기
          def hostWorkspace = sh(script: 'docker inspect pay-jenkins --format "{{ range .Mounts }}{{ if eq .Destination \\"/var/jenkins_home\\" }}{{ .Source }}{{ end }}{{ end }}"', returnStdout: true).trim()
          def k6Path = "${hostWorkspace}/workspace/Payment-SWElite-Pipeline/loadtest/k6"

          sh """
            rm -f loadtest/k6/summary.json || true
            echo "Starting k6 load test..."
            docker run --rm \
              --network payment-swelite-pipeline_default \
              -v "${k6Path}":/k6 \
              -e BASE_URL=http://ingest-service:8080 \
              -e MERCHANT_ID=JENKINS \
              -e ENABLE_CAPTURE=false \
              -e ENABLE_REFUND=false \
              grafana/k6:0.49.0 run /k6/payment-scenario.js --summary-export=/k6/summary.json
          """
        }
        archiveArtifacts artifacts: 'loadtest/k6/summary.json', allowEmptyArchive: true
      }
    }
  }

  post {
    always {
      script {
        if (params.AUTO_CLEANUP == true) {
          echo 'Auto cleanup enabled - stopping all services...'
          sh 'docker compose down --remove-orphans || true'
        } else {
          echo 'Auto cleanup disabled - services remain running'
          echo 'Access services at:'
          echo '  - Frontend: http://localhost:5173'
          echo '  - API: http://localhost:8080'
          echo '  - Prometheus: http://localhost:9090'
          echo '  - Grafana: http://localhost:3000'
          echo 'To stop manually: docker compose down'
        }
      }
    }
  }
}
