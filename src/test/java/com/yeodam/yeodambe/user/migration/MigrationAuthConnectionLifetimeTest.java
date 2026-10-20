package com.yeodam.yeodambe.user.migration;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationAuthConnectionLifetimeTest {
    @Test
    void request_scoped_entity_manager_retains_connection_until_request_closes() {
        try (var pool = new HikariDataSource()) {
            pool.setJdbcUrl("jdbc:h2:mem:connection382;DB_CLOSE_DELAY=-1");
            pool.setMaximumPoolSize(1);
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(pool);
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setPackagesToScan("com.yeodam.yeodambe.connectionprobe");
            factory.afterPropertiesSet();
            var emf = factory.getObject();
            var tx = new TransactionTemplate(new JpaTransactionManager(emf));
            tx.setReadOnly(true);
            var jdbc = new JdbcTemplate(pool);
            var requestEntityManager = emf.createEntityManager();
            TransactionSynchronizationManager.bindResource(emf, new EntityManagerHolder(requestEntityManager));
            try {
                tx.execute(ignored -> jdbc.queryForObject("SELECT 1", Integer.class));
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
            } finally {
                TransactionSynchronizationManager.unbindResource(emf);
                requestEntityManager.close();
            }
            tx.execute(ignored -> jdbc.queryForObject("SELECT 1", Integer.class));
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            factory.destroy();
        }
    }
}
