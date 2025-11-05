pipeline {
  agent any

  options {
    timestamps()
    disableConcurrentBuilds()
    ansiColor('xterm')
  }

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
    COMPOSE_CMD = "docker compose"
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

    stage('Build Container Images') {
      steps {
        script {
          String composeCmd = env.COMPOSE_CMD ?: 'docker compose'
          List<String> buildTargets = [
            'eureka-server',
            'ingest-service',
            'consumer-worker',
            'settlement-worker',
            'refund-worker',
            'gateway',
            'monitoring-service',
            'prometheus',
            'grafana',
            'frontend'
          ]
          sh label: 'docker compose build application images', script: """#!/bin/sh
set -eu
${composeCmd} build --pull ${buildTargets.join(' ')}
"""
        }
      }
    }

    stage('Rolling Update Containers') {
      steps {
        script {
          String composeCmd = env.COMPOSE_CMD ?: 'docker compose'

          def compose = { String args ->
            sh label: "${composeCmd} ${args}", script: """#!/bin/sh
set -eu
${composeCmd} ${args}
"""
          }

          def waitForService = { String service, int timeoutSec ->
            int attempts = Math.max(1, (int) Math.ceil(timeoutSec / 5.0))
            sh label: "wait for ${service}", script: """#!/bin/sh
set -eu
echo "Waiting for ${service} to reach running state..."
for attempt in $(seq 1 ${attempts}); do
  cid=\$(${composeCmd} ps -q ${service})
  if [ -z "\$cid" ]; then
    echo "${service}: container not created yet (\$attempt/${attempts})"
  else
    status=\$(docker inspect --format '{{.State.Status}}' \$cid)
    health=\$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \$cid)
    if [ "\$status" = "running" ]; then
      if [ "\$health" = "healthy" ] || [ "\$health" = "none" ]; then
        echo "${service}: running (status=\$status health=\$health)"
        exit 0
      fi
    fi
    if [ "\$status" = "exited" ] || [ "\$status" = "dead" ]; then
      echo "${service}: exited unexpectedly (status=\$status health=\$health)"
      ${composeCmd} logs ${service} || true
      exit 1
    fi
    echo "${service}: still starting (status=\$status health=\$health) (\$attempt/${attempts})"
  fi
  sleep 5
done
echo "${service} did not become ready within ${timeoutSec}s"
${composeCmd} logs ${service} || true
exit 1
"""
          }

          sh label: 'docker compose version', script: """#!/bin/sh
set -eu
${composeCmd} version
"""

          List<String> infraServices = ['mariadb', 'redis', 'zookeeper', 'kafka']
          echo "Ensuring infrastructure services are running: ${infraServices.join(', ')}"
          compose("up -d ${infraServices.join(' ')}")
          infraServices.each { waitForService(it, 90) }

          List<Map<String, Object>> serviceGroups = [
            [name: 'Service Discovery', services: ['eureka-server'], wait: 120],
            [name: 'Core APIs', services: ['ingest-service', 'gateway', 'monitoring-service'], wait: 180],
            [name: 'Workers', services: ['consumer-worker', 'settlement-worker', 'refund-worker'], wait: 150],
            [name: 'Monitoring & Frontend', services: ['prometheus', 'grafana', 'frontend'], wait: 120]
          ]

          serviceGroups.each { group ->
            echo "Rolling update: ${group.name}"
            List<String> services = group.services as List<String>
            int waitSeconds = (group.wait as Integer) ?: 120
            services.each { svc ->
              compose("up -d --no-deps --build --force-recreate ${svc}")
              waitForService(svc, waitSeconds)
            }
          }

          sh label: 'docker compose ps', script: """#!/bin/sh
set -eu
${composeCmd} ps
"""
        }
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
        String composeCmd = env.COMPOSE_CMD ?: 'docker compose'
        if (params.AUTO_CLEANUP == true) {
          echo 'Auto cleanup enabled - stopping all services...'
          sh label: 'compose down', script: """#!/bin/sh
set -eu
${composeCmd} down --remove-orphans || true
"""
        } else {
          echo 'Auto cleanup disabled - services remain running'
          echo 'Access services at:'
          echo '  - Frontend: http://localhost:5173'
          echo '  - API: http://localhost:8080'
          echo '  - Prometheus: http://localhost:9090'
          echo '  - Grafana: http://localhost:3000'
          echo "To stop manually: ${composeCmd} down"
        }
      }
    }
  }
}
