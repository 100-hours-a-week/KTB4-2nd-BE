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
            GenericContainer<?> mongo = new GenericContainer<>("mongo:8.0")
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
}
