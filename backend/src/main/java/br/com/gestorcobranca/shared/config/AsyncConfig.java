package br.com.gestorcobranca.shared.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.resilience.annotation.EnableResilientMethods;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Executores assíncronos. {@link #EVENT_EXECUTOR} atende os consumidores de eventos de
 * integração; a política {@code CallerRuns} aplica contrapressão em picos em vez de descartar
 * eventos. O MDC (requestId) é propagado para correlacionar o efeito assíncrono com a origem.
 */
@Configuration
@EnableAsync
@EnableResilientMethods
public class AsyncConfig {

    public static final String EVENT_EXECUTOR = "eventExecutor";

    @Bean(name = EVENT_EXECUTOR)
    ThreadPoolTaskExecutor eventExecutor(@Value("${app.events.executor.core-size:4}") int coreSize,
                                         @Value("${app.events.executor.max-size:16}") int maxSize,
                                         @Value("${app.events.executor.queue-capacity:1000}") int queueCapacity,
                                         MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("event-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setTaskDecorator(mdcPropagation());
        executor.initialize();
        ExecutorServiceMetrics.monitor(meterRegistry, executor.getThreadPoolExecutor(), "events");
        return executor;
    }

    static TaskDecorator mdcPropagation() {
        return runnable -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    runnable.run();
                } finally {
                    if (previous == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(previous);
                    }
                }
            };
        };
    }
}
