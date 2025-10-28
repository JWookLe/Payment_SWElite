# Week 3 Summary

## Why Circuit Breaker
The kafka publisher inside `backend/ingest-service` must survive downstream outages. A circuit breaker wraps the publishing call and moves through these six states:

| State | Trigger | Behaviour |
| --- | --- | --- |
| **CLOSED** | Normal operation | all calls pass through. Metrics record success/failure counts. |
| **OPEN** | Failure rate or slow-call rate exceeds the configured threshold | new calls fail fast with `CallNotPermittedException`, protecting Kafka and the application thread pool. |
| **HALF_OPEN** | Wait duration in OPEN expires | only a small number of trial calls are allowed; success moves the breaker to CLOSED, failure moves it back to OPEN. |
| **DISABLED** | Breaker monitoring is turned off (rarely used in prod) | traffic flows but metrics keep accumulating. |
| **FORCED_OPEN / FORCED_HALF_OPEN** | Operator overrides | used to investigate or to keep the breaker open while debugging. |

We tuned the thresholds so that slow calls (>5s) or failure rate ≥50% open the breaker quickly, but healthy traffic closes it again without manual intervention.

## Implementation Details
- Added `Resilience4jConfig` and updated `PaymentEventPublisher` so that Kafka publishing is wrapped with `CircuitBreaker.decorateRunnable`. When the breaker is OPEN we skip Kafka but retain outbox records for later replay.
- Configured resilience properties in `backend/ingest-service/src/main/resources/application.yml` (failure-rate threshold, slow-call duration, minimum-number-of-calls, wait duration, half-open call limit).
- Documented the behaviour in `CIRCUIT_BREAKER_GUIDE.md` with state transitions, troubleshooting steps, and Prometheus/Grafana queries.

## Automation & Testing
- Refactored `scripts/test-circuit-breaker.sh` to run *inside* the Docker Compose network (`docker compose exec`) so health checks and API calls always target `ingest-service` even when Jenkins is remote.
- Script steps: warm-up (healthy traffic) → stop Kafka → send slow/failed requests → restart Kafka → send recovery calls. The script exits non‑zero if any phase behaves unexpectedly.
- Jenkinsfile gained a **“Circuit Breaker Test”** stage that runs the script after smoke tests. Builds now fail if the breaker does not trip and recover as expected.
- `docker-compose.yml` exposes an optional `ngrok` service (profile) which reads `NGROK_AUTHTOKEN` from `.env`. This allows GitHub Webhooks to reach local Jenkins automatically.

## Observability Enhancements
- Updated `monitoring/grafana/dashboards/payment-overview.json` so the **Circuit Breaker State** panel renders six tiles (CLOSED, OPEN, HALF_OPEN, DISABLED, FORCED_OPEN, FORCED_HALF_OPEN). Each tile shows a single metric; active state = value `1` with a green background, inactive states stay grey. *Remember to redeploy Grafana (`docker compose up -d grafana --force-recreate`) after changing the JSON so the provisioning pick ups the new layout.*
- Verified slow-call rate, failure rate, and not-permitted metrics are exposed through Prometheus (`resilience4j_*` series) and plotted on the dashboard.

## Documentation
- Updated README with notes on using the Circuit Breaker dashboard and the ngrok + GitHub Webhook automation flow.
- Added this `3Week.md` so future work retains a clear weekly changelog.

## Next Focus Areas
- Settlement / Reconciliation batch logic for financial postings.
- Evaluate Spring Cloud Gateway as API gateway front door.
- Compare Istio vs Linkerd for eventual service-mesh rollout.
