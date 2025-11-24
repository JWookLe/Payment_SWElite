#!/usr/bin/env node
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { CallToolRequestSchema, ListToolsRequestSchema, } from '@modelcontextprotocol/sdk/types.js';
import Anthropic from '@anthropic-ai/sdk';
// Claude API 클라이언트 초기화
const anthropic = new Anthropic({
    apiKey: process.env.ANTHROPIC_API_KEY || '',
});
// MCP 서버 생성
const server = new Server({
    name: 'ai-test-analyzer',
    version: '1.0.0',
}, {
    capabilities: {
        tools: {},
    },
});
// 도구 목록 정의
const TOOLS = [
    {
        name: 'analyze_k6_test',
        description: 'Analyze K6 load test results and provide insights, recommendations, and performance metrics analysis',
        inputSchema: {
            type: 'object',
            properties: {
                testId: {
                    type: 'string',
                    description: 'Unique identifier for the test',
                },
                scenario: {
                    type: 'string',
                    description: 'Test scenario (e.g., "authorize-only", "full-flow")',
                },
                rawData: {
                    type: 'object',
                    description: 'Raw test data including k6Summary, output, metrics',
                },
            },
            required: ['testId', 'scenario', 'rawData'],
        },
    },
    {
        name: 'analyze_circuit_breaker_test',
        description: 'Analyze Circuit Breaker test results and evaluate resilience patterns',
        inputSchema: {
            type: 'object',
            properties: {
                testId: {
                    type: 'string',
                    description: 'Unique identifier for the test',
                },
                rawData: {
                    type: 'object',
                    description: 'Raw test data including circuit breaker states and transitions',
                },
            },
            required: ['testId', 'rawData'],
        },
    },
    {
        name: 'analyze_health_check',
        description: 'Analyze system health check results across multiple services',
        inputSchema: {
            type: 'object',
            properties: {
                testId: {
                    type: 'string',
                    description: 'Unique identifier for the test',
                },
                rawData: {
                    type: 'object',
                    description: 'Health check data for all services (DB, Redis, Kafka, etc.)',
                },
            },
            required: ['testId', 'rawData'],
        },
    },
    {
        name: 'analyze_monitoring_stats',
        description: 'Analyze monitoring statistics (Database, Redis, Kafka, Settlement)',
        inputSchema: {
            type: 'object',
            properties: {
                testId: {
                    type: 'string',
                    description: 'Unique identifier for the test',
                },
                testType: {
                    type: 'string',
                    description: 'Type of monitoring stats (database, redis, kafka, settlement)',
                },
                rawData: {
                    type: 'object',
                    description: 'Monitoring statistics data',
                },
            },
            required: ['testId', 'testType', 'rawData'],
        },
    },
];
// 도구 목록 핸들러
server.setRequestHandler(ListToolsRequestSchema, async () => ({
    tools: TOOLS,
}));
// 도구 실행 핸들러
server.setRequestHandler(CallToolRequestSchema, async (request) => {
    const { name, arguments: args } = request.params;
    try {
        switch (name) {
            case 'analyze_k6_test':
                return await analyzeK6Test(args);
            case 'analyze_circuit_breaker_test':
                return await analyzeCircuitBreakerTest(args);
            case 'analyze_health_check':
                return await analyzeHealthCheck(args);
            case 'analyze_monitoring_stats':
                return await analyzeMonitoringStats(args);
            default:
                throw new Error(`Unknown tool: ${name}`);
        }
    }
    catch (error) {
        return {
            content: [
                {
                    type: 'text',
                    text: `Error: ${error instanceof Error ? error.message : String(error)}`,
                },
            ],
            isError: true,
        };
    }
});
/**
 * K6 부하 테스트 결과 분석
 */
async function analyzeK6Test(args) {
    const { testId, scenario, rawData } = args;
    const prompt = buildK6AnalysisPrompt(scenario, rawData);
    const message = await anthropic.messages.create({
        model: 'claude-3-5-sonnet-20241022',
        max_tokens: 2048,
        messages: [
            {
                role: 'user',
                content: prompt,
            },
        ],
    });
    const analysisText = message.content[0].type === 'text' ? message.content[0].text : '';
    // 메트릭 및 권장사항 추출
    const result = parseAnalysisResult(analysisText, rawData);
    return {
        content: [
            {
                type: 'text',
                text: JSON.stringify(result, null, 2),
            },
        ],
    };
}
/**
 * Circuit Breaker 테스트 결과 분석
 */
async function analyzeCircuitBreakerTest(args) {
    const { testId, rawData } = args;
    const prompt = buildCircuitBreakerAnalysisPrompt(rawData);
    const message = await anthropic.messages.create({
        model: 'claude-3-5-sonnet-20241022',
        max_tokens: 1536,
        messages: [
            {
                role: 'user',
                content: prompt,
            },
        ],
    });
    const analysisText = message.content[0].type === 'text' ? message.content[0].text : '';
    const result = parseAnalysisResult(analysisText, rawData);
    return {
        content: [
            {
                type: 'text',
                text: JSON.stringify(result, null, 2),
            },
        ],
    };
}
/**
 * Health Check 결과 분석
 */
async function analyzeHealthCheck(args) {
    const { testId, rawData } = args;
    const prompt = buildHealthCheckAnalysisPrompt(rawData);
    const message = await anthropic.messages.create({
        model: 'claude-3-5-sonnet-20241022',
        max_tokens: 1536,
        messages: [
            {
                role: 'user',
                content: prompt,
            },
        ],
    });
    const analysisText = message.content[0].type === 'text' ? message.content[0].text : '';
    const result = parseAnalysisResult(analysisText, rawData);
    return {
        content: [
            {
                type: 'text',
                text: JSON.stringify(result, null, 2),
            },
        ],
    };
}
/**
 * 모니터링 통계 분석
 */
async function analyzeMonitoringStats(args) {
    const { testId, testType, rawData } = args;
    const prompt = buildMonitoringStatsAnalysisPrompt(testType, rawData);
    const message = await anthropic.messages.create({
        model: 'claude-3-5-sonnet-20241022',
        max_tokens: 1536,
        messages: [
            {
                role: 'user',
                content: prompt,
            },
        ],
    });
    const analysisText = message.content[0].type === 'text' ? message.content[0].text : '';
    const result = parseAnalysisResult(analysisText, rawData);
    return {
        content: [
            {
                type: 'text',
                text: JSON.stringify(result, null, 2),
            },
        ],
    };
}
/**
 * K6 분석 프롬프트 생성
 */
function buildK6AnalysisPrompt(scenario, rawData) {
    const k6Summary = rawData.k6Summary || {};
    const metrics = k6Summary.metrics || {};
    return `You are an expert performance testing analyst. Analyze the following K6 load test results and provide:

1. **Executive Summary** (2-3 sentences): Overall test outcome and key findings
2. **Performance Metrics Analysis**: Evaluate HTTP request success rate, response times (avg, p95, p99), and throughput
3. **Bottlenecks & Issues**: Identify any performance bottlenecks or concerning patterns
4. **Recommendations** (3-5 bullet points): Specific, actionable recommendations for improvement

**Test Scenario**: ${scenario}

**Raw K6 Metrics**:
\`\`\`json
${JSON.stringify(metrics, null, 2)}
\`\`\`

**Key Metrics to Analyze**:
- http_req_failed: Request failure rate
- http_req_duration: Response time distribution
- http_reqs: Total requests
- vus: Virtual users

Provide your analysis in a structured format with clear sections. Be specific about numbers and thresholds.`;
}
/**
 * Circuit Breaker 분석 프롬프트 생성
 */
function buildCircuitBreakerAnalysisPrompt(rawData) {
    const output = rawData.output || '';
    return `You are a resilience engineering expert. Analyze the following Circuit Breaker test results:

**Test Output**:
\`\`\`
${output}
\`\`\`

Provide:
1. **Summary**: Did the circuit breaker function correctly?
2. **State Transitions**: Analyze CLOSED → OPEN → HALF_OPEN → CLOSED transitions
3. **Recovery Behavior**: Evaluate how the system recovered after Kafka restart
4. **Recommendations**: Suggestions for circuit breaker tuning (thresholds, timeouts, etc.)

Be concise and focus on resilience patterns.`;
}
/**
 * Health Check 분석 프롬프트 생성
 */
function buildHealthCheckAnalysisPrompt(rawData) {
    return `You are a DevOps engineer analyzing system health checks. Review the following health check results:

**Health Check Data**:
\`\`\`json
${JSON.stringify(rawData, null, 2)}
\`\`\`

Provide:
1. **Summary**: Overall system health status
2. **Service Status**: Breakdown of each service (UP/DOWN)
3. **Dependencies**: Evaluate DB, Redis, Kafka connectivity
4. **Recommendations**: Any immediate actions needed for failing services

Focus on actionable insights.`;
}
/**
 * 모니터링 통계 분석 프롬프트 생성
 */
function buildMonitoringStatsAnalysisPrompt(testType, rawData) {
    return `You are a monitoring and observability expert. Analyze the following ${testType} statistics:

**Monitoring Data**:
\`\`\`json
${JSON.stringify(rawData, null, 2)}
\`\`\`

Provide:
1. **Summary**: Key insights from the data
2. **Metrics Analysis**: Evaluate performance, usage, and health metrics
3. **Trends**: Identify any concerning trends or anomalies
4. **Recommendations**: Optimization suggestions (capacity, configuration, etc.)

Be specific with numbers and thresholds.`;
}
/**
 * AI 분석 결과 파싱
 */
function parseAnalysisResult(analysisText, rawData) {
    // 메트릭 추출 시도
    const metrics = {};
    if (rawData.k6Summary?.metrics) {
        const k6Metrics = rawData.k6Summary.metrics;
        // 주요 메트릭 추출
        if (k6Metrics.http_reqs) {
            metrics['Total Requests'] = String(k6Metrics.http_reqs.values?.count || 'N/A');
        }
        if (k6Metrics.http_req_failed) {
            const failRate = k6Metrics.http_req_failed.values?.rate || 0;
            metrics['Success Rate'] = `${((1 - failRate) * 100).toFixed(2)}%`;
        }
        if (k6Metrics.http_req_duration) {
            metrics['Avg Duration'] = `${k6Metrics.http_req_duration.values?.avg?.toFixed(2) || 'N/A'} ms`;
            metrics['P95 Duration'] = `${k6Metrics.http_req_duration.values?.['p(95)']?.toFixed(2) || 'N/A'} ms`;
            metrics['P99 Duration'] = `${k6Metrics.http_req_duration.values?.['p(99)']?.toFixed(2) || 'N/A'} ms`;
        }
    }
    // 권장사항 추출 (분석 텍스트에서 리스트 항목 찾기)
    const recommendations = [];
    const lines = analysisText.split('\n');
    let inRecommendations = false;
    for (const line of lines) {
        if (line.toLowerCase().includes('recommendation')) {
            inRecommendations = true;
            continue;
        }
        if (inRecommendations && (line.trim().startsWith('-') || line.trim().startsWith('•') || /^\d+\./.test(line.trim()))) {
            const rec = line.trim().replace(/^[-•]\s*/, '').replace(/^\d+\.\s*/, '');
            if (rec.length > 0) {
                recommendations.push(rec);
            }
        }
        if (inRecommendations && line.trim() === '') {
            // 빈 줄이 연속되면 권장사항 섹션 종료
            const nextNonEmpty = lines.slice(lines.indexOf(line) + 1).find(l => l.trim() !== '');
            if (nextNonEmpty && !nextNonEmpty.trim().startsWith('-') && !nextNonEmpty.trim().startsWith('•')) {
                break;
            }
        }
    }
    return {
        aiSummary: analysisText,
        metrics: Object.keys(metrics).length > 0 ? metrics : { Status: 'Data Collected' },
        recommendations: recommendations.length > 0 ? recommendations : ['시스템이 정상적으로 동작하고 있습니다.'],
    };
}
// 서버 시작
async function main() {
    const transport = new StdioServerTransport();
    await server.connect(transport);
    console.error('AI Test Analyzer MCP server running on stdio');
    console.error('ANTHROPIC_API_KEY:', process.env.ANTHROPIC_API_KEY ? 'Set' : 'Not set');
}
main().catch((error) => {
    console.error('Fatal error:', error);
    process.exit(1);
});
//# sourceMappingURL=index.js.map