pipeline {
  agent any

  environment {
    FRONTEND_DIR = "frontend"
    BACKEND_DIR = "backend"
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
        dir(BACKEND_DIR) {
          sh 'gradle build'
        }
      }
    }

    stage('Docker Build & Compose') {
      steps {
        sh '''
          docker compose down --remove-orphans || true
          docker compose up -d mariadb redis zookeeper kafka
          sleep 20
          docker compose up -d ingest-service consumer-worker frontend
        '''
      }
    }

    stage('Smoke Test') {
      steps {
        sh '''
          sleep 25
          curl -X POST -H 'Content-Type: application/json' \
            -d '{"merchantId":"JENKINS","amount":1000,"currency":"KRW","idempotencyKey":"jenkins-1"}' \
            http://localhost:8080/payments/authorize
        '''
      }
    }
  }

}
