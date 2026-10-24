package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.application.port.out.TurnContentStore;
import br.com.gestorcobranca.assistant.domain.model.AgreementFact;
import br.com.gestorcobranca.assistant.domain.model.TurnReview;
import br.com.gestorcobranca.assistant.domain.repository.AgreementFactRepository;
import br.com.gestorcobranca.assistant.domain.repository.TurnReviewRepository;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.communication.MessageStatusChanged;
import br.com.gestorcobranca.shared.events.debt.DebtorAnonymized;
import br.com.gestorcobranca.shared.events.negotiation.AgreementAttributionRecorded;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Reações aos eventos de integração (executadas por {@link AssistantEventListeners}). */
@Component
class AssistantReactions {

    private final JobQueue jobQueue;
    private final AgreementFactRepository facts;
    private final TurnReviewRepository reviews;
    private final TurnContentStore content;
    private final CurrentActor currentActor;
    private final AssistantMetrics metrics;

    AssistantReactions(JobQueue jobQueue, AgreementFactRepository facts, TurnReviewRepository reviews,
                       TurnContentStore content, CurrentActor currentActor, AssistantMetrics metrics) {
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
        AgreementFact fact = load(e.agreementId(), e.creditorId(), e.portfolioId(), e.caseId(), e.formalizedAt());
        UUID assistantPolicy = e.participants().stream().filter(p -> "ASSISTANT".equals(p.actorType()))
                .map(AgreementAttributionRecorded.Participant::actorId).findFirst().orElse(null);
        fact.attribution(e.origin(), assistantPolicy, e.agreementAmount(), e.formalizedAt());
        facts.save(fact);
    }

    void agreementStatus(AgreementStatusChanged e) {
        metrics.eventConsumed("agreement_status");
        AgreementFact fact = load(e.agreementId(), e.creditorId(), e.portfolioId(), e.caseId(), e.occurredAt());
        fact.status(e.newStatus(), e.totalAmount(), e.occurredAt());
        facts.save(fact);
    }

    void anonymized(DebtorAnonymized e) {
        metrics.eventConsumed("debtor_anonymized");
        content.purgeByDebtor(e.debtorId());
        for (TurnReview review : reviews.findByDebtorId(e.debtorId())) {
            review.anonymize();
            reviews.save(review);
        }
    }

    private AgreementFact load(UUID agreementId, UUID creditorId, UUID portfolioId, UUID caseId, java.time.Instant at) {
        facts.insertIfAbsent(Ids.newId(), currentActor.tenantId(), agreementId, creditorId, portfolioId, caseId,
                "FORMALIZED", at);
        return facts.findByAgreementIdForUpdate(agreementId).orElseThrow();
    }
}
