package com.yeodam.yeodambe.story.service;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@TestConfiguration(proxyBeanMethods = false)
class StorySqlCounterConfiguration {
    private static final ThreadLocal<List<String>> QUERIES = new ThreadLocal<>();

    static void begin() {
        QUERIES.set(new ArrayList<>());
    }

    static List<String> finish() {
        List<String> queries = List.copyOf(QUERIES.get());
        QUERIES.remove();
        return queries;
    }

    static void clear() {
        QUERIES.remove();
    }

    @Bean
    static BeanPostProcessor countStorySql() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource) {
                    return new DelegatingDataSource(dataSource) {
                        @Override
                        public Connection getConnection() throws SQLException {
                            return count(super.getConnection());
                        }

                        @Override
                        public Connection getConnection(String username, String password) throws SQLException {
                            return count(super.getConnection(username, password));
                        }
                    };
                }
                return bean;
            }
        };
    }

    private static Connection count(Connection connection) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    List<String> queries = QUERIES.get();
                    if (queries != null && method.getName().equals("prepareStatement")
                            && arguments != null && arguments[0] instanceof String sql) {
                        queries.add(sql);
                    }
                    try {
                        return method.invoke(connection, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                }
        );
    }
}
