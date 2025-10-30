#!/usr/bin/env node

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
  Tool,
} from "@modelcontextprotocol/sdk/types.js";
import axios from "axios";

const API_BASE_URL = process.env.API_BASE_URL || "http://localhost:8082";

class SystemHealthMcpServer {
  private server: Server;

  constructor() {
    this.server = new Server(
      {
        name: "system-health-mcp",
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
      tools: this.getTools(),
    }));

    this.server.setRequestHandler(CallToolRequestSchema, async (request) => {
      const { name, arguments: args } = request.params;

      try {
        switch (name) {
          case "check_all_services":
            return await this.checkAllServices();
          case "get_system_overview":
            return await this.getSystemOverview();
          case "diagnose_issues":
            return await this.diagnoseIssues();
          default:
            throw new Error(`Unknown tool: ${name}`);
        }
      } catch (error: any) {
        return {
          content: [
            {
              type: "text",
              text: `Error: ${error.message}`,
            },
          ],
        };
      }
    });
  }

  private getTools(): Tool[] {
    return [
      {
        name: "check_all_services",
        description:
          "전체 시스템의 모든 서비스 상태를 한 번에 확인합니다 (Circuit Breaker, Kafka, Redis, Database 등)",
        inputSchema: {
          type: "object",
          properties: {},
          required: [],
        },
      },
      {
        name: "get_system_overview",
        description:
          "시스템 전체 개요를 보여줍니다 (서비스 수, 헬스 상태, 주요 지표)",
        inputSchema: {
          type: "object",
          properties: {},
          required: [],
        },
      },
      {
        name: "diagnose_issues",
        description:
          "현재 시스템에서 발생한 문제를 자동으로 탐지하고 진단합니다",
        inputSchema: {
          type: "object",
          properties: {},
          required: [],
        },
      },
    ];
  }

  private async checkAllServices() {
    // 병렬로 모든 헬스 체크 실행
    const checks = await Promise.allSettled([
      this.checkCircuitBreaker(),
      this.checkKafka(),
      this.checkRedis(),
      this.checkDatabase(),
    ]);

    let result = `🏥 전체 시스템 헬스 체크\n\n`;

    for (const check of checks) {
      if (check.status === "fulfilled") {
        result += check.value + "\n\n";
      } else {
        result += `❌ 체크 실패: ${check.reason}\n\n`;
      }
    }

    // 전체 상태 판정
    const allHealthy = checks.every(
      (check) => check.status === "fulfilled" && check.value.includes("✅")
    );

    if (allHealthy) {
      result += `\n🎉 모든 서비스가 정상 작동 중입니다!`;
    } else {
      result += `\n⚠️ 일부 서비스에 문제가 있습니다. 상세 내용을 확인하세요.`;
    }

    return {
      content: [{ type: "text", text: result }],
    };
  }

  private async checkCircuitBreaker(): Promise<string> {
    try {
      const response = await axios.get(
        `${API_BASE_URL}/monitoring/circuit-breaker/health`
      );
      const data = response.data;

      return data.healthy
        ? `✅ Circuit Breaker: ${data.state} (${data.failureRate} 실패율)`
        : `❌ Circuit Breaker: ${data.message}`;
    } catch (error) {
      return `❌ Circuit Breaker: 연결 실패`;
    }
  }

  private async checkKafka(): Promise<string> {
    try {
      const response = await axios.get(
        `${API_BASE_URL}/monitoring/kafka/health`
      );
      const data = response.data;

      return data.healthy
        ? `✅ Kafka: ${data.brokerCount}개 브로커 정상`
        : `❌ Kafka: ${data.message}`;
    } catch (error) {
      return `❌ Kafka: 연결 실패`;
    }
  }

  private async checkRedis(): Promise<string> {
    try {
      const response = await axios.get(
        `${API_BASE_URL}/monitoring/redis/cache-stats`
      );
      const data = response.data;

      return `✅ Redis: ${data.totalKeys}개 키 저장 중`;
    } catch (error) {
      return `❌ Redis: 연결 실패`;
    }
  }

  private async checkDatabase(): Promise<string> {
    try {
      const response = await axios.get(
        `${API_BASE_URL}/monitoring/database/reconciliation`
      );
      const data = response.data;

      return data.balanced
        ? `✅ Database: 복식부기 균형 (${data.totalDebits} = ${data.totalCredits})`
        : `⚠️ Database: 복식부기 불균형 감지`;
    } catch (error) {
      return `❌ Database: 연결 실패`;
    }
  }

  private async getSystemOverview() {
    try {
      // 병렬로 주요 지표 수집
      const [circuitBreakerRes, kafkaRes, redisRes, dbStatsRes] =
        await Promise.all([
          axios.get(`${API_BASE_URL}/monitoring/circuit-breaker/status`),
          axios.get(`${API_BASE_URL}/monitoring/kafka/topics`),
          axios.get(`${API_BASE_URL}/monitoring/redis/cache-stats`),
          axios.get(
            `${API_BASE_URL}/monitoring/database/statistics?timeRange=today`
          ),
        ]);

      const cbData = circuitBreakerRes.data;
      const kafkaData = kafkaRes.data;
      const redisData = redisRes.data;
      const dbData = dbStatsRes.data;

      const result = `
📊 시스템 전체 개요

🔧 Circuit Breaker:
   상태: ${cbData.state}
   실패율: ${cbData.failureRate}
   느린 호출: ${cbData.numberOfSlowCalls}

📨 Kafka:
   토픽 수: ${kafkaData.count}개
   토픽: ${kafkaData.topics.join(", ")}

💾 Redis:
   총 키: ${redisData.totalKeys}개
   Rate Limit: ${redisData.rateLimitKeys}개
   멱등성 캐시: ${redisData.idempotencyKeys}개

💳 Database (오늘):
   총 거래: ${dbData.overall.total_count}건
   총 금액: ${dbData.overall.total_amount.toLocaleString()} KRW
   평균 금액: ${dbData.overall.avg_amount.toLocaleString()} KRW

💡 더 자세한 정보는 각 MCP 서버의 전용 도구를 사용하세요.
      `.trim();

      return {
        content: [{ type: "text", text: result }],
      };
    } catch (error: any) {
      return {
        content: [
          {
            type: "text",
            text: `❌ 시스템 개요 조회 실패: ${error.message}`,
          },
        ],
      };
    }
  }

  private async diagnoseIssues() {
    const issues: string[] = [];

    // Circuit Breaker 체크
    try {
      const cbRes = await axios.get(
        `${API_BASE_URL}/monitoring/circuit-breaker/diagnose`
      );
      const cbData = cbRes.data;

      if (cbData.state === "OPEN" || cbData.state === "HALF_OPEN") {
        issues.push(`⚠️ Circuit Breaker ${cbData.state} 상태`);
        if (cbData.recommendations) {
          issues.push(...cbData.recommendations.map((r: string) => `  • ${r}`));
        }
      }
    } catch (error) {
      issues.push(`❌ Circuit Breaker 진단 실패`);
    }

    // Consumer Lag 체크 (payment-consumer-group 가정)
    try {
      const lagRes = await axios.get(
        `${API_BASE_URL}/monitoring/kafka/consumer-lag`,
        {
          params: { groupId: "payment-consumer-group" },
        }
      );
      const lagData = lagRes.data;

      if (lagData.status === "WARNING") {
        issues.push(
          `⚠️ Consumer Lag 경고: ${lagData.totalLag}개 메시지 지연`
        );
      }
    } catch (error) {
      // Consumer Group이 없을 수도 있음 (정상)
    }

    // DLQ 체크
    try {
      const dlqRes = await axios.get(
        `${API_BASE_URL}/monitoring/kafka/dlq-messages`,
        {
          params: { limit: 1 },
        }
      );
      const dlqData = dlqRes.data;

      if (dlqData.count > 0) {
        issues.push(`⚠️ DLQ에 ${dlqData.count}개 실패 메시지 존재`);
      }
    } catch (error) {
      // DLQ 조회 실패는 무시
    }

    // Outbox 체크
    try {
      const outboxRes = await axios.get(
        `${API_BASE_URL}/monitoring/database/outbox`,
        {
          params: { maxAgeMinutes: 5 },
        }
      );
      const outboxData = outboxRes.data;

      if (!outboxData.healthy && outboxData.count > 0) {
        issues.push(`⚠️ ${outboxData.count}개 Outbox 이벤트 미발행 (5분 이상)`);
      }
    } catch (error) {
      // Outbox 조회 실패는 무시
    }

    // 복식부기 체크
    try {
      const reconRes = await axios.get(
        `${API_BASE_URL}/monitoring/database/reconciliation`
      );
      const reconData = reconRes.data;

      if (!reconData.balanced) {
        issues.push(
          `❌ 복식부기 불균형: 차변 ${reconData.totalDebits} ≠ 대변 ${reconData.totalCredits}`
        );
      }
    } catch (error) {
      issues.push(`❌ 복식부기 검증 실패`);
    }

    if (issues.length === 0) {
      return {
        content: [
          {
            type: "text",
            text: `✅ 시스템 진단 완료\n\n현재 감지된 문제가 없습니다. 모든 서비스가 정상 작동 중입니다.`,
          },
        ],
      };
    }

    let result = `🔍 시스템 문제 진단 결과\n\n`;
    result += `총 ${issues.length}개 문제 발견:\n\n`;
    result += issues.join("\n");
    result += `\n\n💡 각 MCP 서버의 전용 도구로 자세한 내용을 확인하세요.`;

    return {
      content: [{ type: "text", text: result }],
    };
  }

  async run(): Promise<void> {
    const transport = new StdioServerTransport();
    await this.server.connect(transport);
    console.error("System Health MCP Server running on stdio");
  }
}

const server = new SystemHealthMcpServer();
server.run().catch(console.error);
