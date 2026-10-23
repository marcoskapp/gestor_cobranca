package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.incentive.application.port.out.AgreementLock;
import br.com.gestorcobranca.incentive.application.port.out.FactStore;
import br.com.gestorcobranca.incentive.application.port.out.FactStore.CaseSnapshot;
import br.com.gestorcobranca.incentive.application.port.out.FactStore.FactKey;
import br.com.gestorcobranca.incentive.application.port.out.FactStore.Measures;
import br.com.gestorcobranca.incentive.domain.model.DelayBucket;
import br.com.gestorcobranca.incentive.domain.model.attribution.AgreementAttribution;
import br.com.gestorcobranca.incentive.domain.repository.AgreementAttributionRepository;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.events.collection.CaseAssignmentChanged;
import br.com.gestorcobranca.shared.events.collection.CaseBalanceChanged;
import br.com.gestorcobranca.shared.events.collection.CaseStatusChanged;
import br.com.gestorcobranca.shared.events.collection.InteractionRecorded;
import br.com.gestorcobranca.shared.events.communication.MessageStatusChanged;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.events.negotiation.InstallmentStatusChanged;
import br.com.gestorcobranca.shared.events.telephony.CallCompleted;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Materializa os fatos operacionais dos eventos de integração (§2, §9). Executado pelos
 * consumidores dentro da transação de consumo idempotente.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class FactIngestionService {

    static final String OUTBOUND = "OUTBOUND";
    static final String INBOUND = "INBOUND";
    static final String PAID = "PAID";

    private final FactStore facts;
    private final AgreementAttributionRepository attributions;
    private final AgreementLock agreementLock;
    private final IncentiveMetrics metrics;

    FactIngestionService(FactStore facts, AgreementAttributionRepository attributions, AgreementLock agreementLock,
                         IncentiveMetrics metrics) {
        this.facts = facts;
        this.attributions = attributions;
        this.agreementLock = agreementLock;
        this.metrics = metrics;
    }

    /** Tentativa = interação de saída; CPC = contato efetivo; caso trabalhado = ≥ 1 interação do negociador. */
    void interaction(InteractionRecorded e) {
        metrics.factConsumed("interaction");
        if (e.operatorId() == null) {
            return;
        }
        LocalDate day = day(e.occurredAt());
        facts.increment(new FactKey(day, e.operatorId(), e.creditorId(), e.portfolioId(), DelayBucket.UNKNOWN, e.channel()),
                Measures.interaction(OUTBOUND.equals(e.direction()), e.effectiveContact()));
        if (e.caseId() != null) {
            facts.recordWorkedCase(day, e.operatorId(), e.caseId(), e.creditorId(), e.portfolioId(), e.channel());
            if (e.effectiveContact()) {
                facts.recordEffectiveContact(e.interactionId(), e.caseId(), e.operatorId(), e.occurredAt());
            }
        }
    }

    /** Chamada atendida: entra no TMA e no tempo em atendimento (ocupação). Tentativas vêm das interações. */
    void call(CallCompleted e) {
        metrics.factConsumed("call");
        if (e.operatorId() == null || !CallCompleted.ANSWERED.equals(e.result()) || e.durationSeconds() <= 0) {
            return;
        }
        facts.increment(new FactKey(day(e.occurredAt()), e.operatorId(), e.creditorId(), e.portfolioId(),
                DelayBucket.UNKNOWN, "PHONE"), Measures.call(e.durationSeconds()));
    }

    /**
     * Tempo de resposta: abre a espera na primeira mensagem recebida do devedor; qualquer resposta
     * enviada encerra a espera, mas só a de um negociador humano gera amostra.
     */
    void message(MessageStatusChanged e) {
        metrics.factConsumed("message");
        if (e.conversationId() == null) {
            return;
        }
        if (INBOUND.equals(e.direction()) && "RECEIVED".equals(e.status())) {
            facts.openConversationWait(e.conversationId(), e.occurredAt());
        } else if (OUTBOUND.equals(e.direction()) && "SENT".equals(e.status())) {
            Optional<Instant> waitingSince = facts.closeConversationWait(e.conversationId());
            if (waitingSince.isPresent() && "USER".equals(e.senderActorType()) && e.senderActorId() != null) {
                facts.recordResponseSample(e.messageId(), day(e.occurredAt()), e.senderActorId(), e.creditorId(),
                        e.portfolioId(), e.channel(), Duration.between(waitingSince.get(), e.occurredAt()).toSeconds());
            }
        }
    }

    void caseStatus(CaseStatusChanged e) {
        metrics.factConsumed("case_status");
        facts.recordCaseSnapshot(new CaseSnapshot(e.eventId(), e.caseId(), e.occurredAt(), e.creditorId(), e.portfolioId(),
                e.assigneeId(), e.teamId(), e.newStatus(), e.eligible(), e.eligibleBalance()));
    }

    void caseAssignment(CaseAssignmentChanged e) {
        metrics.factConsumed("case_assignment");
        facts.recordCaseSnapshot(new CaseSnapshot(e.eventId(), e.caseId(), e.occurredAt(), e.creditorId(), e.portfolioId(),
                e.assigneeId(), e.teamId(), null, null, e.eligibleBalance()));
    }

    /** Saldo elegível mudou sem mudança de status nem de responsável (pagamento, carga, baixa). */
    void caseBalance(CaseBalanceChanged e) {
        metrics.factConsumed("case_balance");
        facts.recordCaseSnapshot(new CaseSnapshot(e.eventId(), e.caseId(), e.occurredAt(), e.creditorId(), e.portfolioId(),
                e.assigneeId(), e.teamId(), null, e.eligible(), e.eligibleBalance()));
    }

    void agreementStatus(AgreementStatusChanged e) {
        metrics.factConsumed("agreement_status");
        facts.agreementStatus(e.agreementId(), e.newStatus(), e.occurredAt(), e.totalAmount(), e.replacedAgreementId());
    }

    /**
     * 1ª parcela paga (indicador e valor fixo por acordo): marca no ciclo do acordo e conta para o
     * closer; se voltar de paga (estorno), compensa.
     */
    void installmentStatus(InstallmentStatusChanged e) {
        metrics.factConsumed("installment_status");
        if (e.number() != 1 || e.agreementId() == null) {
            return;
        }
        agreementLock.lock(e.agreementId());
        Optional<AgreementAttribution> attribution = attributions.findByAgreementId(e.agreementId());
        if (PAID.equals(e.newStatus())) {
            if (facts.firstInstallmentPaid(e.agreementId(), e.occurredAt())) {
                attribution.ifPresent(a -> countFirstInstallment(a, e.occurredAt(), 1));
            }
        } else if (PAID.equals(e.previousStatus())) {
            if (facts.firstInstallmentPaidAt(e.agreementId()).isPresent()) {
                facts.firstInstallmentReverted(e.agreementId());
                attribution.ifPresent(a -> countFirstInstallment(a, e.occurredAt(), -1));
            }
        }
    }

    /** Conta (ou compensa) a 1ª parcela paga para o closer humano do acordo. */
    void countFirstInstallment(AgreementAttribution a, Instant at, int delta) {
        a.humanCloser().ifPresent(closer -> facts.increment(
                new FactKey(day(at), closer, a.getCreditorId(), a.getPortfolioId(), a.getDelayBucket(), a.getChannel()),
                new Measures(0, 0, 0, 0, 0, null, null, null, null, null, null, 0, 0, 0, delta, null, null)));
    }

    static LocalDate day(Instant instant) {
        return instant.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
    }
}
