package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.collection.InteractionRecorded;
import br.com.gestorcobranca.shared.events.communication.MessageStatusChanged;
import br.com.gestorcobranca.shared.events.identity.TenantProvisioned;
import br.com.gestorcobranca.shared.events.telephony.CallCompleted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;

/** Consumidores de eventos de integração do módulo, idempotentes por evento ({@code shared.consumed_event}). */
@Component
class DebtEventListeners extends TenantEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(DebtEventListeners.class);

    /** Consumidor único do uso do contato: só a primeira vez conta, então a duplicidade entre eventos não altera nada. */
    static final String CONTACT_USAGE = "debt.contact-usage";
    private static final Set<String> MESSAGE_ATTEMPT = Set.of("SENT", "DELIVERED", "READ");

    private final PrescriptionRuleService prescriptionRules;
    private final ContactNoveltyService contactNovelty;

    DebtEventListeners(PrescriptionRuleService prescriptionRules, ContactNoveltyService contactNovelty) {
        this.prescriptionRules = prescriptionRules;
        this.contactNovelty = contactNovelty;
    }

    /** Nova assessoria recebe a tabela de mercado de prescrição, pendente de validação jurídica. */
    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(TenantProvisioned event) {
        handleOnce(event, "debt.prescription-defaults", () -> {
            int created = prescriptionRules.applyMarketDefaults().size();
            LOG.atInfo().addKeyValue("rulesCreated", created).log("Regras de prescrição de mercado semeadas na assessoria");
        });
    }

    // ------------------------------------------------------------------ uso do contato (contatos-recebidos.md §3)

    /** Interação de saída ou com contato efetivo, registrada com o contato usado. */
    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(InteractionRecorded event) {
        if (event.contactId() == null || event.debtorId() == null
                || !("OUTBOUND".equals(event.direction()) || event.effectiveContact())) {
            return;
        }
        handleOnce(event, CONTACT_USAGE, () -> contactNovelty.recordUse(event.debtorId(), event.contactId(),
                event.occurredAt(), event.effectiveContact(), event.channel()));
    }

    /** Ligação discada para o contato (atendida = efetiva) ou recebida dele e atendida. */
    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(CallCompleted event) {
        boolean answered = CallCompleted.ANSWERED.equals(event.result());
        if (event.contactId() == null || event.debtorId() == null || !("OUTBOUND".equals(event.direction()) || answered)) {
            return;
        }
        handleOnce(event, CONTACT_USAGE, () -> contactNovelty.recordUse(event.debtorId(), event.contactId(),
                event.occurredAt(), answered, "PHONE"));
    }

    /** Mensagem enviada/entregue/lida para o contato (tentativa) ou recebida dele (resposta = efetiva). */
    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(MessageStatusChanged event) {
        boolean received = "RECEIVED".equals(event.status());
        if (event.contactId() == null || event.debtorId() == null
                || !(received || MESSAGE_ATTEMPT.contains(event.status()))) {
            return;
        }
        handleOnce(event, CONTACT_USAGE, () -> contactNovelty.recordUse(event.debtorId(), event.contactId(),
                event.occurredAt(), received, event.channel()));
    }
}
