package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.application.port.out.TurnContentStore;
import br.com.gestorcobranca.assistant.domain.model.AssistantAgreementFact;
import br.com.gestorcobranca.assistant.domain.model.PixRequest;
import br.com.gestorcobranca.assistant.domain.model.TurnReview;
import br.com.gestorcobranca.assistant.domain.repository.AssistantAgreementFactRepository;
import br.com.gestorcobranca.assistant.domain.repository.PixRequestRepository;
import br.com.gestorcobranca.assistant.domain.repository.TurnReviewRepository;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.communication.MessageStatusChanged;
import br.com.gestorcobranca.shared.events.debt.DebtorAnonymized;
import br.com.gestorcobranca.shared.events.negotiation.AgreementAttributionRecorded;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.events.payment.PaymentInstructionStatusChanged;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Reações aos eventos de integração (executadas por {@link AssistantEventListeners}). */
@Component
class AssistantReactions {

    private final JobQueue jobQueue;
    private final AssistantAgreementFactRepository facts;
    private final TurnReviewRepository reviews;
    private final TurnContentStore content;
    private final CurrentActor currentActor;
    private final AssistantMetrics metrics;
    private final PixRequestRepository pixRequests;
    private final AssistantPixRecorder pixRecorder;

    AssistantReactions(JobQueue jobQueue, AssistantAgreementFactRepository facts, TurnReviewRepository reviews,
                       TurnContentStore content, CurrentActor currentActor, AssistantMetrics metrics,
                       PixRequestRepository pixRequests, AssistantPixRecorder pixRecorder) {
        this.pixRequests = pixRequests;
        this.pixRecorder = pixRecorder;
        this.jobQueue = jobQueue;
        this.facts = facts;
        this.reviews = reviews;
        this.content = content;
        this.currentActor = currentActor;
        this.metrics = metrics;
    }

    void messageReceived(MessageStatusChanged e) {
        metrics.eventConsumed("message_received");
        if (e.conversationId() == null || e.debtorId() == null || e.creditorId() == null) {
            return;
        }
        jobQueue.enqueue(AssistantTurnJob.TYPE, new AssistantTurnJob.Payload(e.conversationId(), e.messageId()), null,
                "message:" + e.messageId());
        metrics.scheduled("enqueued");
    }

    void attribution(AgreementAttributionRecorded e) {
        metrics.eventConsumed("agreement_attribution");
        AssistantAgreementFact fact = load(e.agreementId(), e.creditorId(), e.portfolioId(), e.caseId(), e.formalizedAt());
        UUID assistantPolicy = e.participants().stream().filter(p -> "ASSISTANT".equals(p.actorType()))
                .map(AgreementAttributionRecorded.Participant::actorId).findFirst().orElse(null);
        fact.attribution(e.origin(), assistantPolicy, e.agreementAmount(), e.formalizedAt());
        facts.save(fact);
    }

    void agreementStatus(AgreementStatusChanged e) {
        metrics.eventConsumed("agreement_status");
        AssistantAgreementFact fact = load(e.agreementId(), e.creditorId(), e.portfolioId(), e.caseId(), e.occurredAt());
        fact.status(e.newStatus(), e.totalAmount(), e.occurredAt());
        facts.save(fact);
    }

    /**
     * Instrução de um Pix gerado pelo assistente mudou: pagamento registra a conversão; ativação, recusa,
     * expiração ou cancelamento de um Pix ainda não entregue agenda a entrega (que decide o que fazer).
     */
    void pixInstruction(PaymentInstructionStatusChanged e) {
        List<PixRequest> related = pixRequests.findByInstructionId(e.instructionId());
        if (related.isEmpty()) {
            return;
        }
        metrics.eventConsumed("pix_instruction");
        for (PixRequest r : related) {
            if ("PAID".equals(e.status()) && r.paid(e.paidAt() == null ? e.occurredAt() : e.paidAt())) {
                pixRequests.save(r);
                pixRecorder.paid(r);
            }
            if (r.getDeliveryStatus() != null && r.getDeliveryStatus().open()) {
                AssistantPixDelivery.enqueue(jobQueue, r.getId());
            }
        }
    }

    void anonymized(DebtorAnonymized e) {
        metrics.eventConsumed("debtor_anonymized");
        content.purgeByDebtor(e.debtorId());
        for (TurnReview review : reviews.findByDebtorId(e.debtorId())) {
            review.anonymize();
            reviews.save(review);
        }
    }

    private AssistantAgreementFact load(UUID agreementId, UUID creditorId, UUID portfolioId, UUID caseId, java.time.Instant at) {
        facts.insertIfAbsent(Ids.newId(), currentActor.tenantId(), agreementId, creditorId, portfolioId, caseId,
                "FORMALIZED", at);
        return facts.findByAgreementIdForUpdate(agreementId).orElseThrow();
    }
}
