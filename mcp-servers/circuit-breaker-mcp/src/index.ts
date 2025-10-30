#!/usr/bin/env node

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import axios from "axios";

const API_BASE_URL = process.env.API_BASE_URL || "http://localhost:8080";
const PROMETHEUS_URL = process.env.PROMETHEUS_URL || "http://localhost:9090";

interface CircuitBreakerInfo {
  state: string;
  numberOfSuccessfulCalls: number;
  numberOfFailedCalls: number;
  numberOfSlowCalls: number;
  numberOfSlowSuccessfulCalls: number;
  numberOfSlowFailedCalls: number;
  numberOfBufferedCalls: number;
  numberOfNotPermittedCalls: number;
  failureRate: string;
  slowCallRate: string;
}

interface CircuitBreakerStatus {
  "kafka-publisher": CircuitBreakerInfo;
}

class CircuitBreakerMCPServer {
  private server: Server;

  constructor() {
    this.server = new Server(
      {
        name: "payment-circuit-breaker-mcp",
        version: "1.0.0",
      },
      {
        capabilities: {
          tools: {},
        },
      }
    );

    this.setupHandlers();
    this.setupErrorHandling();
  }

  private setupErrorHandling(): void {
    this.server.onerror = (error) => {
      console.error("[MCP Error]", error);
    };

    process.on("SIGINT", async () => {
      await this.server.close();
      process.exit(0);
    });
  }

  private setupHandlers(): void {
    this.server.setRequestHandler(ListToolsRequestSchema, async () => ({
      tools: [
        {
          name: "get_circuit_breaker_status",
          description:
            "Get current Circuit Breaker status for kafka-publisher including state (CLOSED/OPEN/HALF_OPEN), call counts, and failure/slow call rates",
          inputSchema: {
            type: "object",
            properties: {},
          },
        },
        {
          name: "diagnose_circuit_breaker",
          description:
            "Diagnose Circuit Breaker issues by analyzing metrics and providing recommendations. Returns root cause analysis and suggested actions.",
          inputSchema: {
            type: "object",
            properties: {},
          },
        },
        {
          name: "check_kafka_health",
          description:
            "Check if Kafka is healthy by examining Circuit Breaker state and recent call patterns. Returns simple yes/no with explanation.",
          inputSchema: {
            type: "object",
            properties: {},
          },
        },
        {
          name: "analyze_failure_pattern",
          description:
            "Analyze failure patterns over time using Prometheus metrics. Shows trend of failures, slow calls, and circuit breaker state transitions.",
          inputSchema: {
            type: "object",
            properties: {
              time_range: {
                type: "string",
                description:
                  "Time range for analysis (e.g., '1h', '30m', '5m'). Default: '1h'",
                default: "1h",
              },
            },
          },
        },
      ],
    }));

    this.server.setRequestHandler(CallToolRequestSchema, async (request) => {
      try {
        switch (request.params.name) {
          case "get_circuit_breaker_status":
            return await this.getCircuitBreakerStatus();

          case "diagnose_circuit_breaker":
            return await this.diagnoseCircuitBreaker();

          case "check_kafka_health":
            return await this.checkKafkaHealth();

          case "analyze_failure_pattern":
            return await this.analyzeFailurePattern(
              (request.params.arguments as { time_range?: string })
                ?.time_range || "1h"
            );

          default:
            throw new Error(`Unknown tool: ${request.params.name}`);
        }
      } catch (error) {
        const errorMessage =
          error instanceof Error ? error.message : String(error);
        return {
          content: [
            {
              type: "text",
              text: `Error: ${errorMessage}`,
            },
          ],
        };
      }
    });
  }

  private async getCircuitBreakerStatus() {
    const response = await axios.get(
      `${API_BASE_URL}/monitoring/circuit-breaker/status`
    );
    const status = response.data;

    const stateEmoji =
      status.state === "CLOSED"
        ? "✅"
        : status.state === "OPEN"
        ? "❌"
        : "⚠️";

    const result = `
${stateEmoji} Circuit Breaker Status: ${status.state}

📊 Call Statistics:
  - Successful Calls: ${status.numberOfSuccessfulCalls || 0}
  - Failed Calls: ${status.numberOfFailedCalls || 0}
  - Slow Calls: ${status.numberOfSlowCalls || 0}
    - Slow Successful: ${status.numberOfSlowSuccessfulCalls || 0}
    - Slow Failed: ${status.numberOfSlowFailedCalls || 0}
  - Buffered Calls: ${status.numberOfBufferedCalls || 0}
  - Rejected Calls: ${status.numberOfNotPermittedCalls || 0}

📈 Metrics:
  - Failure Rate: ${status.failureRate || "0.00%"}
  - Slow Call Rate: ${status.slowCallRate || "0.00%"}

💡 Analysis:
  ${status.analysis?.description || "Normal operation"}
    `.trim();

    return {
      content: [
        {
          type: "text",
          text: result,
        },
      ],
    };
  }

  private async diagnoseCircuitBreaker() {
    const response = await axios.get(
      `${API_BASE_URL}/monitoring/circuit-breaker/status`
    );
    const status = response.data;

    const failureRate = parseFloat(status.failureRate.replace("%", ""));
    const slowCallRate = parseFloat(status.slowCallRate.replace("%", ""));

    const issues: string[] = [];
    const recommendations: string[] = [];

    // Analyze state
    if (status.state === "OPEN") {
      issues.push(
        "🔴 Circuit Breaker is OPEN - Kafka publishing is blocked"
      );
      recommendations.push(
        "1. Check if Kafka broker is running: docker compose ps kafka"
      );
      recommendations.push(
        "2. Check Kafka logs: docker compose logs kafka --tail=50"
      );
      recommendations.push(
        "3. Wait for automatic transition to HALF_OPEN (30 seconds)"
      );
    } else if (status.state === "HALF_OPEN") {
      issues.push(
        "🟡 Circuit Breaker is HALF_OPEN - Testing Kafka recovery"
      );
      recommendations.push("1. Monitor next 3 test calls carefully");
      recommendations.push(
        "2. If calls succeed, will transition to CLOSED automatically"
      );
      recommendations.push(
        "3. If calls fail, will reopen immediately (wait another 30s)"
      );
    }

    // Analyze failure rate
    if (failureRate >= 50) {
      issues.push(`⚠️ High failure rate: ${status.failureRate}`);
      recommendations.push(
        "- Check Kafka connectivity and broker health"
      );
      recommendations.push("- Review application logs for KafkaException");
    }

    // Analyze slow call rate
    if (slowCallRate >= 50) {
      issues.push(`⚠️ High slow call rate: ${status.slowCallRate}`);
      recommendations.push(
        "- Kafka may be overloaded or network latency is high"
      );
      recommendations.push(
        "- Check Prometheus metrics for kafka producer latency"
      );
      recommendations.push(
        "- Consider increasing timeout thresholds if latency is expected"
      );
    }

    // Analyze rejected calls
    if (status.numberOfNotPermittedCalls > 0) {
      issues.push(
        `⚠️ ${status.numberOfNotPermittedCalls} calls were rejected by Circuit Breaker`
      );
      recommendations.push(
        "- These payments were saved to outbox_event table but NOT published"
      );
      recommendations.push(
        "- After Kafka recovery, manually republish using OutboxEventPublisher"
      );
    }

    // Healthy state
    if (issues.length === 0) {
      issues.push("✅ Circuit Breaker is healthy");
      recommendations.push("No action required - system is operating normally");
    }

    const result = `
🔍 Circuit Breaker Diagnostic Report

Current State: ${status.state}
Failure Rate: ${status.failureRate}
Slow Call Rate: ${status.slowCallRate}

📋 Issues Detected:
${issues.map((issue) => `  ${issue}`).join("\n")}

💡 Recommendations:
${recommendations.map((rec) => `  ${rec}`).join("\n")}

📊 Full Statistics:
  - Total Calls: ${status.numberOfBufferedCalls}
  - Failed: ${status.numberOfFailedCalls}
  - Slow: ${status.numberOfSlowCalls}
  - Rejected: ${status.numberOfNotPermittedCalls}
    `.trim();

    return {
      content: [
        {
          type: "text",
          text: result,
        },
      ],
    };
  }

  private async checkKafkaHealth() {
    try {
      const response = await axios.get(
        `${API_BASE_URL}/monitoring/circuit-breaker/status`
      );
      const status = response.data;

      const failureRate = parseFloat(status.failureRate.replace("%", ""));
      const isHealthy =
        status.state === "CLOSED" && failureRate < 10 && status.numberOfFailedCalls === 0;

      let healthStatus: string;
      let explanation: string;

      if (isHealthy) {
        healthStatus = "✅ YES - Kafka is healthy";
        explanation = `Circuit Breaker is CLOSED with ${status.failureRate} failure rate. All systems operational.`;
      } else if (status.state === "OPEN") {
        healthStatus = "❌ NO - Kafka is DOWN or unreachable";
        explanation = `Circuit Breaker is OPEN. Kafka publishing has failed repeatedly. Check Kafka broker status.`;
      } else if (status.state === "HALF_OPEN") {
        healthStatus = "⚠️ RECOVERING - Kafka is being tested";
        explanation = `Circuit Breaker is testing Kafka recovery. Next ${3 - status.numberOfBufferedCalls} calls will determine if it reopens.`;
      } else {
        healthStatus = failureRate > 20 ? "⚠️ DEGRADED" : "✅ YES";
        explanation = `Circuit Breaker is CLOSED but failure rate is ${status.failureRate}. Monitor closely.`;
      }

      const result = `
${healthStatus}

${explanation}

Recent Activity:
  - Successful: ${status.numberOfSuccessfulCalls}
  - Failed: ${status.numberOfFailedCalls}
  - Slow: ${status.numberOfSlowCalls}
  - Failure Rate: ${status.failureRate}
      `.trim();

      return {
        content: [
          {
            type: "text",
            text: result,
          },
        ],
      };
    } catch (error) {
      return {
        content: [
          {
            type: "text",
            text: `❌ Cannot determine Kafka health - API unreachable\n\nError: ${error instanceof Error ? error.message : String(error)}`,
          },
        ],
      };
    }
  }

  private async analyzeFailurePattern(timeRange: string) {
    try {
      // Query Prometheus for Circuit Breaker metrics over time
      const queries = [
        {
          name: "Failure Rate",
          query: `resilience4j_circuitbreaker_failure_rate{name="kafka-publisher"}`,
        },
        {
          name: "Slow Call Rate",
          query: `resilience4j_circuitbreaker_slow_call_rate{name="kafka-publisher"}`,
        },
        {
          name: "State",
          query: `resilience4j_circuitbreaker_state{name="kafka-publisher"}`,
        },
        {
          name: "Failed Calls",
          query: `resilience4j_circuitbreaker_calls_total{name="kafka-publisher",kind="failed"}`,
        },
      ];

      const results = await Promise.all(
        queries.map(async (q) => {
          try {
            const response = await axios.get(
              `${PROMETHEUS_URL}/api/v1/query`,
              {
                params: {
                  query: `${q.query}[${timeRange}]`,
                },
              }
            );
            return { name: q.name, data: response.data };
          } catch {
            return { name: q.name, data: null };
          }
        })
      );

      let analysis = `
📊 Failure Pattern Analysis (Last ${timeRange})

`;

      results.forEach((result) => {
        if (result.data?.data?.result?.[0]) {
          const metric = result.data.data.result[0];
          const values = metric.values || [];
          analysis += `\n${result.name}:\n`;
          if (values.length > 0) {
            const latest = values[values.length - 1][1];
            const earliest = values[0][1];
            analysis += `  - Latest: ${latest}\n`;
            analysis += `  - Earliest: ${earliest}\n`;
            analysis += `  - Data points: ${values.length}\n`;
          } else {
            analysis += `  - No data available\n`;
          }
        } else {
          analysis += `\n${result.name}: No data available\n`;
        }
      });

      analysis += `\n💡 Note: For detailed visualization, check Grafana dashboard at http://localhost:3000`;

      return {
        content: [
          {
            type: "text",
            text: analysis.trim(),
          },
        ],
      };
    } catch (error) {
      return {
        content: [
          {
            type: "text",
            text: `⚠️ Could not analyze failure pattern\n\nError: ${error instanceof Error ? error.message : String(error)}\n\nMake sure Prometheus is running at ${PROMETHEUS_URL}`,
          },
        ],
      };
    }
  }

  private explainState(state: string): string {
    switch (state) {
      case "CLOSED":
        return "Normal operation. Requests are flowing through to Kafka.";
      case "OPEN":
        return "Kafka publishing is blocked due to high failure rate. Calls are rejected immediately for 30 seconds.";
      case "HALF_OPEN":
        return "Testing phase. Allowing 3 test calls to check if Kafka has recovered.";
      default:
        return "Unknown state";
    }
  }

  async run(): Promise<void> {
    const transport = new StdioServerTransport();
    await this.server.connect(transport);
    console.error("Circuit Breaker MCP Server running on stdio");
  }
}

const server = new CircuitBreakerMCPServer();
server.run().catch(console.error);
