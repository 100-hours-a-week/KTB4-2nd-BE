package com.yeodam.yeodambe;

import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public final class SharedTestMongo {
    private SharedTestMongo() {
    }

    public static String connectionString(String database) {
        GenericContainer<?> mongo = Holder.MONGO;
        return "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017)
                + "/" + database;
    }

    private static final class Holder {
        private static final GenericContainer<?> MONGO = startMongo();

        private static GenericContainer<?> startMongo() {
            GenericContainer<?> mongo = new MongoTestContainer()
                    .withExposedPorts(27017)
                    .waitingFor(Wait.forLogMessage(".*Waiting for connections.*\\n", 1));
            mongo.start();
            String uri = "mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017)
                    + "/admin?serverSelectionTimeoutMS=10000";
            try (var client = MongoClients.create(uri)) {
                client.getDatabase("admin").runCommand(new Document("ping", 1));
            } catch (RuntimeException exception) {
                System.err.println(mongo.getLogs());
                exception.printStackTrace();
                throw exception;
            }
            return mongo;
        }
    }

    static final class MongoTestContainer extends GenericContainer<MongoTestContainer> {
        MongoTestContainer() {
            super("mongo:8.0");
        }

        @Override
        protected void waitUntilContainerStarted() {
            try {
                super.waitUntilContainerStarted();
            } catch (RuntimeException exception) {
                StringBuilder diagnostics = new StringBuilder("Test MongoDB startup failure.");
                try {
                    var info = dockerClient.inspectContainerCmd(getContainerId()).exec();
                    var state = info.getState();
                    diagnostics.append("\nExit code: ").append(state.getExitCodeLong())
                            .append("\nOOM killed: ").append(state.getOOMKilled())
                            .append("\nDocker error: ").append(state.getError())
                            .append("\nContainer output:\n").append(getLogs());
                } catch (RuntimeException diagnosticFailure) {
                    exception.addSuppressed(diagnosticFailure);
                    diagnostics.append("\nCould not inspect failed MongoDB container.");
                }
                throw new IllegalStateException(diagnostics.toString(), exception);
            }
        }
    }
}
