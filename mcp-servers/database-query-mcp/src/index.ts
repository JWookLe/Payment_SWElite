#!/usr/bin/env node

import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";
import axios from "axios";

const API_BASE_URL = process.env.API_BASE_URL || "http://localhost:8082";

class DatabaseQueryMCPServer {
  private server: Server;

  constructor() {
    this.server = new Server(
      {
        name: "payment-database-query-mcp",
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
          name: "query_payments",
          description:
            'Query payment transactions with natural language filters. Examples: "failed payments last hour", "refunds for merchant X", "payments over 10000 KRW today"',
          inputSchema: {
            type: "object",
            properties: {
              filter: {
                type: "string",
                description:
                  "Filter criteria (status, merchant_id, time range, amount)",
              },
              limit: {
                type: "number",
                description: "Maximum number of results (default: 10)",
                default: 10,
              },
            },
            required: ["filter"],
          },
        },
        {
          name: "payment_statistics",
          description:
            "Get payment statistics: total count, amount, by status, top merchants, etc.",
          inputSchema: {
            type: "object",
            properties: {
              time_range: {
                type: "string",
                description:
                  "Time range: 'today', 'last_hour', 'last_24h', 'all' (default: 'today')",
                default: "today",
              },
            },
          },
        },
        {
          name: "check_outbox_status",
          description:
            "Check outbox event status to find unpublished events (Kafka publish failures). Useful for detecting stuck events.",
          inputSchema: {
            type: "object",
            properties: {
              max_age_minutes: {
                type: "number",
                description:
                  "Show unpublished events older than N minutes (default: 5)",
                default: 5,
              },
            },
          },
        },
        {
          name: "reconciliation_check",
          description:
            "Verify double-entry bookkeeping integrity: sum of debits must equal sum of credits. Returns any discrepancies.",
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
          case "query_payments":
            return await this.queryPayments(request.params.arguments);

          case "payment_statistics":
            return await this.getStatistics(request.params.arguments);

          case "check_outbox_status":
            return await this.checkOutboxStatus(request.params.arguments);

          case "reconciliation_check":
            return await this.checkReconciliation();

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

  private async queryPayments(args: any) {
    const filter = args.filter || "all";
    const limit = args.limit || 10;

    const response = await axios.get(
      `${API_BASE_URL}/monitoring/database/payments`,
      {
        params: { filter, limit },
      }
    );

    const data = response.data;
    let result = `📋 Payment Query Results (${data.count} results)\n\n`;
    result += `Filter: "${data.filter}"\n\n`;

    if (data.payments && data.payments.length > 0) {
      data.payments.forEach((payment: any, index: number) => {
        result += `${index + 1}. Payment ID: ${payment.payment_id}\n`;
        result += `   Merchant: ${payment.merchant_id}\n`;
        result += `   Amount: ${payment.amount} ${payment.currency}\n`;
        result += `   Status: ${payment.status}\n`;
        result += `   Requested: ${payment.requested_at}\n`;
        result += `   Idempotency Key: ${payment.idempotency_key}\n\n`;
      });
    } else {
      result += "No payments found matching the filter.\n";
    }

    return {
      content: [
        {
          type: "text",
          text: result,
        },
      ],
    };
  }

  private async getStatistics(args: any) {
    const timeRange = args.time_range || "today";

    const response = await axios.get(
      `${API_BASE_URL}/monitoring/database/statistics`,
      {
        params: { timeRange },
      }
    );

    const data = response.data;
    let result = `📊 Payment Statistics (${data.timeRange})\n\n`;

    result += `📈 Overall:\n`;
    result += `   Total Count: ${data.overall.total_count}\n`;
    result += `   Total Amount: ${data.overall.total_amount} KRW\n`;
    result += `   Average: ${data.overall.avg_amount} KRW\n`;
    result += `   Range: ${data.overall.min_amount} - ${data.overall.max_amount} KRW\n\n`;

    result += `📊 By Status:\n`;
    data.byStatus.forEach((s: any) => {
      result += `   ${s.status}: ${s.count} payments, ${s.total_amount} KRW\n`;
    });

    if (data.topMerchants && data.topMerchants.length > 0) {
      result += `\n🏆 Top Merchants:\n`;
      data.topMerchants.forEach((m: any, index: number) => {
        result += `   ${index + 1}. ${m.merchant_id}: ${m.transaction_count} txns, ${m.total_amount} KRW\n`;
      });
    }

    return {
      content: [
        {
          type: "text",
          text: result,
        },
      ],
    };
  }

  private async checkOutboxStatus(args: any) {
    const maxAgeMinutes = args.max_age_minutes || 5;

    const response = await axios.get(
      `${API_BASE_URL}/monitoring/database/outbox`,
      {
        params: { maxAgeMinutes },
      }
    );

    const data = response.data;
    const statusEmoji = data.healthy ? "✅" : "⚠️";

    let result = `${statusEmoji} Outbox Status\n\n`;
    result += `${data.message}\n\n`;

    if (data.events && data.events.length > 0) {
      result += `Stuck Events (${data.count}):\n\n`;
      data.events.forEach((event: any, index: number) => {
        result += `${index + 1}. Event ID: ${event.event_id}\n`;
        result += `   Type: ${event.event_type}\n`;
        result += `   Aggregate: ${event.aggregate_type} #${event.aggregate_id}\n`;
        result += `   Created: ${event.created_at}\n`;
        result += `   Age: ${event.age_minutes} minutes\n\n`;
      });
    } else {
      result += "All events published successfully! 🎉\n";
    }

    return {
      content: [
        {
          type: "text",
          text: result,
        },
      ],
    };
  }

  private async checkReconciliation() {
    const response = await axios.get(
      `${API_BASE_URL}/monitoring/database/reconciliation`
    );

    const data = response.data;
    const balanceEmoji = data.balanced ? "✅" : "❌";

    let result = `${balanceEmoji} Reconciliation Check\n\n`;
    result += `${data.message}\n\n`;
    result += `Total Debits: ${data.totalDebits} KRW\n`;
    result += `Total Credits: ${data.totalCredits} KRW\n`;
    result += `Difference: ${Math.abs(data.totalDebits - data.totalCredits)} KRW\n\n`;

    if (data.debitAccounts && data.debitAccounts.length > 0) {
      result += `📤 Debit Accounts:\n`;
      data.debitAccounts.forEach((acc: any) => {
        result += `   ${acc.debit_account}: ${acc.total} KRW\n`;
      });
    }

    if (data.creditAccounts && data.creditAccounts.length > 0) {
      result += `\n📥 Credit Accounts:\n`;
      data.creditAccounts.forEach((acc: any) => {
        result += `   ${acc.credit_account}: ${acc.total} KRW\n`;
      });
    }

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
    console.error("Database Query MCP Server running on stdio");
  }
}

const server = new DatabaseQueryMCPServer();
server.run().catch(console.error);
