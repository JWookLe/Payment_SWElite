#!/usr/bin/env node

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import axios from "axios";

const API_BASE_URL = process.env.API_BASE_URL || "http://localhost:8082";

class RedisCacheMCPServer {
  private server: Server;

  constructor() {
    this.server = new Server(
      {
        name: "payment-redis-cache-mcp",
        version: "1.0.0",
      },
      {
        capabilities: {
          tools: {},
        },
      }
    );

    this.setupHandlers();
    this.server.onerror = (error) => console.error("[MCP Error]", error);
    process.on("SIGINT", async () => {
      await this.server.close();
      process.exit(0);
    });
  }

  private setupHandlers() {
    this.server.setRequestHandler(ListToolsRequestSchema, async () => ({
      tools: [
        {
          name: "check_rate_limit",
          description:
            "Check rate limit status for a specific merchant. Shows remaining tokens and reset time.",
          inputSchema: {
            type: "object",
            properties: {
              merchant_id: {
                type: "string",
                description: "Merchant ID to check",
              },
            },
            required: ["merchant_id"],
          },
        },
        {
          name: "cache_stats",
          description:
            "Get Redis cache statistics including keys count and samples.",
          inputSchema: {
            type: "object",
            properties: {},
          },
        },
        {
          name: "list_blocked_merchants",
          description:
            "Find merchants currently blocked by rate limits.",
          inputSchema: {
            type: "object",
            properties: {},
          },
        },
      ],
    }));

    this.server.setRequestHandler(CallToolRequestSchema, async (request) => {
      try {
        switch (request.params.name) {
          case "check_rate_limit":
            return await this.checkRateLimit(request.params.arguments);

          case "cache_stats":
            return await this.getCacheStats();

          case "list_blocked_merchants":
            return await this.getBlockedMerchants();

          default:
            throw new Error(`Unknown tool: ${request.params.name}`);
        }
      } catch (error: any) {
        const errorMessage = error.response?.data?.message || error.message;
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

  private async checkRateLimit(args: any) {
    const merchantId = args.merchant_id;

    const response = await axios.get(
      `${API_BASE_URL}/monitoring/redis/rate-limit`,
      {
        params: { merchantId },
      }
    );

    const data = response.data;
    const statusEmoji = data.status === "OK" ? "✅" : "❌";

    let result = `${statusEmoji} Rate Limit Status\n\n`;
    result += `Merchant: ${data.merchantId}\n`;
    result += `Current Count: ${data.currentCount} / ${data.limit}\n`;
    result += `Remaining: ${data.remaining}\n`;
    result += `TTL: ${data.ttlSeconds} seconds\n`;
    result += `Status: ${data.status}\n`;
    result += `\n${data.message}\n`;

    return {
      content: [
        {
          type: "text",
          text: result,
        },
      ],
    };
  }

  private async getCacheStats() {
    const response = await axios.get(
      `${API_BASE_URL}/monitoring/redis/cache-stats`
    );

    const data = response.data;

    let result = `📊 Redis Cache Statistics\n\n`;
    result += `Rate Limit Keys: ${data.rateLimitKeys}\n`;
    result += `Idempotency Keys: ${data.idempotencyKeys}\n`;
    result += `Cache Keys: ${data.cacheKeys}\n`;
    result += `Total Keys: ${data.totalKeys}\n\n`;

    if (data.rateLimitSamples && data.rateLimitSamples.length > 0) {
      result += `Sample Rate Limits:\n`;
      data.rateLimitSamples.forEach((sample: any) => {
        result += `  - ${sample.key}: ${sample.count} (TTL: ${sample.ttlSeconds}s)\n`;
      });
    }

    result += `\n${data.message}\n`;

    return {
      content: [
        {
          type: "text",
          text: result,
        },
      ],
    };
  }

  private async getBlockedMerchants() {
    const response = await axios.get(
      `${API_BASE_URL}/monitoring/redis/rate-limit/blocked`
    );

    const data = response.data;
    const statusEmoji = data.count > 0 ? "⚠️" : "✅";

    let result = `${statusEmoji} Blocked Merchants (${data.count})\n\n`;

    if (data.blockedMerchants && data.blockedMerchants.length > 0) {
      data.blockedMerchants.forEach((m: any, index: number) => {
        result += `${index + 1}. ${m.merchantId}\n`;
        result += `   Count: ${m.count}\n`;
        result += `   TTL: ${m.ttl} seconds\n\n`;
      });
    } else {
      result += "No merchants currently blocked! 🎉\n";
    }

    result += `\n${data.message}\n`;

    return {
      content: [
        {
          type: "text",
          text: result,
        },
      ],
    };
  }

  async run() {
    const transport = new StdioServerTransport();
    await this.server.connect(transport);
    console.error("Redis Cache MCP Server running on stdio");
  }
}

const server = new RedisCacheMCPServer();
server.run().catch(console.error);
