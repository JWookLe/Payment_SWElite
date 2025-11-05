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
        sh './gradlew :backend:eureka-server:bootJar :backend:gateway:bootJar :backend:ingest-service:bootJar :backend:consumer-worker:bootJar :backend:settlement-worker:bootJar :backend:refund-worker:bootJar :backend:monitoring-service:bootJar'
      }
    }

    stage('Docker Build & Compose') {
      steps {
        sh '''
          # Inspect Docker build context
          pwd
          ls -la monitoring/prometheus/

          # 프로젝트 이름 고정 (기존 실행 중인 컨테이너와 동일하게)
          export COMPOSE_PROJECT_NAME=payment-swelite-pipeline2

          # Rolling Update: 변경된 서비스만 재배포
          # docker compose가 기존 컨테이너를 인식하고 해당 서비스만 재생성

          echo "=== Step 1: Infrastructure (항상 유지) ==="
          # DB/Cache/MQ는 건드리지 않음 (데이터 보존)
          docker compose up -d mariadb redis zookeeper kafka
          echo "Infrastructure services verified."

          echo "=== Step 2: Eureka (서비스 레지스트리 재시작) ==="
          docker compose build eureka-server
          docker compose stop eureka-server && docker compose rm -f eureka-server || true
          docker compose up -d eureka-server
          echo "Waiting for Eureka to be ready..."
          sleep 15

          echo "=== Step 3: Application Services (변경된 것만 재배포) ==="
          # 각 서비스 빌드 및 재배포
          docker compose build gateway ingest-service monitoring-service
          docker compose stop gateway ingest-service monitoring-service && docker compose rm -f gateway ingest-service monitoring-service || true
          docker compose up -d gateway ingest-service monitoring-service
          echo "Waiting for services to register with Eureka..."
          sleep 10

          echo "=== Step 4: Workers (병렬 재배포) ==="
          docker compose build consumer-worker settlement-worker refund-worker
          docker compose stop consumer-worker settlement-worker refund-worker && docker compose rm -f consumer-worker settlement-worker refund-worker || true
          docker compose up -d consumer-worker settlement-worker refund-worker
          sleep 5

          echo "=== Step 5: Monitoring & Frontend ==="
          docker compose build prometheus grafana frontend
          docker compose stop prometheus grafana frontend && docker compose rm -f prometheus grafana frontend || true
          docker compose up -d prometheus grafana frontend
          echo "All services updated."
          sleep 5

          echo "=== Deployment Complete ==="
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






