package com.yeodam.yeodambe.trip.migration;

import java.lang.reflect.*;
import java.sql.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Opt-in JDBC timing; preserves the original SQL, connection and transaction calls. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="migration.diagnostics.enabled",havingValue="true")
class MigrationDiagnosticsDataSource {
    @Bean static BeanPostProcessor migrationDiagnosticDataSourceTiming() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                return bean instanceof DataSource ds && name.equals("dataSource") ? wrap(ds) : bean;
            }
        };
    }
    static DataSource wrap(DataSource ds) {
        return new DelegatingDataSource(ds) {
            @Override public Connection getConnection() throws SQLException {
                long start=System.nanoTime();
                try {Connection c=ds.getConnection();return MigrationTripDiagnostics.CURRENT.get()==null ? c : connection(c);}
                finally {MigrationTripDiagnostics.record("connection",start);}
            }
            @Override public Connection getConnection(String user,String password) throws SQLException {
                long start=System.nanoTime();
                try {Connection c=ds.getConnection(user,password);return MigrationTripDiagnostics.CURRENT.get()==null ? c : connection(c);}
                finally {MigrationTripDiagnostics.record("connection",start);}
            }
        };
    }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {return method.invoke(target,args);}
        catch(InvocationTargetException failure) {throw failure.getCause();}
    }
    private static Connection connection(Connection delegate) {
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
            String name=method.getName();long start=System.nanoTime();
            try {
                Object result=invoke(delegate,method,args);
                if(result instanceof Statement statement && (name.equals("prepareStatement") || name.equals("prepareCall") || name.equals("createStatement"))) {
                    String kind=args!=null && args.length>0 && args[0] instanceof String sql ? MigrationTripDiagnostics.sqlKind(sql) : null;
                    Class<?> api=result instanceof CallableStatement ? CallableStatement.class : result instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                    return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{api},(p,m,a)->{
                        if(!m.getName().startsWith("execute")) return invoke(statement,m,a);
                        String category=kind!=null ? kind : a!=null && a.length>0 && a[0] instanceof String sql ? MigrationTripDiagnostics.sqlKind(sql) : "other_sql";
                        long before=System.nanoTime();
                        try {return invoke(statement,m,a);}
                        finally {MigrationTripDiagnostics.record(category,before);}
                    });
                }
                return result;
            } finally {
                if(name.equals("commit") || name.equals("rollback")) MigrationTripDiagnostics.record(name,start);
            }
        });
    }
}
