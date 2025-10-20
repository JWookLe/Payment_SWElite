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
          # monitoring 디렉토리가 workspace에 있는지 확인 (Git checkout으로 이미 있음)
          ls -la monitoring/

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
          echo ""
          echo "=========================================="
          echo "Build completed! Services are running."
          echo "=========================================="
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
