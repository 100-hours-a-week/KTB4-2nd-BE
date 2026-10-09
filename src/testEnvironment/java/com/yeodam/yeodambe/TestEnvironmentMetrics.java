package com.yeodam.yeodambe;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.DockerClientFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public class TestEnvironmentMetrics implements LauncherSessionListener, ContextCustomizerFactory, TestExecutionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        record("jvm_start", DockerClientFactory.SESSION_ID,
                Long.toString(ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli()));
        if (System.getProperty("test.environment.output") != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> record("jvm_end", "", ""), "test-environment-metrics-end"
            ));
        }
    }

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass,
            List<ContextConfigurationAttributes> configAttributes
    ) {
        return new MetricsCustomizer();
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        if (testContext.hasApplicationContext()) {
            record("class_use", contextId(testContext.getApplicationContext()), testContext.getTestClass().getName());
        }
    }

    private static String contextId(Object context) {
        return Integer.toHexString(System.identityHashCode(context));
    }

    private static synchronized void record(String event, String id, String detail) {
        String directory = System.getProperty("test.environment.output");
        if (directory == null) {
            return;
        }
        try {
            Path path = Path.of(directory);
            Files.createDirectories(path);
            Files.writeString(path.resolve("events.tsv"), String.join("\t",
                    Long.toString(System.currentTimeMillis()),
                    Long.toString(ProcessHandle.current().pid()),
                    event, id, detail
            ) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new UncheckedIOException("테스트 환경 측정 기록에 실패했습니다.", failure);
        }
    }

    private record MetricsCustomizer() implements ContextCustomizer {
        @Override
        public void customizeContext(
                ConfigurableApplicationContext context,
                MergedContextConfiguration mergedConfig
        ) {
            context.addApplicationListener(event -> {
                if (event.getSource() == context && event instanceof ContextRefreshedEvent) {
                    record("context_refresh", contextId(context), Integer.toHexString(mergedConfig.hashCode()));
                }
                if (event.getSource() == context && event instanceof ContextClosedEvent) {
                    record("context_close", contextId(context), "");
                }
            });
        }
    }
}
