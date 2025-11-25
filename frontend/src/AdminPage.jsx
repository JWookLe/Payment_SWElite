import { useState, useEffect } from 'react';
import axios from 'axios';

const API_BASE = '/api/admin';

const TEST_CATEGORIES = [
  {
    id: 'load',
    name: '부하 테스트',
    icon: '📊',
    tests: [
      {
        id: 'k6-authorize-only',
        name: 'K6: 승인 전용',
        description: '승인 API 부하 테스트 (최대 1000 RPS)',
        endpoint: '/tests/k6/authorize-only',
        estimatedTime: '8분'
      },
      {
        id: 'k6-full-flow',
        name: 'K6: 전체 플로우',
        description: '승인 + 정산 + 환불 전체 플로우 테스트',
        endpoint: '/tests/k6/full-flow',
        estimatedTime: '10분'
      }
    ]
  },
  {
    id: 'resilience',
    name: '안정성 테스트',
    icon: '🛡️',
    tests: [
      {
        id: 'circuit-breaker',
        name: 'Circuit Breaker',
        description: 'Kafka 다운타임 시뮬레이션 및 복구 검증',
        endpoint: '/tests/circuit-breaker',
        estimatedTime: '2분'
      }
    ]
  },
  {
    id: 'monitoring',
    name: '모니터링',
    icon: '📈',
    tests: [
      {
        id: 'health-check',
        name: 'Health Check',
        description: '모든 서비스 헬스 체크 (DB, Redis, Kafka)',
        endpoint: '/tests/health-check',
        estimatedTime: '30초'
      },
      {
        id: 'database-stats',
        name: 'Database 통계',
        description: 'DB 연결, 쿼리 성능, 테이블 통계',
        endpoint: '/tests/database-stats',
        estimatedTime: '15초'
      },
      {
        id: 'redis-stats',
        name: 'Redis 통계',
        description: 'Cache hit/miss rate, 메모리 사용량',
        endpoint: '/tests/redis-stats',
        estimatedTime: '15초'
      },
      {
        id: 'kafka-stats',
        name: 'Kafka 통계',
        description: 'Topic lag, consumer group 상태',
        endpoint: '/tests/kafka-stats',
        estimatedTime: '20초'
      }
    ]
  },
  {
    id: 'business',
    name: '비즈니스 메트릭',
    icon: '💰',
    tests: [
      {
        id: 'settlement-stats',
        name: 'Settlement 통계',
        description: '정산 완료율, 금액 집계, 실패 케이스',
        endpoint: '/tests/settlement-stats',
        estimatedTime: '10초'
      }
    ]
  }
];

function TestCard({ test, onRun, running, latestReport }) {
  const [expanded, setExpanded] = useState(false);

  return (
    <div className={`admin-test-card ${running ? 'running' : ''}`}>
      <div className="admin-test-card__header">
        <div className="admin-test-card__info">
          <h4>{test.name}</h4>
          <p>{test.description}</p>
          <span className="admin-test-card__time">예상 시간: {test.estimatedTime}</span>
        </div>
        <div className="admin-test-card__actions">
          <button
            type="button"
            onClick={() => onRun(test)}
            disabled={running}
            className={`admin-test-card__run-btn ${running ? 'loading' : ''}`}
          >
            {running ? '실행 중...' : '테스트 실행'}
          </button>
          {latestReport && (
            <button
              type="button"
              onClick={() => setExpanded(!expanded)}
              className="admin-test-card__toggle-btn"
            >
              {expanded ? '접기 ▲' : '보고서 보기 ▼'}
            </button>
          )}
        </div>
      </div>

      {expanded && latestReport && (
        <div className="admin-test-card__report">
          <div className="admin-report">
            <div className="admin-report__meta">
              <span className={`admin-report__status admin-report__status--${latestReport.status}`}>
                {latestReport.status === 'success' ? '✓ 성공' : '✕ 실패'}
              </span>
              <span className="admin-report__time">
                {new Date(latestReport.timestamp).toLocaleString('ko-KR')}
              </span>
              <span className="admin-report__duration">
                소요 시간: {latestReport.duration}
              </span>
            </div>

            <div className="admin-report__summary">
              <h5>AI 분석 요약</h5>
              <div className="admin-report__ai-summary">
                {latestReport.aiSummary || '분석 중...'}
              </div>
            </div>

            {latestReport.metrics && (
              <div className="admin-report__metrics">
                <h5>주요 메트릭</h5>
                <div className="admin-report__metrics-grid">
                  {Object.entries(latestReport.metrics).map(([key, value]) => (
                    <div key={key} className="admin-report__metric">
                      <span className="admin-report__metric-label">{key}</span>
                      <span className="admin-report__metric-value">{value}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {latestReport.recommendations && latestReport.recommendations.length > 0 && (
              <div className="admin-report__recommendations">
                <h5>개선 권장사항</h5>
                <ul>
                  {latestReport.recommendations.map((rec, idx) => (
                    <li key={idx}>{rec}</li>
                  ))}
                </ul>
              </div>
            )}

            <div className="admin-report__raw">
              <button
                type="button"
                onClick={() => {
                  const blob = new Blob([JSON.stringify(latestReport.rawData, null, 2)], {
                    type: 'application/json'
                  });
                  const url = URL.createObjectURL(blob);
                  const a = document.createElement('a');
                  a.href = url;
                  a.download = `${test.id}-${latestReport.timestamp}.json`;
                  a.click();
                  URL.revokeObjectURL(url);
                }}
              >
                Raw Data 다운로드
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default function AdminPage() {
  const [runningTests, setRunningTests] = useState(new Set());
  const [reports, setReports] = useState({});
  const [globalStatus, setGlobalStatus] = useState(null);

  useEffect(() => {
    // 페이지 로드 시 최근 보고서 가져오기
    loadRecentReports();
  }, []);

  const loadRecentReports = async () => {
    try {
      const response = await axios.get(`${API_BASE}/reports/recent`);
      const reportsByTestId = {};
      response.data.forEach((report) => {
        reportsByTestId[report.testId] = report;
      });
      setReports(reportsByTestId);
    } catch (error) {
      console.error('Failed to load recent reports:', error);
    }
  };

  const runTest = async (test) => {
    setRunningTests((prev) => new Set(prev).add(test.id));
    setGlobalStatus({ type: 'info', message: `${test.name} 실행 중...` });

    try {
      // 테스트 시작 요청
      const response = await axios.post(`${API_BASE}${test.endpoint}`, {
        testId: test.id,
        generateReport: true
      });

      // "running" 상태 저장
      setReports((prev) => ({
        ...prev,
        [test.id]: response.data
      }));

      // 완료될 때까지 폴링 (running 상태가 아닐 때까지)
      if (response.data.status === 'running') {
        const pollInterval = setInterval(async () => {
          try {
            const statusResponse = await axios.get(`${API_BASE}/tests/status/${test.id}`);

            // 상태 업데이트
            setReports((prev) => ({
              ...prev,
              [test.id]: statusResponse.data
            }));

            // 완료되면 폴링 중지
            if (statusResponse.data.status !== 'running') {
              clearInterval(pollInterval);
              setRunningTests((prev) => {
                const updated = new Set(prev);
                updated.delete(test.id);
                return updated;
              });

              // 성공/실패에 따라 다른 메시지 표시
              if (statusResponse.data.status === 'success') {
                setGlobalStatus({
                  type: 'success',
                  message: `${test.name} 완료! AI 분석 보고서가 생성되었습니다.`
                });
              } else {
                setGlobalStatus({
                  type: 'error',
                  message: `${test.name} 실패! 보고서를 확인하세요.`
                });
              }
            }
          } catch (error) {
            console.error('Polling error:', error);
            clearInterval(pollInterval);
            setRunningTests((prev) => {
              const updated = new Set(prev);
              updated.delete(test.id);
              return updated;
            });
          }
        }, 3000); // 3초마다 폴링
      } else {
        // 즉시 완료된 경우
        setRunningTests((prev) => {
          const updated = new Set(prev);
          updated.delete(test.id);
          return updated;
        });

        // 성공/실패에 따라 다른 메시지 표시
        if (response.data.status === 'success') {
          setGlobalStatus({
            type: 'success',
            message: `${test.name} 완료! AI 분석 보고서가 생성되었습니다.`
          });
        } else {
          setGlobalStatus({
            type: 'error',
            message: `${test.name} 실패! 보고서를 확인하세요.`
          });
        }
      }
    } catch (error) {
      console.error(`Test ${test.id} failed:`, error);

      // 실패 보고서 생성
      const failureReport = {
        reportId: `error-${Date.now()}`,
        testId: test.id,
        testName: test.name,
        status: 'failure',
        timestamp: new Date().toISOString(),
        duration: '0초',
        aiSummary: `테스트 실행 중 오류가 발생했습니다: ${error.response?.status || ''} : "${error.response?.data ? JSON.stringify(error.response.data) : error.message}"`,
        metrics: {},
        recommendations: ['서비스가 정상적으로 실행 중인지 확인하세요', 'API 엔드포인트 경로를 확인하세요'],
        rawData: {
          error: error.message,
          response: error.response?.data,
          status: error.response?.status
        }
      };

      setReports((prev) => ({
        ...prev,
        [test.id]: failureReport
      }));

      setGlobalStatus({
        type: 'error',
        message: `${test.name} 실행 중 오류가 발생했습니다: ${error.response?.status || ''} : "${error.response?.data ? JSON.stringify(error.response.data) : error.message}"`
      });

      setRunningTests((prev) => {
        const updated = new Set(prev);
        updated.delete(test.id);
        return updated;
      });
    }
  };

  const exportAllReports = () => {
    const blob = new Blob([JSON.stringify(reports, null, 2)], {
      type: 'application/json'
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `admin-reports-${Date.now()}.json`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="admin-page">
      <header className="admin-header">
        <div className="admin-header__content">
          <h1>운영 관리자 대시보드</h1>
          <p>시스템 테스트 및 모니터링 통합 관리 콘솔</p>
        </div>
        <div className="admin-header__actions">
          <button type="button" onClick={loadRecentReports} className="admin-header__refresh-btn">
            🔄 새로고침
          </button>
          <button type="button" onClick={exportAllReports} className="admin-header__export-btn">
            📥 전체 보고서 내보내기
          </button>
        </div>
      </header>

      {globalStatus && (
        <div className={`admin-status-banner admin-status-banner--${globalStatus.type}`}>
          {globalStatus.message}
        </div>
      )}

      <div className="admin-content">
        {TEST_CATEGORIES.map((category) => (
          <section key={category.id} className="admin-category">
            <h2 className="admin-category__title">
              <span className="admin-category__icon">{category.icon}</span>
              {category.name}
            </h2>
            <div className="admin-category__tests">
              {category.tests.map((test) => (
                <TestCard
                  key={test.id}
                  test={test}
                  onRun={runTest}
                  running={runningTests.has(test.id)}
                  latestReport={reports[test.id]}
                />
              ))}
            </div>
          </section>
        ))}
      </div>
    </div>
  );
}
