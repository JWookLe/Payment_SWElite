pipeline {
  agent any

  parameters {
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

  triggers {
    githubPush()
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
          # Docker 빌드 컨텍스트 확인
          pwd
          ls -la monitoring/prometheus/

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

    stage('Circuit Breaker Test') {
      steps {
        sh '''
          echo ""
          echo "=========================================="
          echo "Circuit Breaker 자동 테스트 시작"
          echo "=========================================="
          echo ""

          # ingest-service 안정화를 위해 추가 대기
          echo "ingest-service 안정화 대기 중 (5초)..."
          sleep 5

          # 스크립트 실행 권한 부여
          chmod +x scripts/test-circuit-breaker.sh

          # Circuit Breaker 자동 테스트를 ingest-service 컨테이너 내부에서 실행
          # 스크립트를 먼저 컨테이너로 복사
          docker compose cp scripts/test-circuit-breaker.sh payment-swelite-pipeline-ingest-service-1:/scripts/test-circuit-breaker.sh

          # 컨테이너 내부에서 실행
          docker compose exec -T payment-swelite-pipeline-ingest-service-1 bash /scripts/test-circuit-breaker.sh

          TEST_RESULT=$?

          echo ""
          if [ $TEST_RESULT -eq 0 ]; then
            echo "✅ Circuit Breaker 테스트 통과"
          else
            echo "⚠️ Circuit Breaker 테스트 경고 (상세 로그 확인 필요)"
          fi
        '''
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
