package com.yeodam.yeodambe.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "CDC_INTEGRATION_ENABLED", matches = "true")
class DebeziumOutboxIntegrationTest {
    private static final List<String> TABLES = List.of(
            "trip_creation_outbox", "additional_attachment_upload_outbox", "story_generation_outbox");
    private static final String PAYLOAD = "{\"type\":\"story\",\"nullable\":null,\"attachments\":[]}";
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void actualConnectorPreservesWireAndSkipsUpdatesRollbacksAndPollingSnapshots() throws Exception {
        try (var network = Network.newNetwork();
             var mysql = new MySQLContainer("mysql:9.7.2")
                     .withDatabaseName("yeodam").withUsername("test").withPassword("test")
                     .withEnv("MYSQL_ROOT_HOST", "%").withNetwork(network).withNetworkAliases("mysql")
                     .withCommand("--server-id=223344", "--log-bin=mysql-bin", "--binlog-format=ROW",
                             "--binlog-row-image=FULL", "--innodb-buffer-pool-size=64M");
             var kafka = new KafkaContainer("apache/kafka:3.9.1")
                     .withNetwork(network).withNetworkAliases("kafka").withListener("kafka:19092")
                     .withEnv("KAFKA_HEAP_OPTS", "-Xms64m -Xmx256m");
             var connect = new GenericContainer<>("quay.io/debezium/connect:3.0.0.Final")
                     .withNetwork(network).withExposedPorts(8083)
                     .withEnv("BOOTSTRAP_SERVERS", "kafka:19092").withEnv("GROUP_ID", "cdc-test").withEnv("ENABLE_DEBEZIUM_SCRIPTING", "true")
                     .withEnv("CONFIG_STORAGE_TOPIC", "connect-configs")
                     .withEnv("OFFSET_STORAGE_TOPIC", "connect-offsets")
                     .withEnv("STATUS_STORAGE_TOPIC", "connect-status")
                     .withEnv("CONFIG_STORAGE_REPLICATION_FACTOR", "1")
                     .withEnv("OFFSET_STORAGE_REPLICATION_FACTOR", "1")
                     .withEnv("STATUS_STORAGE_REPLICATION_FACTOR", "1")
                     .withEnv("HEAP_OPTS", "-Xms64m -Xmx256m")
                     .waitingFor(Wait.forHttp("/connectors").forPort(8083))
                     .withStartupTimeout(Duration.ofMinutes(2))) {
            mysql.start();
            kafka.start();
            try (var db = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
                 var sql = db.createStatement();
                 var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()));
                 var consumer = consumer(kafka.getBootstrapServers())) {
                admin.createTopics(TABLES.stream().map(table -> new NewTopic(table, 1, (short) 1)
                        .configs(Map.of("message.timestamp.type", "LogAppendTime"))).toList())
                        .all().get(30, TimeUnit.SECONDS);
                for (String table : TABLES) {
                    String idType = table.equals("trip_creation_outbox") ? "VARCHAR(36)" : "BIGINT";
                    sql.execute("CREATE TABLE " + table + " (outbox_id " + idType + " PRIMARY KEY, "
                            + "trip_id BIGINT NOT NULL, topic VARCHAR(255), payload LONGTEXT, "
                            + "event_source VARCHAR(64), request_id VARCHAR(128), "
                            + "cdc_managed BOOLEAN NOT NULL, status VARCHAR(20))");
                    insert(db, table, 1, false, "PENDING");
                    insert(db, table, 2, true, "PUBLISHED");
                    insert(db, table, 3, true, "PENDING");
                }
                consumer.subscribe(TABLES);
                for (String engineClass : List.of("groovy.lang.GroovyObject",
                        "org.codehaus.groovy.jsr223.GroovyScriptEngineFactory")) {
                    var jar = java.nio.file.Path.of(Class.forName(engineClass).getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
                    connect.withCopyFileToContainer(MountableFile.forHostPath(jar),
                            "/kafka/connect/debezium-connector-mysql/" + jar.getFileName());
                }
                connect.start();
                String endpoint = "http://" + connect.getHost() + ":" + connect.getMappedPort(8083);
                Map<String, String> config;
                try (var input = getClass().getResourceAsStream("/cdc/connector.json")) {
                    config = json.readValue(input, new TypeReference<>() {});
                }
                request(endpoint + "/connectors", "POST", json.writeValueAsString(
                        Map.of("name", "outbox", "config", config)));
                awaitRunning(endpoint, connect);
                var snapshots = receive(consumer, 3);
                assertThat(snapshots).withFailMessage("%s%n%s",
                        request(endpoint + "/connectors/outbox/status", "GET", null), connect.getLogs()).hasSize(3);
                for (var record : snapshots) {
                    assertRecord(record, "3");
                }
                for (String table : TABLES) {
                    insert(db, table, 4, true, "PENDING");
                    insert(db, table, 6, false, "PENDING");
                    sql.execute("UPDATE " + table + " SET status = 'PUBLISHED' WHERE outbox_id = '3'");
                    db.setAutoCommit(false);
                    insert(db, table, 5, true, "PENDING");
                    db.rollback();
                    db.setAutoCommit(true);
                }
                var live = receive(consumer, 3);
                assertThat(live).hasSize(3);
                for (var record : live) {
                    assertRecord(record, "4");
                }
                var extras = new ArrayList<ConsumerRecord<String, String>>();
                for (int attempt = 0; attempt < 10; attempt++) {
                    consumer.poll(Duration.ofMillis(500)).forEach(extras::add);
                }
                assertThat(extras).isEmpty();
                request(endpoint + "/connectors/outbox/restart?includeTasks=true&onlyFailed=false", "POST", "");
                for (int attempt = 0; attempt < 10; attempt++) {
                    for (var record : consumer.poll(Duration.ofMillis(500))) {
                        String id = new String(record.headers().lastHeader("id").value(), StandardCharsets.UTF_8);
                        assertThat(id).isIn("3", "4");
                        assertRecord(record, id);
                    }
                }
                // Broker failure keeps committed source rows; recovery uses the saved source offset.
                kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
                try {
                    for (String table : TABLES) {
                        insert(db, table, 7, true, "PENDING");
                    }
                    Thread.sleep(12_000);
                    for (String table : TABLES) {
                        try (var rows = sql.executeQuery("SELECT status FROM " + table + " WHERE outbox_id = '7'")) {
                            assertThat(rows.next()).isTrue();
                            assertThat(rows.getString(1)).isEqualTo("PENDING");
                        }
                    }
                } finally {
                    kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
                }
                request(endpoint + "/connectors/outbox/restart?includeTasks=true&onlyFailed=false", "POST", "");
                awaitRunning(endpoint, connect);
                var recovered = new java.util.HashSet<String>();
                for (int attempt = 0; attempt < 120 && recovered.size() < TABLES.size(); attempt++) {
                    for (var record : consumer.poll(Duration.ofMillis(500))) {
                        String id = new String(record.headers().lastHeader("id").value(), StandardCharsets.UTF_8);
                        assertThat(id).isIn("3", "4", "7");
                        assertRecord(record, id);
                        if ("7".equals(id)) {
                            recovered.add(record.topic());
                        }
                    }
                }
                assertThat(recovered).containsExactlyInAnyOrderElementsOf(TABLES);
                // Restart the same Connect process/container with its Kafka-backed configuration and offsets intact.
                connect.getDockerClient().restartContainerCmd(connect.getContainerId()).exec();
                var bindings = connect.getDockerClient().inspectContainerCmd(connect.getContainerId()).exec()
                        .getNetworkSettings().getPorts().getBindings()
                        .get(com.github.dockerjava.api.model.ExposedPort.tcp(8083));
                endpoint = "http://" + connect.getHost() + ":" + bindings[0].getHostPortSpec();
                awaitRestoredConnect(endpoint, connect);
                awaitRunning(endpoint, connect);
                for (String table : TABLES) {
                    insert(db, table, 8, true, "PENDING");
                }
                var resumed = new java.util.HashSet<String>();
                for (int attempt = 0; attempt < 120 && resumed.size() < TABLES.size(); attempt++) {
                    for (var record : consumer.poll(Duration.ofMillis(500))) {
                        String id = new String(record.headers().lastHeader("id").value(), StandardCharsets.UTF_8);
                        assertThat(id).isIn("3", "4", "7", "8");
                        assertRecord(record, id);
                        if ("8".equals(id)) {
                            resumed.add(record.topic());
                        }
                    }
                }
                assertThat(resumed).containsExactlyInAnyOrderElementsOf(TABLES);
                String status = request(endpoint + "/connectors/outbox/status", "GET", null);
                assertThat(json.readTree(status).path("tasks").get(0).path("state").asString()).isEqualTo("RUNNING");
            }
        }
    }

    private void insert(java.sql.Connection db, String table, int id, boolean cdc, String status) throws Exception {
        try (var statement = db.prepareStatement("INSERT INTO " + table
                + " (outbox_id, trip_id, topic, payload, event_source, request_id, cdc_managed, status)"
                + " VALUES (?, 77, ?, ?, ?, 'trace-test', ?, ?)")) {
            statement.setString(1, Integer.toString(id));
            statement.setString(2, table);
            statement.setString(3, PAYLOAD);
            statement.setString(4, table);
            statement.setBoolean(5, cdc);
            statement.setString(6, status);
            statement.executeUpdate();
        }
    }

    private void awaitRestoredConnect(String endpoint, GenericContainer<?> connect) throws Exception {
        for (int attempt = 0; attempt < 120; attempt++) {
            try {
                var response = http.send(HttpRequest.newBuilder(URI.create(endpoint + "/connectors"))
                        .timeout(Duration.ofSeconds(1)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (java.io.IOException notReady) {
                // The restarted process has not opened its REST port yet.
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Restarted Connect REST endpoint is unavailable: " + connect.getLogs());
    }

    private void awaitRunning(String endpoint, GenericContainer<?> connect) throws Exception {
        for (int attempt = 0; attempt < 300; attempt++) {
            var tasks = json.readTree(request(endpoint + "/connectors/outbox/status", "GET", null)).path("tasks");
            if (!tasks.isEmpty()) {
                String state = tasks.get(0).path("state").asString();
                assertThat(state).withFailMessage(connect.getLogs()).isNotEqualTo("FAILED");
                if ("RUNNING".equals(state)) {
                    return;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Connector did not start: " + connect.getLogs());
    }

    private KafkaConsumer<String, String> consumer(String bootstrap) {
        return new KafkaConsumer<>(Map.of(
                "bootstrap.servers", bootstrap, "group.id", "cdc-wire-test", "auto.offset.reset", "earliest",
                "enable.auto.commit", "false",
                "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"));
    }

    private List<ConsumerRecord<String, String>> receive(KafkaConsumer<String, String> consumer, int count) {
        var records = new ArrayList<ConsumerRecord<String, String>>();
        for (int attempt = 0; attempt < 120 && records.size() < count; attempt++) {
            consumer.poll(Duration.ofMillis(500)).forEach(records::add);
        }
        return records;
    }

    private void assertRecord(ConsumerRecord<String, String> record, String id) {
        assertThat(record.timestampType()).isEqualTo(org.apache.kafka.common.record.TimestampType.LOG_APPEND_TIME);
        assertThat(record.timestamp()).isPositive();
        assertThat(record.key()).isEqualTo("77");
        assertThat(record.value()).isEqualTo(PAYLOAD);
        assertThat(new String(record.headers().lastHeader("id").value(), StandardCharsets.UTF_8)).isEqualTo(id);
        assertThat(new String(record.headers().lastHeader("outbox-source").value(), StandardCharsets.UTF_8))
                .isEqualTo(record.topic());
        assertThat(new String(record.headers().lastHeader("X-Request-ID").value(), StandardCharsets.UTF_8))
                .isEqualTo("trace-test");
    }

    private String request(String url, String method, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage(response.body()).isBetween(200, 299);
        return response.body();
    }
}
