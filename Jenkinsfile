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

          # 고정된 프로젝트 이름 사용 (수동 실행과 Jenkins 자동 빌드가 같은 이름 공유)
          export COMPOSE_PROJECT_NAME=payment-swelite

          echo "==================================================="
          echo "Production-Grade Rolling Update 시작"
          echo "==================================================="

          echo "=== Step 1: 인프라 서비스 확인 (절대 재시작 안함 - 데이터 보존) ==="
          # DB, Redis, Kafka는 이미 실행 중이면 그대로 유지
          # 처음 실행이거나 중지되어 있으면 시작
          docker compose up -d --no-recreate mariadb redis zookeeper kafka
          echo "✓ 인프라 서비스 안전하게 유지됨 (데이터 보존)"
          sleep 3

          echo ""
          echo "=== Step 2: Eureka 서버 업데이트 (서비스 레지스트리) ==="
          docker compose build eureka-server
          # --force-recreate: 새 이미지로 컨테이너 교체 (Rolling Update)
          # --no-deps: 의존성(DB 등)은 건드리지 않음
          docker compose up -d --force-recreate --no-deps eureka-server
          echo "✓ Eureka 서버 재배포 완료"
          echo "Eureka가 준비될 때까지 대기 중..."
          sleep 20

          echo ""
          echo "=== Step 3: 코어 애플리케이션 서비스 업데이트 ==="
          echo "Building: gateway, ingest-service, monitoring-service"
          docker compose build gateway ingest-service monitoring-service

          echo "Rolling Update: gateway (API 게이트웨이)"
          docker compose up -d --force-recreate --no-deps gateway
          sleep 5

          echo "Rolling Update: ingest-service (결제 요청 수신)"
          docker compose up -d --force-recreate --no-deps ingest-service
          sleep 5

          echo "Rolling Update: monitoring-service (모니터링 API)"
          docker compose up -d --force-recreate --no-deps monitoring-service
          echo "✓ 코어 서비스 재배포 완료"
          echo "서비스들이 Eureka에 등록될 때까지 대기 중..."
          sleep 10

          echo ""
          echo "=== Step 4: 백그라운드 워커 업데이트 ==="
          echo "Building: consumer-worker, settlement-worker, refund-worker"
          docker compose build consumer-worker settlement-worker refund-worker

          echo "Rolling Update: consumer-worker (결제 처리)"
          docker compose up -d --force-recreate --no-deps consumer-worker

          echo "Rolling Update: settlement-worker (정산 처리)"
          docker compose up -d --force-recreate --no-deps settlement-worker

          echo "Rolling Update: refund-worker (환불 처리)"
          docker compose up -d --force-recreate --no-deps refund-worker
          echo "✓ 워커 서비스 재배포 완료"
          sleep 5

          echo ""
          echo "=== Step 5: 모니터링 & 프론트엔드 업데이트 ==="
          echo "Building: prometheus, grafana, frontend"
          docker compose build prometheus grafana frontend

          echo "Rolling Update: prometheus (메트릭 수집)"
          docker compose up -d --force-recreate --no-deps prometheus

          echo "Rolling Update: grafana (대시보드)"
          docker compose up -d --force-recreate --no-deps grafana

          echo "Rolling Update: frontend (사용자 UI)"
          docker compose up -d --force-recreate --no-deps frontend
          echo "✓ 모니터링 & 프론트엔드 재배포 완료"
          sleep 5

          echo ""
          echo "==================================================="
          echo "✓ Rolling Update 완료 - 전체 서비스 상태:"
          echo "==================================================="
          docker compose ps

          echo ""
          echo "배포 전략 요약:"
          echo "- 인프라 (DB/Redis/Kafka): 재시작 안함 (데이터 보존)"
          echo "- 애플리케이션 서비스: 순차적 Rolling Update"
          echo "- 각 서비스: 이전 버전 종료 → 새 버전 시작"
          echo "- 다운타임: 최소화 (서비스별 5-10초)"
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






