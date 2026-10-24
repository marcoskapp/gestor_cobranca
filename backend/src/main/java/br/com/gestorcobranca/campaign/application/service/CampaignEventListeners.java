package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.collection.CaseAssignmentChanged;
import br.com.gestorcobranca.shared.events.collection.CaseBalanceChanged;
import br.com.gestorcobranca.shared.events.collection.CaseStatusChanged;
import br.com.gestorcobranca.shared.events.collection.DisputeStatusChanged;
import br.com.gestorcobranca.shared.events.collection.InteractionRecorded;
import br.com.gestorcobranca.shared.events.collection.PromiseStatusChanged;
import br.com.gestorcobranca.shared.events.communication.MessageStatusChanged;
import br.com.gestorcobranca.shared.events.compliance.ContactRestrictionChanged;
import br.com.gestorcobranca.shared.events.legal.LegalMatterStageChanged;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.events.payment.PaymentReversed;
import br.com.gestorcobranca.shared.events.scoring.CaseScoreChanged;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Consumidores dos eventos de integração, idempotentes por evento ({@code shared.consumed_event}).
 * Nomes de consumidor estáveis: renomear faz eventos já consumidos serem reprocessados.
 */
@Component
class CampaignEventListeners extends TenantEventListener {

    private final CampaignReactions reactions;
    private final CampaignMetrics metrics;

    CampaignEventListeners(CampaignReactions reactions, CampaignMetrics metrics) {
        this.reactions = reactions;
        this.metrics = metrics;
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(CaseStatusChanged event) {
        handleOnce(event, "campaign.case-status", () -> {
            reactions.caseChanged(event.caseId(), "case_status");
            metrics.eventConsumed("case_status");
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(CaseScoreChanged event) {
        handleOnce(event, "campaign.case-score", () -> {
            reactions.caseScoreChanged(event);
            metrics.eventConsumed("case_score");
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(CaseBalanceChanged event) {
        handleOnce(event, "campaign.case-balance", () -> {
            reactions.caseChanged(event.caseId(), "case_balance");
            metrics.eventConsumed("case_balance");
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(CaseAssignmentChanged event) {
        handleOnce(event, "campaign.case-assignment", () -> {
            reactions.caseChanged(event.caseId(), "case_assignment");
            metrics.eventConsumed("case_assignment");
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PromiseStatusChanged event) {
        handleOnce(event, "campaign.promise", () -> {
            reactions.caseChanged(event.caseId(), "promise");
            metrics.eventConsumed("promise");
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(InteractionRecorded event) {
        handleOnce(event, "campaign.interaction", () -> {
            if (event.effectiveContact()) {
                reactions.effectiveContact(event.debtorId(), event.creditorId(), event.interactionId(), event.occurredAt());
            }
            reactions.caseChanged(event.caseId(), "interaction");
            metrics.eventConsumed("interaction");
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(DisputeStatusChanged event) {
        handleOnce(event, "campaign.dispute", () -> {
            reactions.caseChanged(event.caseId(), "dispute");
            metrics.eventConsumed("dispute");
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(LegalMatterStageChanged event) {
        handleOnce(event, "campaign.legal-stage", () -> {
            reactions.caseChanged(event.caseId(), "legal_stage");
            metrics.eventConsumed("legal_stage");
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(AgreementStatusChanged event) {
        handleOnce(event, "campaign.agreement-status", () -> {
            reactions.agreementStatusChanged(event);
            metrics.eventConsumed("agreement_status");
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PaymentConfirmed event) {
        handleOnce(event, "campaign.payment-confirmed", () -> {
            reactions.paymentConfirmed(event);
            metrics.eventConsumed("payment_confirmed");
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PaymentReversed event) {
        handleOnce(event, "campaign.payment-reversed", () -> {
            reactions.paymentReversed(event);
            metrics.eventConsumed("payment_reversed");
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(ContactRestrictionChanged event) {
        handleOnce(event, "campaign.contact-restriction", () -> {
            reactions.contactRestrictionChanged(event);
            metrics.eventConsumed("contact_restriction");
        });
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(MessageStatusChanged event) {
        handleOnce(event, "campaign.message-status", () -> {
            reactions.messageStatusChanged(event);
            metrics.eventConsumed("message_status");
        });
    }
}
