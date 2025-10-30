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

interface KafkaTopicStats {
  topic: string;
  partitions: number;
  totalMessages: number;
  endOffsets?: Record<string, number>;
  error?: string;
}

interface ConsumerLagInfo {
  groupId: string;
  totalLag: number;
  status: string;
  partitions: Array<{
    topic: string;
    partition: number;
    currentOffset: number;
    endOffset: number;
    lag: number;
  }>;
  message: string;
}

interface DlqMessage {
  partition: number;
  offset: number;
  timestamp: number;
  key: string;
  value: string;
}

class KafkaOperationsMcpServer {
  private server: Server;

  constructor() {
    this.server = new Server(
      {
        name: "kafka-operations-mcp",
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
          case "list_topics":
            return await this.listTopics();
          case "get_topic_stats":
            return await this.getTopicStats(args);
          case "check_consumer_lag":
            return await this.checkConsumerLag(args);
          case "list_consumer_groups":
            return await this.listConsumerGroups();
          case "get_dlq_messages":
            return await this.getDlqMessages(args);
          case "check_kafka_health":
            return await this.checkKafkaHealth();
          case "get_topic_details":
            return await this.getTopicDetails(args);
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
        name: "list_topics",
        description:
          "결제 시스템의 Kafka 토픽 목록을 조회합니다 (payment.* 토픽만)",
        inputSchema: {
          type: "object",
          properties: {},
          required: [],
        },
      },
      {
        name: "get_topic_stats",
        description:
          "토픽별 메시지 수, 파티션 정보, 오프셋 등을 조회합니다",
        inputSchema: {
          type: "object",
          properties: {
            topic: {
              type: "string",
              description:
                "조회할 토픽 이름 (선택사항, 미입력 시 전체 payment 토픽)",
            },
          },
        },
      },
      {
        name: "check_consumer_lag",
        description:
          "특정 Consumer Group의 Lag을 조회합니다 (처리 지연 확인)",
        inputSchema: {
          type: "object",
          properties: {
            group_id: {
              type: "string",
              description: "Consumer Group ID (예: payment-consumer-group)",
            },
          },
          required: ["group_id"],
        },
      },
      {
        name: "list_consumer_groups",
        description: "활성화된 모든 Consumer Group 목록을 조회합니다",
        inputSchema: {
          type: "object",
          properties: {},
          required: [],
        },
      },
      {
        name: "get_dlq_messages",
        description:
          "Dead Letter Queue(DLQ)에 있는 실패한 메시지들을 조회합니다",
        inputSchema: {
          type: "object",
          properties: {
            limit: {
              type: "number",
              description: "조회할 최대 메시지 수 (기본값: 10)",
            },
          },
        },
      },
      {
        name: "check_kafka_health",
        description: "Kafka 클러스터의 전반적인 헬스 상태를 확인합니다",
        inputSchema: {
          type: "object",
          properties: {},
          required: [],
        },
      },
      {
        name: "get_topic_details",
        description:
          "토픽의 상세 정보 (파티션, 리더, 레플리카 등)를 조회합니다",
        inputSchema: {
          type: "object",
          properties: {
            topic: {
              type: "string",
              description: "조회할 토픽 이름",
            },
          },
          required: ["topic"],
        },
      },
    ];
  }

  private async listTopics() {
    const response = await axios.get(`${API_BASE_URL}/monitoring/kafka/topics`);
    const data = response.data;

    const result = `
📋 Kafka 토픽 목록

총 ${data.count}개 토픽:
${data.topics.map((t: string) => `  • ${t}`).join("\n")}

💡 각 토픽의 상세 정보를 보려면 get_topic_stats를 사용하세요.
    `.trim();

    return {
      content: [{ type: "text", text: result }],
    };
  }

  private async getTopicStats(args: any) {
    const topic = args.topic;
    const params = topic ? { topic } : {};

    const response = await axios.get(
      `${API_BASE_URL}/monitoring/kafka/topic-stats`,
      { params }
    );
    const data = response.data;

    let result = `📊 Kafka 토픽 통계 (${data.count}개 토픽)\n\n`;

    for (const stats of data.topics as KafkaTopicStats[]) {
      if (stats.error) {
        result += `❌ ${stats.topic}\n   오류: ${stats.error}\n\n`;
      } else {
        result += `✅ ${stats.topic}\n`;
        result += `   파티션: ${stats.partitions}개\n`;
        result += `   총 메시지: ${stats.totalMessages}개\n`;
        if (stats.endOffsets) {
          result += `   파티션별 오프셋:\n`;
          for (const [partition, offset] of Object.entries(stats.endOffsets)) {
            result += `     - ${partition}: ${offset}\n`;
          }
        }
        result += `\n`;
      }
    }

    result += `💡 ${data.message}`;

    return {
      content: [{ type: "text", text: result }],
    };
  }

  private async checkConsumerLag(args: any) {
    const groupId = args.group_id;

    if (!groupId) {
      throw new Error("group_id is required");
    }

    const response = await axios.get(
      `${API_BASE_URL}/monitoring/kafka/consumer-lag`,
      {
        params: { groupId },
      }
    );
    const data = response.data as ConsumerLagInfo;

    const statusEmoji =
      data.status === "OK" ? "✅" : data.status === "WARNING" ? "⚠️" : "🟡";

    let result = `${statusEmoji} Consumer Lag 상태: ${groupId}\n\n`;
    result += `총 Lag: ${data.totalLag}개 메시지\n`;
    result += `상태: ${data.status}\n\n`;

    if (data.partitions && data.partitions.length > 0) {
      result += `파티션별 상세:\n`;
      for (const p of data.partitions) {
        result += `  📌 ${p.topic} [파티션 ${p.partition}]\n`;
        result += `     현재 오프셋: ${p.currentOffset}\n`;
        result += `     최신 오프셋: ${p.endOffset}\n`;
        result += `     Lag: ${p.lag}개\n\n`;
      }
    }

    result += `💡 ${data.message}`;

    return {
      content: [{ type: "text", text: result }],
    };
  }

  private async listConsumerGroups() {
    const response = await axios.get(
      `${API_BASE_URL}/monitoring/kafka/consumer-groups`
    );
    const data = response.data;

    let result = `📋 Consumer Group 목록 (${data.count}개)\n\n`;

    for (const group of data.groups) {
      result += `  • ${group.groupId}\n`;
      result += `    상태: ${group.state}\n`;
      result += `    Simple: ${group.isSimpleConsumerGroup ? "Yes" : "No"}\n\n`;
    }

    result += `💡 각 그룹의 Lag을 확인하려면 check_consumer_lag를 사용하세요.`;

    return {
      content: [{ type: "text", text: result }],
    };
  }

  private async getDlqMessages(args: any) {
    const limit = args.limit || 10;

    const response = await axios.get(
      `${API_BASE_URL}/monitoring/kafka/dlq-messages`,
      {
        params: { limit },
      }
    );
    const data = response.data;

    const messages = data.messages as DlqMessage[];

    if (messages.length === 0) {
      return {
        content: [
          {
            type: "text",
            text: `✅ DLQ가 비어있습니다. 실패한 메시지가 없습니다.`,
          },
        ],
      };
    }

    let result = `⚠️ DLQ 메시지 (${data.count}개 발견)\n\n`;

    for (const msg of messages) {
      const date = new Date(msg.timestamp);
      result += `📩 파티션 ${msg.partition}, 오프셋 ${msg.offset}\n`;
      result += `   시각: ${date.toLocaleString("ko-KR")}\n`;
      result += `   키: ${msg.key}\n`;
      result += `   내용: ${msg.value}\n\n`;
    }

    result += `💡 ${data.message}`;

    return {
      content: [{ type: "text", text: result }],
    };
  }

  private async checkKafkaHealth() {
    const response = await axios.get(
      `${API_BASE_URL}/monitoring/kafka/health`
    );
    const data = response.data;

    const healthEmoji = data.healthy ? "✅" : "❌";

    let result = `${healthEmoji} Kafka 클러스터 헬스 체크\n\n`;

    if (data.healthy) {
      result += `상태: 정상\n`;
      result += `클러스터 ID: ${data.clusterId}\n`;
      result += `브로커 수: ${data.brokerCount}개\n\n`;
      result += `💡 ${data.message}`;
    } else {
      result += `상태: 비정상\n`;
      result += `오류: ${data.message}`;
    }

    return {
      content: [{ type: "text", text: result }],
    };
  }

  private async getTopicDetails(args: any) {
    const topic = args.topic;

    if (!topic) {
      throw new Error("topic is required");
    }

    const response = await axios.get(
      `${API_BASE_URL}/monitoring/kafka/topic-details`,
      {
        params: { topic },
      }
    );
    const data = response.data;

    let result = `📌 토픽 상세 정보: ${data.topic}\n\n`;
    result += `파티션 수: ${data.partitionCount}개\n\n`;

    result += `파티션별 상세:\n`;
    for (const p of data.partitions) {
      result += `  파티션 ${p.partition}:\n`;
      result += `    리더: 브로커 ${p.leader}\n`;
      result += `    레플리카: ${p.replicas.join(", ")}\n`;
      result += `    ISR: ${p.isr.join(", ")}\n\n`;
    }

    result += `💡 ${data.message}`;

    return {
      content: [{ type: "text", text: result }],
    };
  }

  async run(): Promise<void> {
    const transport = new StdioServerTransport();
    await this.server.connect(transport);
    console.error("Kafka Operations MCP Server running on stdio");
  }
}

const server = new KafkaOperationsMcpServer();
server.run().catch(console.error);
