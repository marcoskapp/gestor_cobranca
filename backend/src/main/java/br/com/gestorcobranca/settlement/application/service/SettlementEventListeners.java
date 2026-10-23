package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.port.out.SettlementSources;
import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.IntegrationEvent;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.legal.LegalExpenseChanged;
import br.com.gestorcobranca.shared.events.negotiation.AgreementAttributionRecorded;
import br.com.gestorcobranca.shared.events.negotiation.AgreementPaymentRecorded;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.events.payment.PaymentReversed;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Consumidores dos eventos de integração do módulo. Idempotentes por evento ({@code shared.consumed_event})
 * e pela chave natural (alocação, estorno, acordo, despesa) sob lock — eventos da mesma alocação podem chegar
 * em qualquer ordem. Os fatos de outros módulos (payment, negotiation, organization) são lidos antes da
 * transação do consumidor, para que ele use uma conexão por vez.
 */
@Component
class SettlementEventListeners extends TenantEventListener {

    private final ReceiptPostingService posting;
    private final SettlementFactIngestion facts;
    private final SettlementSources sources;
    private final TenantExecution tenantExecution;

    SettlementEventListeners(ReceiptPostingService posting, SettlementFactIngestion facts, SettlementSources sources,
                             TenantExecution tenantExecution) {
        this.posting = posting;
        this.facts = facts;
        this.sources = sources;
        this.tenantExecution = tenantExecution;
    }

    /** Leitura como sistema no tenant do evento, sem transação própria (cada caso de uso lido abre a sua). */
    private <T> T prefetch(IntegrationEvent event, Supplier<T> read) {
        AtomicReference<T> result = new AtomicReference<>();
        tenantExecution.withTenantContext(event.tenantId(), "settlement.prefetch", () -> result.set(read.get()));
        return result.get();
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PaymentConfirmed event) {
        if (event.agreementId() != null) {
            handleOnce(event, "settlement.payment-confirmed", () -> posting.paymentConfirmed(event, sources));
            return;
        }
        SettlementSources lookups = prefetch(event, () -> SourceSnapshot.forTitlePayment(sources, event));
        handleOnce(event, "settlement.payment-confirmed", () -> posting.paymentConfirmed(event, lookups));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(AgreementPaymentRecorded event) {
        SettlementSources lookups = "APPLIED".equals(event.status())
                ? prefetch(event, () -> SourceSnapshot.forAgreementPayment(sources, event)) : sources;
        handleOnce(event, "settlement.agreement-payment-recorded", () -> posting.agreementPaymentRecorded(event, lookups));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PaymentReversed event) {
        handleOnce(event, "settlement.payment-reversed", () -> posting.paymentReversed(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(AgreementAttributionRecorded event) {
        var installments = prefetch(event, () -> sources.agreementInstallmentCount(event.agreementId()));
        handleOnce(event, "settlement.agreement-attribution", () -> facts.agreementAttributed(event, installments));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(AgreementStatusChanged event) {
        handleOnce(event, "settlement.agreement-status", () -> facts.agreementStatusChanged(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(LegalExpenseChanged event) {
        handleOnce(event, "settlement.legal-expense", () -> facts.legalExpenseChanged(event));
    }
}
