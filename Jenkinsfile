pipeline {
  agent any

  parameters {
    choice(
      name: 'DEPLOYMENT_TARGET',
      choices: ['LOCAL', 'VM1', 'VM2', 'BOTH_VMS'],
      description: '배포 대상 선택 (GitHub push는 LOCAL으로 트리거됨)'
    )
    booleanParam(
      name: 'AUTO_CLEANUP',
      defaultValue: false,
      description: 'Stop stack automatically after build (runs docker compose down when checked)'
    )
  }

  environment {
    FRONTEND_DIR = "frontend"
    VM1_IP = "172.25.0.37"
    VM1_USER = "root"
    VM2_IP = "172.25.0.79"
    VM2_USER = "root"
    SSH_CREDENTIALS_ID = "payment-swelite-ssh"
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
          sh 'npm ci'
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
        script {
          if (params.DEPLOYMENT_TARGET == 'LOCAL') {
            sh '''
              # Inspect Docker build context
              pwd
              ls -la monitoring/prometheus/

              echo "=== Step 1: 기존 컨테이너 정리 (포트 충돌 방지) ==="
              # Jenkins와 ngrok을 제외한 모든 payment 관련 컨테이너 중지 및 제거
              docker ps -a --format "{{.Names}}" | grep -E "pay-|payment-swelite" | grep -v "pay-jenkins" | grep -v "pay-ngrok" | xargs -r docker stop || true
              docker ps -a --format "{{.Names}}" | grep -E "pay-|payment-swelite" | grep -v "pay-jenkins" | grep -v "pay-ngrok" | xargs -r docker rm || true
              echo "✓ 기존 컨테이너 정리 완료 (Jenkins 제외)"

              echo ""
              echo "=== Step 2: 인프라 서비스 시작 ==="
              docker compose up -d --no-recreate mariadb redis zookeeper kafka
              echo "✓ 인프라 서비스 안전하게 유지됨 (데이터 보존)"
              sleep 3

              echo ""
              echo "=== Step 3: 전체 서비스 빌드 및 시작 (Jenkins 제외) ==="
              docker compose build eureka-server gateway ingest-service consumer-worker settlement-worker refund-worker monitoring-service prometheus grafana frontend
              docker compose up -d eureka-server gateway ingest-service consumer-worker settlement-worker refund-worker monitoring-service prometheus grafana frontend
              echo "✓ 모든 서비스 기동 완료"

              echo ""
              echo "=== 배포 완료 ==="
              docker compose ps
            '''
          } else {
            sh '''
              echo "=== 배포 대상: ${DEPLOYMENT_TARGET} ==="

              # Docker 이미지 빌드만 수행
              echo "=== Docker 이미지 빌드 중 ==="
              docker compose build eureka-server gateway ingest-service consumer-worker settlement-worker refund-worker monitoring-service prometheus grafana frontend
              echo "✓ Docker 이미지 빌드 완료"
            '''
          }
        }
      }
    }

    stage('Deploy to VM') {
      when {
        expression { params.DEPLOYMENT_TARGET != 'LOCAL' }
      }
      steps {
        script {
          if (params.DEPLOYMENT_TARGET == 'VM1' || params.DEPLOYMENT_TARGET == 'BOTH_VMS') {
            echo "=== VM1 배포 중 (172.25.0.37) ==="
            sh '''
              ssh -i /var/jenkins_home/.ssh/id_rsa -o StrictHostKeyChecking=no root@172.25.0.37 << 'EOF'
                cd /root/Payment_SWElite

                echo "=== 기존 컨테이너 중지 ==="
                docker compose -f docker-compose.state.yml down || true

                echo "=== 최신 코드 가져오기 ==="
                git pull

                echo "=== VM1 서비스 시작 (docker-compose.state.yml) ==="
                docker compose -f docker-compose.state.yml up -d --build

                echo "=== VM1 배포 완료 ==="
                docker compose -f docker-compose.state.yml ps
              EOF
            '''
          }

          if (params.DEPLOYMENT_TARGET == 'VM2' || params.DEPLOYMENT_TARGET == 'BOTH_VMS') {
            echo "=== VM2 배포 중 (172.25.0.79) ==="
            sh '''
              ssh -i /var/jenkins_home/.ssh/id_rsa -o StrictHostKeyChecking=no root@172.25.0.79 << 'EOF'
                cd /root/Payment_SWElite

                echo "=== 기존 컨테이너 중지 ==="
                docker compose -f docker-compose.state.yml down || true

                echo "=== 최신 코드 가져오기 ==="
                git pull

                echo "=== VM2 서비스 시작 (docker-compose.state.yml) ==="
                docker compose -f docker-compose.state.yml up -d --build

                echo "=== VM2 배포 완료 ==="
                docker compose -f docker-compose.state.yml ps
              EOF
            '''
          }
        }
      }
    }

    stage('Wait for Services') {
      when {
        expression { params.DEPLOYMENT_TARGET == 'LOCAL' }
      }
      steps {
        sh '''
          check_ready() {
            url="$1"
            name="$2"
            attempts="${3:-60}"
            ready=0
            for i in $(seq 1 "$attempts"); do
              if docker compose run --rm --no-deps curl-client "curl -sf ${url} > /dev/null"; then
                echo "$name is ready!"
                ready=1
                break
              fi
              echo "Waiting for $name... attempt $i/$attempts"
              sleep 2
            done
            if [ "$ready" -ne 1 ]; then
              echo "$name failed to become ready within timeout."
              exit 1
            fi
          }

          check_ready http://ingest-service:8080/actuator/health ingest-service
          check_ready http://gateway:8080/actuator/health gateway
        '''
      }
    }

    stage('Smoke Test') {
      when {
        expression { params.DEPLOYMENT_TARGET == 'LOCAL' }
      }
      steps {
        sh '''
          echo "Running smoke test..."
          TS=$(date +%s)
          payload=$(printf '{"merchantId":"JENKINS","amount":1000,"currency":"KRW","idempotencyKey":"smoke-test-%s"}' "$TS")
          success=0
          for i in $(seq 1 10); do
            if docker compose run --rm --no-deps curl-client "curl -sSf -X POST -H 'Content-Type: application/json' -d '$payload' http://gateway:8080/api/payments/authorize > /dev/null"; then
              success=1
              break
            fi
            echo "Smoke test retry $i/10 - gateway not ready yet, waiting..."
            sleep 3
          done
          if [ "$success" -ne 1 ]; then
            echo "Smoke test failed after retries"
            exit 1
          fi
          echo "Smoke test completed successfully"
        '''
      }
    }

    stage('Circuit Breaker Test') {
      when {
        expression { params.DEPLOYMENT_TARGET == 'LOCAL' }
      }
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
