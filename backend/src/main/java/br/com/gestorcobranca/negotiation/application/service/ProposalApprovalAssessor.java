package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.ApprovalAssessmentView;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.AuthorityGapView;
import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityLimits;
import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityProfile;
import br.com.gestorcobranca.negotiation.domain.model.proposal.Proposal;
import br.com.gestorcobranca.negotiation.domain.repository.AuthorityRepository;
import br.com.gestorcobranca.negotiation.domain.service.ProposalTerms;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * "Quanto passa da alçada" para a tela de aprovação: compara a exigência gravada na proposta
 * ({@link ProposalTerms.AuthorityRequirement}, a mesma que roteia a fila) com o perfil de quem negociou (pelo código
 * gravado na criação, com os parâmetros atuais do perfil) e com o perfil de quem consulta. Só informa: a aprovação
 * revalida tudo pelo {@code NegotiationGuard}.
 */
@Component
class ProposalApprovalAssessor {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final AuthorityRepository authority;
    private final AuthorityResolver resolver;

    ProposalApprovalAssessor(AuthorityRepository authority, AuthorityResolver resolver) {
        this.authority = authority;
        this.resolver = resolver;
    }

    ApprovalAssessmentView assess(Actor actor, Proposal p) {
        Optional<AuthorityProfile> proposer = Optional.ofNullable(p.getAuthorityProfileCode())
                .flatMap(authority::findProfileByCode);
        Optional<AuthorityProfile> approver = approverOf(actor, p);
        ProposalTerms.AuthorityRequirement requirement = ProposalApprovalQueue.requirement(p);
        AuthorityLimits approverLimits = approver.map(AuthorityProfile::limits).orElse(null);
        List<AuthorityGapView> gaps = proposer.map(profile -> requirement.gaps(profile.limits(), approverLimits).stream()
                .map(g -> new AuthorityGapView(g.dimension().name(), g.requested(), g.proposerLimit(), g.approverLimit(),
                        g.approverCovers()))
                .toList()).orElse(List.of());
        boolean own = "USER".equals(p.getActorType()) && actor.isUser() && actor.id().equals(p.getActorId());
        return new ApprovalAssessmentView(p.getId(), p.getAuthorityProfileCode(),
                proposer.map(AuthorityProfile::getName).orElse(null),
                approver.map(AuthorityProfile::getCode).orElse(null), approver.map(AuthorityProfile::getName).orElse(null),
                approver.map(AuthorityProfile::isCanApprove).orElse(false),
                approverLimits != null && requirement.withinLimits(approverLimits), own, discountPercent(p), gaps);
    }

    /** Perfil de quem consulta para o credor da proposta; só para usuário com permissão de aprovar. */
    private Optional<AuthorityProfile> approverOf(Actor actor, Proposal p) {
        if (!actor.isUser() || !actor.has(Permission.NEGOTIATION_APPROVE)) {
            return Optional.empty();
        }
        try {
            return Optional.of(resolver.resolve(actor, p.getCreditorId()).profile());
        } catch (BusinessRuleException e) {
            // sem perfil aplicável ao credor: a tela mostra que a alçada de quem consulta não cobre
            return Optional.empty();
        }
    }

    private static BigDecimal discountPercent(Proposal p) {
        BigDecimal updated = p.getUpdatedAmount().amount();
        if (updated.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return p.getDiscountAmount().amount().multiply(HUNDRED).divide(updated, 2, RoundingMode.HALF_EVEN);
    }
}
