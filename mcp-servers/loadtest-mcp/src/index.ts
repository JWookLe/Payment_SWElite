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

class LoadTestMcpServer {
  private server: Server;

  constructor() {
    this.server = new Server(
      {
        name: "loadtest-mcp",
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
          case "run_load_test":
            return await this.runLoadTest(args);
          case "get_latest_result":
            return await this.getLatestResult();
          case "analyze_performance":
            return await this.analyzePerformance();
          case "list_scenarios":
            return await this.listScenarios();
          case "get_test_history":
            return await this.getTestHistory(args);
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
        name: "run_load_test",
        description:
          "k6 부하 테스트를 실행합니다 (200 RPS 시나리오, 백그라운드 실행)",
        inputSchema: {
          type: "object",
          properties: {
            enable_capture: {
              type: "boolean",
              description: "결제 승인 후 정산(capture)도 함께 테스트 (기본: false)",
            },
            enable_refund: {
              type: "boolean",
              description: "환불(refund)도 함께 테스트 (기본: false)",
            },
          },
        },
      },
      {
        name: "get_latest_result",
        description: "가장 최근 실행한 k6 테스트 결과를 조회합니다",
        inputSchema: {
          type: "object",
          properties: {},
          required: [],
        },
      },
      {
        name: "analyze_performance",
        description:
          "최근 테스트 결과를 분석하여 성능 평가 및 권장사항을 제공합니다",
        inputSchema: {
          type: "object",
          properties: {},
          required: [],
        },
      },
      {
        name: "list_scenarios",
        description: "사용 가능한 k6 테스트 시나리오 목록을 보여줍니다",
        inputSchema: {
          type: "object",
          properties: {},
          required: [],
        },
      },
      {
        name: "get_test_history",
        description: "과거 테스트 결과 히스토리를 조회합니다",
        inputSchema: {
          type: "object",
          properties: {
            limit: {
              type: "number",
              description: "조회할 테스트 개수 (기본: 5)",
            },
          },
        },
      },
    ];
  }

  private async runLoadTest(args: any) {
    const enableCapture = args.enable_capture || false;
    const enableRefund = args.enable_refund || false;

    const response = await axios.post(
      `${API_BASE_URL}/monitoring/loadtest/run`,
      null,
      {
        params: {
          enableCapture,
          enableRefund,
        },
      }
    );
    const data = response.data;

    if (data.error) {
      return {
        content: [
          {
            type: "text",
            text: `❌ 테스트 시작 실패\n\n오류: ${data.message}`,
          },
        ],
      };
    }

    const scenario = enableRefund
      ? "전체 플로우 (승인 → 정산 → 환불)"
      : enableCapture
        ? "승인 + 정산"
        : "승인만";

    const result = `
🚀 k6 부하 테스트 시작됨

시나리오: ${scenario}
프로세스 ID: ${data.pid}
상태: 백그라운드 실행 중

💡 테스트가 완료되면 (약 8분 소요):
   - get_latest_result로 결과 확인
   - analyze_performance로 성능 분석

📊 실시간 모니터링:
   - Grafana: http://localhost:3000
   - Prometheus: http://localhost:9090
    `.trim();

    return {
      content: [{ type: "text", text: result }],
    };
  }

  private async getLatestResult() {
    const response = await axios.get(
      `${API_BASE_URL}/monitoring/loadtest/latest-result`
    );
    const data = response.data;

    if (!data.found) {
      return {
        content: [
          {
            type: "text",
            text: `📭 테스트 결과 없음\n\n아직 k6 테스트를 실행하지 않았거나, 결과 파일이 생성되지 않았습니다.\n\n💡 run_load_test로 테스트를 먼저 실행하세요.`,
          },
        ],
      };
    }

    const summary = data.summary;
    const metrics = summary.metrics;

    // 주요 메트릭 추출
    const httpReqDuration = metrics.http_req_duration.values;
    const httpReqFailed = metrics.http_req_failed.values;
    const iterations = metrics.iterations.values;

    const result = `
📊 최근 k6 테스트 결과

응답 시간:
  • 평균: ${httpReqDuration.avg.toFixed(2)} ms
  • p95: ${httpReqDuration["p(95)"].toFixed(2)} ms
  • p99: ${httpReqDuration["p(99)"].toFixed(2)} ms
  • 최소: ${httpReqDuration.min.toFixed(2)} ms
  • 최대: ${httpReqDuration.max.toFixed(2)} ms

성공률:
  • 실패율: ${(httpReqFailed.rate * 100).toFixed(2)}%
  • 성공률: ${((1 - httpReqFailed.rate) * 100).toFixed(2)}%

처리량:
  • 총 요청: ${iterations.count}건
  • 처리량: ${iterations.rate.toFixed(2)} req/s

파일: ${data.filePath}

💡 analyze_performance로 상세 분석 및 권장사항을 확인하세요.
    `.trim();

    return {
      content: [{ type: "text", text: result }],
    };
  }

  private async analyzePerformance() {
    const response = await axios.get(
      `${API_BASE_URL}/monitoring/loadtest/analyze`
    );
    const data = response.data;

    if (!data.found) {
      return {
        content: [
          {
            type: "text",
            text: `📭 분석할 테스트 결과가 없습니다.\n\n💡 run_load_test로 테스트를 먼저 실행하세요.`,
          },
        ],
      };
    }

    const analysis = data.analysis;

    const gradeEmoji =
      analysis.performanceGrade.includes("EXCELLENT")
        ? "🏆"
        : analysis.performanceGrade.includes("GOOD")
          ? "✅"
          : analysis.performanceGrade.includes("FAIR")
            ? "⚠️"
            : "❌";

    let result = `${gradeEmoji} 성능 분석 결과\n\n`;
    result += `평가: ${analysis.performanceGrade}\n\n`;
    result += `📈 응답 시간:\n`;
    result += `  • 평균: ${analysis.responseTime.avg}\n`;
    result += `  • p95: ${analysis.responseTime.p95}\n`;
    result += `  • p99: ${analysis.responseTime.p99}\n\n`;
    result += `📊 안정성:\n`;
    result += `  • 성공률: ${analysis.successRate}\n`;
    result += `  • 실패율: ${analysis.failureRate}\n\n`;
    result += `⚡ 처리량:\n`;
    result += `  • ${analysis.throughput}\n\n`;
    result += `💡 권장사항:\n`;
    for (const rec of analysis.recommendations) {
      result += `  • ${rec}\n`;
    }

    return {
      content: [{ type: "text", text: result }],
    };
  }

  private async listScenarios() {
    const response = await axios.get(
      `${API_BASE_URL}/monitoring/loadtest/scenarios`
    );
    const data = response.data;

    let result = `📋 사용 가능한 k6 테스트 시나리오\n\n`;

    for (const scenario of data.scenarios) {
      result += `• ${scenario.name}\n`;
      result += `  ${scenario.description}\n\n`;
    }

    result += `💡 run_load_test의 파라미터로 시나리오를 선택하세요.`;

    return {
      content: [{ type: "text", text: result }],
    };
  }

  private async getTestHistory(args: any) {
    const limit = args.limit || 5;

    const response = await axios.get(
      `${API_BASE_URL}/monitoring/loadtest/history`,
      {
        params: { limit },
      }
    );
    const data = response.data;

    if (data.count === 0) {
      return {
        content: [
          {
            type: "text",
            text: `📭 테스트 히스토리 없음\n\n과거 테스트 기록이 없습니다.`,
          },
        ],
      };
    }

    let result = `📜 테스트 히스토리 (최근 ${data.count}개)\n\n`;

    for (const test of data.history) {
      const date = new Date(test.timestamp);
      result += `📅 ${date.toLocaleString("ko-KR")}\n`;
      result += `   파일: ${test.file}\n`;

      if (test.summary && test.summary.metrics) {
        const metrics = test.summary.metrics;
        const p95 = metrics.http_req_duration.values["p(95)"];
        const failRate = metrics.http_req_failed.values.rate;
        result += `   p95: ${p95.toFixed(2)} ms, 실패율: ${(failRate * 100).toFixed(2)}%\n`;
      }

      result += `\n`;
    }

    return {
      content: [{ type: "text", text: result }],
    };
  }

  async run(): Promise<void> {
    const transport = new StdioServerTransport();
    await this.server.connect(transport);
    console.error("Load Test MCP Server running on stdio");
  }
}

const server = new LoadTestMcpServer();
server.run().catch(console.error);
