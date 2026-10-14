package com.yeodam.yeodambe.migrationprobe.controller;

import com.yeodam.yeodambe.migrationprobe.filter.MigrationProbeBodyLimitFilter;
import com.yeodam.yeodambe.migrationprobe.repository.MigrationProbeRepository;
import com.yeodam.yeodambe.migrationprobe.service.MigrationProbeService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class MigrationProbeProfileIntegrationTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProbeBeans.class)
            .withBean(JdbcTemplate.class, () -> jdbc)
            .withBean(PlatformTransactionManager.class, () -> transactions);

    @ParameterizedTest
    @ValueSource(strings = {"staging", "prod"})
    void activeProfilesRegisterAllProbeBeansWithoutQueryingTable(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
            assertThat(context).hasSingleBean(MigrationProbeController.class);
            assertThat(context).hasSingleBean(MigrationProbeService.class);
            assertThat(context).hasSingleBean(MigrationProbeRepository.class);
            assertThat(context).hasSingleBean(MigrationProbeBodyLimitFilter.class);
            verify(jdbc).afterPropertiesSet();
            verifyNoMoreInteractions(jdbc);
            verifyNoInteractions(transactions);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "local", "performance"})
    void inactiveProfilesDoNotRegisterProbeBeans(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
            assertThat(context).doesNotHaveBean(MigrationProbeController.class);
            assertThat(context).doesNotHaveBean(MigrationProbeService.class);
            assertThat(context).doesNotHaveBean(MigrationProbeRepository.class);
            assertThat(context).doesNotHaveBean(MigrationProbeBodyLimitFilter.class);
            verify(jdbc).afterPropertiesSet();
            verifyNoMoreInteractions(jdbc);
            verifyNoInteractions(transactions);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({MigrationProbeController.class, MigrationProbeService.class,
            MigrationProbeRepository.class, MigrationProbeBodyLimitFilter.class})
    static class ProbeBeans {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
