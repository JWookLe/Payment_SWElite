package com.example.monitoring.controller;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/monitoring/kafka")
public class KafkaMonitoringController {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    private AdminClient getAdminClient() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        return AdminClient.create(props);
    }

    private KafkaConsumer<String, String> getConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "monitoring-temp-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(props);
    }

    /**
     * 토픽 목록 조회
     */
    @GetMapping("/topics")
    public Map<String, Object> listTopics() {
        try (AdminClient admin = getAdminClient()) {
            ListTopicsResult topics = admin.listTopics();
            Set<String> topicNames = topics.names().get();

            List<String> paymentTopics = topicNames.stream()
                    .filter(name -> name.startsWith("payment."))
                    .sorted()
                    .collect(Collectors.toList());

            return Map.of(
                    "count", paymentTopics.size(),
                    "topics", paymentTopics,
                    "message", "Successfully retrieved Kafka topics"
            );
        } catch (Exception e) {
            return Map.of(
                    "error", true,
                    "message", "Failed to retrieve topics: " + e.getMessage()
            );
        }
    }

    /**
     * 토픽별 메시지 수 조회
     */
    @GetMapping("/topic-stats")
    public Map<String, Object> getTopicStats(@RequestParam(required = false) String topic) {
        try (KafkaConsumer<String, String> consumer = getConsumer()) {
            List<String> topics = topic != null
                    ? List.of(topic)
                    : List.of("payment.authorized", "payment.captured", "payment.refunded", "payment.dlq");

            List<Map<String, Object>> stats = new ArrayList<>();

            for (String t : topics) {
                try {
                    List<TopicPartition> partitions = consumer.partitionsFor(t).stream()
                            .map(p -> new TopicPartition(t, p.partition()))
                            .collect(Collectors.toList());

                    Map<TopicPartition, Long> beginningOffsets = consumer.beginningOffsets(partitions);
                    Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);

                    long totalMessages = endOffsets.entrySet().stream()
                            .mapToLong(e -> e.getValue() - beginningOffsets.get(e.getKey()))
                            .sum();

                    stats.add(Map.of(
                            "topic", t,
                            "partitions", partitions.size(),
                            "totalMessages", totalMessages,
                            "endOffsets", endOffsets.entrySet().stream()
                                    .collect(Collectors.toMap(
                                            e -> "partition-" + e.getKey().partition(),
                                            Map.Entry::getValue
                                    ))
                    ));
                } catch (Exception e) {
                    stats.add(Map.of(
                            "topic", t,
                            "error", "Topic not found or empty"
                    ));
                }
            }

            return Map.of(
                    "count", stats.size(),
                    "topics", stats,
                    "message", "Topic statistics retrieved successfully"
            );
        } catch (Exception e) {
            return Map.of(
                    "error", true,
                    "message", "Failed to retrieve topic stats: " + e.getMessage()
            );
        }
    }

    /**
     * Consumer Group 상태 조회
     */
    @GetMapping("/consumer-groups")
    public Map<String, Object> listConsumerGroups() {
        try (AdminClient admin = getAdminClient()) {
            Collection<ConsumerGroupListing> groups = admin.listConsumerGroups().all().get();

            List<Map<String, Object>> groupList = groups.stream()
                    .map(g -> Map.of(
                            "groupId", g.groupId(),
                            "isSimpleConsumerGroup", g.isSimpleConsumerGroup(),
                            "state", g.state().map(Enum::name).orElse("UNKNOWN")
                    ))
                    .collect(Collectors.toList());

            return Map.of(
                    "count", groupList.size(),
                    "groups", groupList,
                    "message", "Consumer groups retrieved successfully"
            );
        } catch (Exception e) {
            return Map.of(
                    "error", true,
                    "message", "Failed to retrieve consumer groups: " + e.getMessage()
            );
        }
    }

    /**
     * Consumer Lag 조회
     */
    @GetMapping("/consumer-lag")
    public Map<String, Object> getConsumerLag(@RequestParam String groupId) {
        try (AdminClient admin = getAdminClient();
             KafkaConsumer<String, String> consumer = getConsumer()) {

            // Consumer Group의 오프셋 조회
            ListConsumerGroupOffsetsResult offsetsResult = admin.listConsumerGroupOffsets(groupId);
            Map<TopicPartition, OffsetAndMetadata> currentOffsets = offsetsResult.partitionsToOffsetAndMetadata().get();

            if (currentOffsets.isEmpty()) {
                return Map.of(
                        "groupId", groupId,
                        "message", "No active consumers or no committed offsets",
                        "lag", 0
                );
            }

            // End Offset 조회
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(currentOffsets.keySet());

            // Lag 계산
            List<Map<String, Object>> lagDetails = new ArrayList<>();
            long totalLag = 0;

            for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : currentOffsets.entrySet()) {
                TopicPartition tp = entry.getKey();
                long currentOffset = entry.getValue().offset();
                long endOffset = endOffsets.getOrDefault(tp, 0L);
                long lag = Math.max(0, endOffset - currentOffset);
                totalLag += lag;

                lagDetails.add(Map.of(
                        "topic", tp.topic(),
                        "partition", tp.partition(),
                        "currentOffset", currentOffset,
                        "endOffset", endOffset,
                        "lag", lag
                ));
            }

            String status = totalLag == 0 ? "OK" : (totalLag > 100 ? "WARNING" : "MINOR_LAG");

            return Map.of(
                    "groupId", groupId,
                    "totalLag", totalLag,
                    "status", status,
                    "partitions", lagDetails,
                    "message", totalLag == 0 ? "No lag - all caught up" : "Consumer lag detected"
            );
        } catch (ExecutionException e) {
            if (e.getCause() instanceof org.apache.kafka.common.errors.GroupIdNotFoundException) {
                return Map.of(
                        "error", true,
                        "groupId", groupId,
                        "message", "Consumer group not found"
                );
            }
            return Map.of(
                    "error", true,
                    "message", "Failed to retrieve consumer lag: " + e.getMessage()
            );
        } catch (Exception e) {
            return Map.of(
                    "error", true,
                    "message", "Failed to retrieve consumer lag: " + e.getMessage()
            );
        }
    }

    /**
     * DLQ 메시지 조회 (최근 N개)
     */
    @GetMapping("/dlq-messages")
    public Map<String, Object> getDlqMessages(@RequestParam(defaultValue = "10") int limit) {
        try (KafkaConsumer<String, String> consumer = getConsumer()) {
            String dlqTopic = "payment.dlq";
            List<TopicPartition> partitions = consumer.partitionsFor(dlqTopic).stream()
                    .map(p -> new TopicPartition(dlqTopic, p.partition()))
                    .collect(Collectors.toList());

            consumer.assign(partitions);

            // 최근 메시지부터 읽기 위해 끝에서 limit만큼 뒤로
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            for (Map.Entry<TopicPartition, Long> entry : endOffsets.entrySet()) {
                long offset = Math.max(0, entry.getValue() - limit);
                consumer.seek(entry.getKey(), offset);
            }

            List<Map<String, Object>> messages = new ArrayList<>();
            var records = consumer.poll(Duration.ofSeconds(2));

            records.forEach(record -> {
                messages.add(Map.of(
                        "partition", record.partition(),
                        "offset", record.offset(),
                        "timestamp", record.timestamp(),
                        "key", record.key() != null ? record.key() : "null",
                        "value", record.value() != null ? record.value().substring(0, Math.min(200, record.value().length())) : "null"
                ));
            });

            return Map.of(
                    "topic", dlqTopic,
                    "count", messages.size(),
                    "messages", messages,
                    "message", messages.isEmpty() ? "No DLQ messages found" : "DLQ messages retrieved"
            );
        } catch (Exception e) {
            return Map.of(
                    "error", true,
                    "message", "Failed to retrieve DLQ messages: " + e.getMessage()
            );
        }
    }

    /**
     * Kafka 클러스터 헬스 체크
     */
    @GetMapping("/health")
    public Map<String, Object> checkHealth() {
        try (AdminClient admin = getAdminClient()) {
            DescribeClusterResult cluster = admin.describeCluster();
            int nodeCount = cluster.nodes().get().size();
            String clusterId = cluster.clusterId().get();

            return Map.of(
                    "healthy", true,
                    "clusterId", clusterId,
                    "brokerCount", nodeCount,
                    "message", "Kafka cluster is healthy"
            );
        } catch (Exception e) {
            return Map.of(
                    "healthy", false,
                    "message", "Kafka cluster unreachable: " + e.getMessage()
            );
        }
    }

    /**
     * 토픽 상세 정보 (파티션, 레플리카 등)
     */
    @GetMapping("/topic-details")
    public Map<String, Object> getTopicDetails(@RequestParam String topic) {
        try (AdminClient admin = getAdminClient()) {
            DescribeTopicsResult result = admin.describeTopics(Collections.singleton(topic));
            TopicDescription description = result.all().get().get(topic);

            List<Map<String, Object>> partitionInfo = description.partitions().stream()
                    .map(p -> Map.of(
                            "partition", p.partition(),
                            "leader", p.leader().id(),
                            "replicas", p.replicas().stream().map(n -> n.id()).collect(Collectors.toList()),
                            "isr", p.isr().stream().map(n -> n.id()).collect(Collectors.toList())
                    ))
                    .collect(Collectors.toList());

            return Map.of(
                    "topic", topic,
                    "partitionCount", description.partitions().size(),
                    "partitions", partitionInfo,
                    "message", "Topic details retrieved successfully"
            );
        } catch (Exception e) {
            return Map.of(
                    "error", true,
                    "message", "Failed to retrieve topic details: " + e.getMessage()
            );
        }
    }
}
