package br.com.gestorcobranca.pendingwork.infrastructure.config;

import br.com.gestorcobranca.pendingwork.application.PendingWorkProperties;
import br.com.gestorcobranca.shared.config.AsyncConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Executor das fontes de pendências: tamanho fixo (bulkhead) para que a tela inicial de muitos
 * usuários não esgote o pool de conexões; fila limitada e recusa explícita (a fonte volta como
 * {@code BUSY}, sem bloquear a requisição).
 */
@Configuration
class PendingWorkExecutorConfig {

    static final String EXECUTOR = "pendingWorkExecutor";

    @Bean(name = EXECUTOR)
    ThreadPoolTaskExecutor pendingWorkExecutor(PendingWorkProperties properties, MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("pending-work-");
        executor.setCorePoolSize(properties.executorSize());
        executor.setMaxPoolSize(properties.executorSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setTaskDecorator(AsyncConfig.mdcPropagation());
        executor.initialize();
        ExecutorServiceMetrics.monitor(meterRegistry, executor.getThreadPoolExecutor(), "pending_work");
        return executor;
    }
}
