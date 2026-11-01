package com.yeodam.yeodambe.integration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class CdcTestEnvironment implements AutoCloseable {
    private final Network network = Network.newNetwork();
    private final MySQLContainer mysql = new MySQLContainer("mysql:9.7.2")
            .withDatabaseName("yeodam").withUsername("test").withPassword("test")
            .withEnv("MYSQL_ROOT_HOST", "%").withNetwork(network).withNetworkAliases("mysql")
            .withCommand("--server-id=223344", "--log-bin=mysql-bin", "--binlog-format=ROW",
                    "--binlog-row-image=FULL", "--innodb-buffer-pool-size=64M");
    private final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1")
            .withNetwork(network).withNetworkAliases("kafka").withListener("kafka:19092")
            .withEnv("KAFKA_HEAP_OPTS", "-Xms64m -Xmx256m");
    private final GenericContainer<?> connect = new GenericContainer<>("quay.io/debezium/connect:3.0.0.Final")
            .withNetwork(network).withExposedPorts(8083)
            .withEnv("BOOTSTRAP_SERVERS", "kafka:19092").withEnv("GROUP_ID", "cdc-test")
            .withEnv("ENABLE_DEBEZIUM_SCRIPTING", "true")
            .withEnv("CONFIG_STORAGE_TOPIC", "connect-configs")
            .withEnv("OFFSET_STORAGE_TOPIC", "connect-offsets")
            .withEnv("STATUS_STORAGE_TOPIC", "connect-status")
            .withEnv("CONFIG_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("OFFSET_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("STATUS_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("HEAP_OPTS", "-Xms64m -Xmx256m")
            .waitingFor(Wait.forHttp("/connectors").forPort(8083))
            .withStartupTimeout(Duration.ofMinutes(2));

    public CdcTestEnvironment() {
        try {
            mysql.start();
            kafka.start();
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public void startConnect() throws Exception {
        for (String engineClass : List.of("groovy.lang.GroovyObject", "org.codehaus.groovy.jsr223.GroovyScriptEngineFactory")) {
            var jar = Path.of(Class.forName(engineClass).getProtectionDomain().getCodeSource().getLocation().toURI());
            connect.withCopyFileToContainer(MountableFile.forHostPath(jar),
                    "/kafka/connect/debezium-connector-mysql/" + jar.getFileName());
        }
        connect.start();
    }

    public MySQLContainer mysql() {
        return mysql;
    }

    public KafkaContainer kafka() {
        return kafka;
    }

    public GenericContainer<?> connect() {
        return connect;
    }

    @Override
    public void close() {
        try {
            connect.close();
        } finally {
            try {
                kafka.close();
            } finally {
                try {
                    mysql.close();
                } finally {
                    network.close();
                }
            }
        }
    }
}
