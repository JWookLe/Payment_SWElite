package com.example.monitoring.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 테스트 보고서 DTO
 * AI 분석 결과 및 원시 데이터 포함
 */
public class TestReportDTO {

    private String reportId;
    private String testId;
    private String testName;
    private String status; // success, failure
    private LocalDateTime timestamp;
    private String duration;

    // AI 분석 결과
    private String aiSummary;
    private List<String> recommendations;

    // 주요 메트릭
    private Map<String, String> metrics;

    // 원시 데이터
    private Map<String, Object> rawData;

    public TestReportDTO() {
    }

    public TestReportDTO(String reportId, String testId, String testName, String status,
                         LocalDateTime timestamp, String duration, String aiSummary,
                         List<String> recommendations, Map<String, String> metrics,
                         Map<String, Object> rawData) {
        this.reportId = reportId;
        this.testId = testId;
        this.testName = testName;
        this.status = status;
        this.timestamp = timestamp;
        this.duration = duration;
        this.aiSummary = aiSummary;
        this.recommendations = recommendations;
        this.metrics = metrics;
        this.rawData = rawData;
    }

    // Getters and Setters
    public String getReportId() {
        return reportId;
    }

    public void setReportId(String reportId) {
        this.reportId = reportId;
    }

    public String getTestId() {
        return testId;
    }

    public void setTestId(String testId) {
        this.testId = testId;
    }

    public String getTestName() {
        return testName;
    }

    public void setTestName(String testName) {
        this.testName = testName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public String getDuration() {
        return duration;
    }

    public void setDuration(String duration) {
        this.duration = duration;
    }

    public String getAiSummary() {
        return aiSummary;
    }

    public void setAiSummary(String aiSummary) {
        this.aiSummary = aiSummary;
    }

    public List<String> getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(List<String> recommendations) {
        this.recommendations = recommendations;
    }

    public Map<String, String> getMetrics() {
        return metrics;
    }

    public void setMetrics(Map<String, String> metrics) {
        this.metrics = metrics;
    }

    public Map<String, Object> getRawData() {
        return rawData;
    }

    public void setRawData(Map<String, Object> rawData) {
        this.rawData = rawData;
    }
}
