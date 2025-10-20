pipeline {
  agent any

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

    stage('Smoke Test') {
      steps {
        sh '''
          sleep 25
          docker compose exec -T ingest-service curl -X POST -H 'Content-Type: application/json' \
            -d '{"merchantId":"JENKINS","amount":1000,"currency":"KRW","idempotencyKey":"jenkins-1"}' \
            http://localhost:8080/payments/authorize
        '''
      }
    }

    stage('Load Test (k6)') {
      steps {
        sh '''
          rm -f loadtest/k6/summary.json || true
          docker run --rm \
            --network payment-swelite-pipeline_default \
            -v "$WORKSPACE/loadtest/k6":/k6 \
            -e BASE_URL=http://ingest-service:8080 \
            -e MERCHANT_ID=JENKINS \
            -e ENABLE_CAPTURE=false \
            -e ENABLE_REFUND=false \
            grafana/k6:0.49.0 run /k6/payment-scenario.js --summary-export=/k6/summary.json
        '''
        archiveArtifacts artifacts: 'loadtest/k6/summary.json', allowEmptyArchive: true
      }
    }
  }

  post {
    always {
      sh 'docker compose down --remove-orphans || true'
    }
  }
}
