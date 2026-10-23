package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.BreakRuleView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.InstallmentView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.ParticipantView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.PaymentView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.ActorRef;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.ComponentsView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.PolicyRef;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.AcceptanceView;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.DecisionView;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.ProposalView;
import br.com.gestorcobranca.negotiation.domain.model.agreement.AcceptanceEvidence;
import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement;
import br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementInstallment;
import br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementPayment;
import br.com.gestorcobranca.negotiation.domain.model.proposal.Proposal;
import br.com.gestorcobranca.negotiation.domain.service.AttributionRules;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.util.List;

/** Conversão de propostas e acordos para os records dos casos de uso. */
@Component
class NegotiationViews {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final JsonCodec json;

    NegotiationViews(JsonCodec json) {
        this.json = json;
    }

    /** @param minimized visão do assistente: sem observações livres de decisão */
    ProposalView proposal(Proposal p, boolean minimized) {
        ProposalCalculation calc = json.fromJson(p.getCalculation(), ProposalCalculation.class);
        DecisionView decision = p.getDecidedBy() == null ? null : new DecisionView(p.getDecidedBy(),
                p.getDecidedByProfile(), p.getDecidedAt(), minimized ? null : p.getDecisionNote());
        return new ProposalView(p.getId(), p.getStatus().name(), p.getCreditorId(), p.getDebtorId(), p.getPortfolioId(),
                p.getCaseId(), p.getSimulationId(), new PolicyRef(p.getPolicyId(), p.getPolicyVersion(), p.getOverrideCode()),
                p.getReferenceDate(), new ActorRef(p.getActorType(), p.getActorId(), null, p.getAuthorityProfileCode()),
                p.getLevel(), p.getJustificationReason() == null ? null : p.getJustificationReason().name(), p.isCopilot(),
                p.getChannel(), p.getTitleIds(), discountRates(p), p.getInstallments(), amount(p.getRequestedDownPayment()),
                p.getFirstDueDate(), p.getUpdatedAmount().amount(), p.getDiscountAmount().amount(),
                p.getNegotiatedAmount().amount(), p.getDownPayment().amount(), p.getFinancingInterest().amount(),
                p.getTotalAmount().amount(), p.getEnvelopeUsage().value(), p.getValidUntil(),
                json.fromJson(p.getAuthorityViolations(), STRINGS), p.getReplacedAgreementId(), p.getRenegotiationNumber(),
                p.getHandoffProposalId(), decision, acceptance(p.getAcceptance()), p.getAgreementId(), p.getFormalizedAt(),
                p.getCreatedAt(), calc.offer(), p.getVersion() == null ? 0 : p.getVersion());
    }

    ComponentsView discountRates(Proposal p) {
        return json.fromJson(p.getDiscountRates(), ComponentsView.class);
    }

    static AcceptanceView acceptance(AcceptanceEvidence a) {
        return a == null || a.getAcceptedAt() == null ? null : new AcceptanceView(a.getChannel(), a.getAcceptedAt(),
                a.getActorType(), a.getActorId(), a.getReference(), a.getTermSha256(), a.getTermSize());
    }

    AgreementView agreement(Agreement a) {
        List<InstallmentView> installments = a.getInstallments().stream().map(NegotiationViews::installment).toList();
        AttributionRules.Attribution attribution = AttributionRules.resolve(new AttributionRules.Facts(a.getCloserType(),
                a.getCloserId(), a.getOriginatorType(), a.getOriginatorId(), false, a.getPreNegotiatorId(), false,
                a.getApproverId()));
        List<ParticipantView> participants = attribution.participants().stream()
                .map(p -> new ParticipantView(p.actorType(), p.actorId(), p.role())).toList();
        return new AgreementView(a.getId(), a.getStatus().name(), a.getStatusReason(), a.getProposalId(), a.getCreditorId(),
                a.getDebtorId(), a.getPortfolioId(), a.getCaseId(), a.getPolicyId(), a.getPolicyVersion(),
                a.getConcessionLevel(), a.getEnvelopeUsage().value(), a.getChannel(), a.getOrigin().name(), participants,
                a.getUpdatedAmount().amount(), a.getDiscountAmount().amount(), a.getNegotiatedAmount().amount(),
                a.getFinancingInterest().amount(), a.getTotalAmount().amount(), a.getDownPayment().amount(),
                a.getPaidAmount().amount(), a.remaining().amount(), a.titleIds(), installments,
                new BreakRuleView(a.getBreakOverdueInstallments(), a.getBreakToleranceDays()), a.getFormalizedAt(),
                a.getInPaymentAt(), a.getSettledAt(), a.getEndedAt(), a.getReplacedAgreementId(),
                a.getReplacedByAgreementId(), a.getRenegotiationNumber(), a.getIncrementalAmount().amount(),
                acceptance(a.getAcceptance()), a.getTermDeletedAt() == null && a.getAcceptance() != null,
                a.getVersion() == null ? 0 : a.getVersion());
    }

    static InstallmentView installment(AgreementInstallment i) {
        return new InstallmentView(i.getId(), i.getNumber(), i.getDueDate(), i.getAmount().amount(),
                i.getPaidAmount().amount(), i.getStatus().name(), i.getPaidAt());
    }

    static PaymentView payment(AgreementPayment p) {
        return new PaymentView(p.getPaymentId(), p.getAmount().amount(), p.getAppliedAmount().amount(),
                p.getUnappliedAmount().amount(), p.getPaidAt(), p.getStatus().name(), p.getLedgerStatus().name(),
                p.getReversedAt());
    }

    private static BigDecimal amount(Money m) {
        return m == null ? null : m.amount();
    }
}
