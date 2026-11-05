package br.com.gestorcobranca.telephony.infrastructure.config;

import br.com.gestorcobranca.shared.config.AsyncConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Executor dos comandos à chamada em andamento ({@code bridge}/{@code hangup}) pedidos depois do commit
 * ({@code ProviderCalls}): a thread que confirmou a transação ainda segura a conexão JDBC até o fim da
 * sincronização, então o HTTP ao provedor e as transações próprias (desfazer a conexão, reagendar o
 * encerramento) rodam aqui. Tamanho fixo (bulkhead); com a fila cheia, quem confirmou executa (contrapressão,
 * nada se perde).
 */
@Configuration
class TelephonyCommandExecutorConfig {

    /** Mesmo nome usado no qualificador de {@code ProviderCalls}. */
    static final String EXECUTOR = "telephonyCommandExecutor";

    @Bean(name = EXECUTOR)
    ThreadPoolTaskExecutor telephonyCommandExecutor(@Value("${app.telephony.command-executor.size:8}") int size,
                                                    @Value("${app.telephony.command-executor.queue-capacity:500}") int queue,
                                                    MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("telephony-cmd-");
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(queue);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setTaskDecorator(AsyncConfig.mdcPropagation());
        executor.initialize();
        ExecutorServiceMetrics.monitor(meterRegistry, executor.getThreadPoolExecutor(), "telephony_commands");
        return executor;
    }
}
