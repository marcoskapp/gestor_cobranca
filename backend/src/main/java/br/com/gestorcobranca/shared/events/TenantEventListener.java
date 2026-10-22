package br.com.gestorcobranca.shared.events;

import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        tenantExecution.inTenant(event.tenantId(), consumer, () -> {
            if (!consumedEventRegistry.markConsumed(consumer, event)) {
                LOG.debug("Evento {} já consumido por {}; ignorando reentrega", event.eventId(), consumer);
                return;
            }
            action.run();
        });
    }
}
