package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.collection.InteractionRecorded;
import br.com.gestorcobranca.shared.events.communication.MessageStatusChanged;
import br.com.gestorcobranca.shared.events.debt.DebtorAnonymized;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.events.telephony.CallCompleted;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Consumidores da inteligência de contato, separados dos fatos do score ({@code scoring.contact.*}): uma falha num
 * não segura o outro. Idempotentes por evento em {@code shared.consumed_event} e pela chave do fato.
 */
@Component
class ContactIntelligenceListeners extends TenantEventListener {

    private final ContactFactRecorder recorder;
    private final ScoringMetrics metrics;

    ContactIntelligenceListeners(ContactFactRecorder recorder, ScoringMetrics metrics) {
        this.recorder = recorder;
        this.metrics = metrics;
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(InteractionRecorded event) {
        handleOnce(event, "scoring.contact.interaction", () -> {
            metrics.eventConsumed("contact_interaction");
            recorder.interaction(event);
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(CallCompleted event) {
        handleOnce(event, "scoring.contact.call", () -> {
            metrics.eventConsumed("contact_call");
            recorder.call(event);
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(MessageStatusChanged event) {
        handleOnce(event, "scoring.contact.message", () -> {
            metrics.eventConsumed("contact_message");
            recorder.message(event);
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(AgreementStatusChanged event) {
        handleOnce(event, "scoring.contact.agreement", () -> {
            metrics.eventConsumed("contact_agreement");
            recorder.agreement(event);
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(DebtorAnonymized event) {
        handleOnce(event, "scoring.contact.debtor-anonymized", () -> {
            metrics.eventConsumed("contact_debtor_anonymized");
            recorder.debtorAnonymized(event);
        });
    }
}
