package com.yeodam.yeodambe;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;

final class SharedTestMysql {

    private static int databaseSequence;

    private SharedTestMysql() {
    }

    static synchronized JdbcConnectionDetails createDatabase() throws SQLException {
        MySQLContainer mysql = Holder.MYSQL;
        String database = "context_" + ++databaseSequence;
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            statement.execute("GRANT ALL ON " + database + ".* TO 'test'@'%'");
        }

        String jdbcUrl = mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/" + database);
        return new JdbcConnectionDetails() {
            @Override
            public String getJdbcUrl() {
                return jdbcUrl;
            }

            @Override
            public String getUsername() {
                return mysql.getUsername();
            }

            @Override
            public String getPassword() {
                return mysql.getPassword();
            }
        };
    }

    private static final class Holder {
        private static final MySQLContainer MYSQL = startMysql();

        private static MySQLContainer startMysql() {
            MySQLContainer mysql = new MySQLContainer("mysql:9.7.2");
            mysql.start();
            // Spring 컨텍스트의 종료와 분리하고 JVM 종료 시 테스트 컨테이너를 정리한다.
            Runtime.getRuntime().addShutdownHook(new Thread(mysql::stop, "shared-test-mysql-stop"));
            return mysql;
        }
    }
}
