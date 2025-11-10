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

    stage('Skip CI Check') {
      steps {
        script {
          String commitMessage = sh(script: "git log -1 --pretty=%B", returnStdout: true).trim()
          boolean skipRequested = commitMessage ==~ /(?i).*(\[(skip ci|ci skip)\]|skip-ci).*/
          if (skipRequested) {
            echo "Commit message requests CI skip: '${commitMessage}'"
            currentBuild.result = 'NOT_BUILT'
            error("Build skipped by commit message directive")
          }
        }
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
        sh './gradlew clean :backend:eureka-server:bootJar :backend:gateway:bootJar :backend:ingest-service:bootJar :backend:consumer-worker:bootJar :backend:settlement-worker:bootJar :backend:refund-worker:bootJar :backend:monitoring-service:bootJar --parallel'
      }
    }

    stage('Docker Build & Compose') {
      steps {
        sh '''
          # Inspect Docker build context
          pwd
          ls -la monitoring/prometheus/

          echo "=== Step 1: 기존 컨테이너 정리 (포트 충돌 방지) ==="
          # Jenkins와 ngrok를 제외한 모든 payment 관련 컨테이너 중지 및 제거
          docker ps -a --format "{{.Names}}" | grep -E "pay-|payment-swelite" | grep -v "pay-jenkins" | grep -v "pay-ngrok" | xargs -r docker stop || true
          docker ps -a --format "{{.Names}}" | grep -E "pay-|payment-swelite" | grep -v "pay-jenkins" | grep -v "pay-ngrok" | xargs -r docker rm || true
          echo "✓ 기존 컨테이너 정리 완료 (Jenkins 제외)"

          echo ""
          echo "=== Step 2: 인프라 서비스 시작 ==="
          # DB, Redis, Kafka는 이미 실행 중이면 그대로 유지
          # 처음 실행이거나 중지되어 있으면 시작
          docker compose up -d --no-recreate mariadb redis zookeeper kafka
          echo "✓ 인프라 서비스 안전하게 유지됨 (데이터 보존)"
          sleep 3

          echo ""
          echo "=== Step 3: 전체 서비스 빌드 및 시작 (Jenkins 제외) ==="
          # Jenkins와 ngrok는 이미 실행 중이므로 빌드에서 제외
          docker compose build eureka-server gateway ingest-service consumer-worker settlement-worker refund-worker monitoring-service prometheus grafana frontend
          docker compose up -d eureka-server gateway ingest-service consumer-worker settlement-worker refund-worker monitoring-service prometheus grafana frontend
          echo "✓ 모든 서비스 시작 완료"

          echo ""
          echo "=== 배포 완료 ==="
          docker compose ps
        '''
      }
    }

    stage('Wait for Services') {
      steps {
        script {
          echo 'Waiting for ingest-service to be ready...'
          sh '''
            # Health check with retry (max 120s)
            ready=0
            for i in $(seq 1 60); do
              if docker compose exec -T ingest-service curl -f http://ingest-service:8080/actuator/health 2>/dev/null; then
                echo "ingest-service is ready!"
                ready=1
                break
              fi
              echo "Waiting for ingest-service... attempt $i/60"
              sleep 2
            done
            if [ "$ready" -ne 1 ]; then
              echo "ingest-service failed to become ready within timeout."
              exit 1
            fi

            echo "Waiting for gateway to be ready..."
            ready=0
            for i in $(seq 1 60); do
              if docker compose exec -T gateway curl -f http://localhost:8080/actuator/health 2>/dev/null; then
                echo "gateway is ready!"
                ready=1
                break
              fi
              echo "Waiting for gateway... attempt $i/60"
              sleep 2
            done
            if [ "$ready" -ne 1 ]; then
              echo "gateway failed to become ready within timeout."
              exit 1
            fi
          '''
        }
      }
    }

    stage('Smoke Test') {
      steps {
        sh '''
          echo "Running smoke test..."
          TS=$(date +%s)
          payload=$(printf '{"merchantId":"JENKINS","amount":1000,"currency":"KRW","idempotencyKey":"smoke-test-%s"}' "$TS")
          success=0
          for i in $(seq 1 10); do
            if docker compose exec -T gateway sh -c "curl -sSf -X POST -H 'Content-Type: application/json' -d '$payload' http://localhost:8080/api/payments/authorize >/dev/null"; then
              success=1
              break
            fi
            echo \"Smoke test retry $i/5 - gateway not ready yet, waiting...\"
            sleep 3
          done
          if [ \"$success\" -ne 1 ]; then
            echo \"Smoke test failed after retries\"
            exit 1
          fi
          echo \"Smoke test completed successfully\"
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






