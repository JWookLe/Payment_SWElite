pipeline {
  agent any

  parameters {
    booleanParam(
      name: 'AUTO_CLEANUP',
      defaultValue: false,
      description: 'Stop stack automatically after build (runs docker compose down when checked)'
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
        sh './gradlew :backend:eureka-server:bootJar :backend:gateway:bootJar :backend:ingest-service:bootJar :backend:consumer-worker:bootJar'
      }
    }

    stage('Docker Build & Compose') {
      steps {
        sh '''
          # Inspect Docker build context
          pwd
          ls -la monitoring/prometheus/

          docker compose down --remove-orphans || true
          docker compose up -d eureka-server mariadb redis zookeeper kafka
          sleep 20
          docker compose up -d ingest-service consumer-worker gateway frontend prometheus grafana
        '''
      }
    }

    stage('Wait for Services') {
      steps {
        script {
          echo 'Waiting for ingest-service to be ready...'
          sh '''
            # Health check with retry (max 60s)
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
          curl -X POST -H 'Content-Type: application/json' \
            -d '{"merchantId":"JENKINS","amount":1000,"currency":"KRW","idempotencyKey":"smoke-test-'$(date +%s)'"}' \
            http://localhost:8080/api/payments/authorize
          echo "Smoke test completed successfully"
        '''
      }
    }

    stage('Circuit Breaker Test') {
      steps {
        sh '''
          echo ""
          echo "=========================================="
          echo "Circuit Breaker automated test start"
          echo "=========================================="
          echo ""

          echo "Waiting 5s for ingest-service to settle..."
          sleep 5

          chmod +x scripts/test-circuit-breaker.sh
          API_BASE_URL=http://localhost:8080 GATEWAY_BASE_URL=http://localhost:8080/api bash scripts/test-circuit-breaker.sh
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






