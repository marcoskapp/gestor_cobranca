package br.com.gestorcobranca.portal.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Envio do código de acesso do portal fora da requisição (revisão de segurança S3): a resposta ao
 * pedido de código tem o mesmo trabalho e o mesmo tempo para documento existente ou não, e a latência do
 * envio (porteiro, template, provedor) não vira oráculo. O código existe só em memória até o envio; fila
 * cheia descarta o envio (o devedor pede outro código) em vez de executar na thread da requisição, o que
 * reabriria a diferença de tempo.
 */
@Configuration
class PortalDispatchConfig {

    static final String EXECUTOR = "portalCodeDispatchExecutor";

    @Bean(name = EXECUTOR)
    ThreadPoolTaskExecutor portalCodeDispatchExecutor(@Value("${app.portal.access.dispatch.core-size:2}") int coreSize,
                                                      @Value("${app.portal.access.dispatch.max-size:4}") int maxSize,
                                                      @Value("${app.portal.access.dispatch.queue-capacity:500}") int queueCapacity,
                                                      MeterRegistry meterRegistry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("portal-code-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.setTaskDecorator(runnable -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        });
        executor.initialize();
        ExecutorServiceMetrics.monitor(meterRegistry, executor.getThreadPoolExecutor(), "portal_code_dispatch");
        return executor;
    }
}
