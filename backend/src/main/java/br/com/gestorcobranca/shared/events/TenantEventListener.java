package br.com.gestorcobranca.shared.events;

import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Base para consumidores de {@link IntegrationEvent}. Garante, numa única transação:
 * <ol>
 *   <li>execução no tenant do evento, como ator de sistema;</li>
 *   <li>deduplicação por {@code (consumidor, eventId)} — reentregas não repetem o efeito;</li>
 *   <li>a ação do consumidor.</li>
 * </ol>
 * Se a ação falhar, a transação é desfeita (inclusive a marcação de consumo), a exceção sobe
 * e o Spring Modulith mantém a publicação como incompleta para reprocessamento.
 * <p>
 * Conflitos de concorrência transitórios ({@link ConcurrencyFailureException}: lock otimista,
 * deadlock, falha de serialização, lock não obtido) são comuns quando vários consumidores reagem
 * ao mesmo fato. Nesses casos a transação inteira é repetida em seguida, até
 * {@value #MAX_ATTEMPTS} tentativas com espera crescente e aleatória, em vez de deixar o evento
 * pendente até a próxima republicação. Cada tentativa é uma transação nova, então a ação vê o
 * estado já confirmado pelo concorrente.
 * <p>
 * Uso:
 * <pre>{@code
 * @Component
 * class PaymentConfirmedListener extends TenantEventListener {
 *     @Async(AsyncConfig.EVENT_EXECUTOR)
 *     @TransactionalEventListener
 *     void on(PaymentConfirmed event) {
 *         handleOnce(event, "collection.payment-confirmed", () -> eligibility.recalculate(...));
 *     }
 * }
 * }</pre>
 */
public abstract class TenantEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(TenantEventListener.class);

    static final int MAX_ATTEMPTS = 4;
    private static final long BASE_BACKOFF_MILLIS = 25;

    private TenantExecution tenantExecution;
    private ConsumedEventRegistry consumedEventRegistry;

    @org.springframework.beans.factory.annotation.Autowired
    final void setInfrastructure(TenantExecution tenantExecution, ConsumedEventRegistry consumedEventRegistry) {
        this.tenantExecution = tenantExecution;
        this.consumedEventRegistry = consumedEventRegistry;
    }

    /**
     * @param consumer nome estável do consumidor ({@code <modulo>.<reacao>}); usado na
     *                 deduplicação — renomear faz eventos já consumidos serem reprocessados.
     */
    protected final void handleOnce(IntegrationEvent event, String consumer, Runnable action) {
        for (int attempt = 1; ; attempt++) {
            try {
                tenantExecution.inTenant(event.tenantId(), consumer, () -> {
                    if (!consumedEventRegistry.markConsumed(consumer, event)) {
                        LOG.debug("Evento {} já consumido por {}; ignorando reentrega", event.eventId(), consumer);
                        return;
                    }
                    action.run();
                });
                return;
            } catch (ConcurrencyFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    logFailure(event, consumer, attempt, e);
                    throw e;
                }
                LOG.info("Conflito de concorrência ao consumir {} ({}), tentativa {} de {}: {}",
                        event.getClass().getSimpleName(), consumer, attempt, MAX_ATTEMPTS, e.getClass().getSimpleName());
                backoff(attempt);
            } catch (RuntimeException e) {
                logFailure(event, consumer, attempt, e);
                throw e;
            }
        }
    }

    /** A publicação fica incompleta e será republicada; o log técnico registra a causa. */
    private static void logFailure(IntegrationEvent event, String consumer, int attempts, RuntimeException e) {
        LOG.error("Falha ao consumir {} (evento {}) por {} após {} tentativa(s); publicação mantida para reprocessamento",
                event.getClass().getSimpleName(), event.eventId(), consumer, attempts, e);
    }

    private static void backoff(int attempt) {
        long max = BASE_BACKOFF_MILLIS << attempt;
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(max / 2, max + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompido aguardando nova tentativa de consumo", e);
        }
    }
}
