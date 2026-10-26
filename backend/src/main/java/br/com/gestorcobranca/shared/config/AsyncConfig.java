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
 * eventos. {@link #MAIL_EXECUTOR} é separado para que um servidor SMTP lento ou indisponível não
 * ocupe as threads dos consumidores de eventos. O MDC (requestId) é propagado para correlacionar
 * o efeito assíncrono com a origem.
 * <p>
 * O {@code ThreadPoolExecutor} só cria threads além do núcleo quando a fila enche; por isso o
 * núcleo é o paralelismo efetivo em operação normal.
 */
@Configuration
@EnableAsync
@EnableResilientMethods
public class AsyncConfig {

    public static final String EVENT_EXECUTOR = "eventExecutor";
    public static final String MAIL_EXECUTOR = "mailExecutor";

    @Bean(name = EVENT_EXECUTOR)
    ThreadPoolTaskExecutor eventExecutor(@Value("${app.events.executor.core-size:8}") int coreSize,
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

    @Bean(name = MAIL_EXECUTOR)
    ThreadPoolTaskExecutor mailExecutor(@Value("${app.mail.executor.core-size:2}") int coreSize,
                                        @Value("${app.mail.executor.max-size:4}") int maxSize,
                                        @Value("${app.mail.executor.queue-capacity:500}") int queueCapacity,
                                        MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("mail-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setTaskDecorator(mdcPropagation());
        executor.initialize();
        ExecutorServiceMetrics.monitor(meterRegistry, executor.getThreadPoolExecutor(), "mail");
        return executor;
    }

    /** Propaga o MDC (requestId, tenant, ator) da thread que agenda para a que executa. */
    public static TaskDecorator mdcPropagation() {
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
