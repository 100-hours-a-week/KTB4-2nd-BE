package com.yeodam.yeodambe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.DriverManager;
import java.sql.SQLException;

final class SharedTestMysql {

    private static final Logger log = LoggerFactory.getLogger(SharedTestMysql.class);
    private static int databaseSequence;

    private SharedTestMysql() {
    }

    static synchronized JdbcConnectionDetails createDatabase() throws SQLException {
        MySQLContainer mysql = Holder.MYSQL;
        String database = "context_" + ++databaseSequence;
        initializeDatabase(mysql, database);
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

    private static void initializeDatabase(MySQLContainer mysql, String database) throws SQLException {
        try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            statement.execute("GRANT ALL ON " + database + ".* TO '" + mysql.getUsername() + "'@'%'");
        } catch (SQLException exception) {
            log.error("공유 테스트 MySQL DB 생성 또는 권한 설정 실패: database={}", database);
            throw exception;
        }
    }

    private static final class Holder {

        private static final MySQLContainer MYSQL = startMysql();

        private static MySQLContainer startMysql() {
            MySQLContainer mysql = new MySQLContainer("mysql:9.7.2");
            // 컨텍스트 종료와 분리하고 테스트 JVM 종료 후 Ryuk이 컨테이너를 정리한다.
            mysql.start();
            return mysql;
        }
    }
}
