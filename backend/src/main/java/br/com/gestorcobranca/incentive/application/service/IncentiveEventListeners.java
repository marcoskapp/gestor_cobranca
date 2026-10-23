package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.incentive.application.port.in.IncentiveProvisioning;
import br.com.gestorcobranca.shared.config.AsyncConfig;
import br.com.gestorcobranca.shared.events.TenantEventListener;
import br.com.gestorcobranca.shared.events.collection.CallbackStatusChanged;
import br.com.gestorcobranca.shared.events.collection.CaseAssignmentChanged;
import br.com.gestorcobranca.shared.events.collection.CaseBalanceChanged;
import br.com.gestorcobranca.shared.events.collection.CaseStatusChanged;
import br.com.gestorcobranca.shared.events.collection.InteractionRecorded;
import br.com.gestorcobranca.shared.events.collection.PromiseStatusChanged;
import br.com.gestorcobranca.shared.events.communication.MessageStatusChanged;
import br.com.gestorcobranca.shared.events.identity.TenantProvisioned;
import br.com.gestorcobranca.shared.events.negotiation.AgreementAttributionRecorded;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.events.negotiation.InstallmentStatusChanged;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.events.payment.PaymentReversed;
import br.com.gestorcobranca.shared.events.telephony.CallCompleted;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Consumidores dos eventos de integração do módulo (§9). Idempotentes por evento
 * ({@code shared.consumed_event}) e, para fatos de acordo, também pela chave natural (acordo,
 * pagamento, estorno) sob lock do acordo — eventos do mesmo acordo podem chegar em qualquer ordem.
 */
@Component
class IncentiveEventListeners extends TenantEventListener {

    private final FactIngestionService facts;
    private final CommitmentFactService commitments;
    private final AttributionService attribution;
    private final CommissionPostingService posting;
    private final IncentiveProvisioning provisioning;

    IncentiveEventListeners(FactIngestionService facts, CommitmentFactService commitments, AttributionService attribution,
                            CommissionPostingService posting, IncentiveProvisioning provisioning) {
        this.facts = facts;
        this.commitments = commitments;
        this.attribution = attribution;
        this.posting = posting;
        this.provisioning = provisioning;
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(AgreementAttributionRecorded event) {
        handleOnce(event, "incentive.attribution-recorded", () -> attribution.record(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PaymentConfirmed event) {
        handleOnce(event, "incentive.payment-confirmed", () -> posting.paymentConfirmed(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PaymentReversed event) {
        handleOnce(event, "incentive.payment-reversed", () -> posting.paymentReversed(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(AgreementStatusChanged event) {
        handleOnce(event, "incentive.agreement-status", () -> facts.agreementStatus(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(InstallmentStatusChanged event) {
        handleOnce(event, "incentive.installment-status", () -> facts.installmentStatus(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(InteractionRecorded event) {
        handleOnce(event, "incentive.interaction-recorded", () -> facts.interaction(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(CaseStatusChanged event) {
        handleOnce(event, "incentive.case-status", () -> facts.caseStatus(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(CaseAssignmentChanged event) {
        handleOnce(event, "incentive.case-assignment", () -> facts.caseAssignment(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(CaseBalanceChanged event) {
        handleOnce(event, "incentive.case-balance", () -> facts.caseBalance(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(CallbackStatusChanged event) {
        handleOnce(event, "incentive.callback-status", () -> commitments.callback(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(PromiseStatusChanged event) {
        handleOnce(event, "incentive.promise-status", () -> commitments.promise(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(CallCompleted event) {
        handleOnce(event, "incentive.call-completed", () -> facts.call(event));
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(MessageStatusChanged event) {
        handleOnce(event, "incentive.message-status", () -> facts.message(event));
    }

    /** Plano padrão semeado na nova assessoria. */
    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener
    void on(TenantProvisioned event) {
        handleOnce(event, "incentive.tenant-provisioned", provisioning::ensureDefaultPlan);
    }
}
