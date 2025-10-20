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
        script {
          sh '''
            docker compose down --remove-orphans || true
            docker compose up -d mariadb redis zookeeper kafka
            sleep 20
            docker compose up -d ingest-service consumer-worker frontend
          '''

          // Prometheus와 Grafana는 호스트 볼륨 경로로 수동 시작
          def hostWorkspace = sh(script: 'docker inspect pay-jenkins --format "{{ range .Mounts }}{{ if eq .Destination \\"/var/jenkins_home\\" }}{{ .Source }}{{ end }}{{ end }}"', returnStdout: true).trim()
          def monitoringPath = "${hostWorkspace}/workspace/Payment-SWElite-Pipeline/monitoring"
          // ingest-service 컨테이너가 속한 네트워크를 찾음
          def projectNetwork = sh(script: 'docker inspect payment-swelite-pipeline-ingest-service-1 --format "{{range .NetworkSettings.Networks}}{{.NetworkID}}{{end}}" | xargs docker network inspect --format "{{.Name}}"', returnStdout: true).trim()

          sh """
            # Prometheus 시작
            docker run -d --name pay-prometheus \
              --network ${projectNetwork} \
              -p 9090:9090 \
              -v "${monitoringPath}/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
              prom/prometheus:v2.54.1 \
              --config.file=/etc/prometheus/prometheus.yml || echo "Prometheus already running"

            # Grafana 시작
            docker run -d --name pay-grafana \
              --network ${projectNetwork} \
              -p 3000:3000 \
              -e GF_SECURITY_ADMIN_USER=admin \
              -e GF_SECURITY_ADMIN_PASSWORD=admin \
              -v "${monitoringPath}/grafana/provisioning/datasources:/etc/grafana/provisioning/datasources:ro" \
              -v "${monitoringPath}/grafana/provisioning/dashboards:/etc/grafana/provisioning/dashboards:ro" \
              -v "${monitoringPath}/grafana/dashboards:/etc/grafana/dashboards:ro" \
              grafana/grafana:10.4.3 || echo "Grafana already running"
          """
        }
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
          sh '''
            docker compose down --remove-orphans || true
            docker rm -f pay-prometheus pay-grafana || true
          '''
        } else {
          echo 'Auto cleanup disabled - services remain running'
          echo 'Access services at:'
          echo '  - Frontend: http://localhost:5173'
          echo '  - API: http://localhost:8080'
          echo '  - Prometheus: http://localhost:9090'
          echo '  - Grafana: http://localhost:3000'
          echo 'To stop manually: docker compose down && docker rm -f pay-prometheus pay-grafana'
        }
      }
    }
  }
}
