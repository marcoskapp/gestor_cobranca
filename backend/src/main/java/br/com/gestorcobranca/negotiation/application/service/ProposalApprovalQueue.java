package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityLimits;
import br.com.gestorcobranca.negotiation.domain.model.proposal.Proposal;
import br.com.gestorcobranca.negotiation.domain.repository.ProposalRepository;
import br.com.gestorcobranca.negotiation.domain.service.ProposalTerms;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fila de aprovação do ator (F16): propostas {@code PENDING_APPROVAL} nos credores do escopo cujas
 * condições cabem na alçada do aprovador (perfil com limites resolvidos para o credor), excluídas as
 * próprias (segregação), das mais antigas para as mais novas. Usada pela tela de aprovações e por
 * "Minhas pendências" — o mesmo critério nos dois lugares.
 */
@Component
class ProposalApprovalQueue {

    /** Propostas pendentes examinadas por consulta (filtradas pela alçada do aprovador). */
    static final int SCAN_LIMIT = 1000;

    private final ProposalRepository proposals;
    private final AuthorityResolver authorityResolver;
    private final AccessScope accessScope;

    ProposalApprovalQueue(ProposalRepository proposals, AuthorityResolver authorityResolver, AccessScope accessScope) {
        this.proposals = proposals;
        this.authorityResolver = authorityResolver;
        this.accessScope = accessScope;
    }

    List<Proposal> eligibleFor(Actor actor) {
        accessScope.requirePermission(Permission.NEGOTIATION_APPROVE);
        Guard.require(actor.isUser(), "proposal.approver_must_be_user", "Somente usuários aprovam propostas");
        Set<UUID> allowed = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
        Map<UUID, Optional<AuthorityLimits>> limitsByCreditor = new HashMap<>();
        List<Proposal> eligible = new ArrayList<>();
        for (Proposal p : proposals.findPendingApproval(allowed, SCAN_LIMIT)) {
            if ("USER".equals(p.getActorType()) && actor.id().equals(p.getActorId())) {
                continue;
            }
            Optional<AuthorityLimits> limits = limitsByCreditor.computeIfAbsent(p.getCreditorId(), c -> limitsOf(actor, c));
            if (limits.isPresent() && requirement(p).satisfiedBy(limits.get())) {
                eligible.add(p);
            }
        }
        return eligible;
    }

    private Optional<AuthorityLimits> limitsOf(Actor actor, UUID creditorId) {
        try {
            return Optional.of(authorityResolver.resolve(actor, creditorId).profile().limits());
        } catch (BusinessRuleException e) {
            return Optional.empty();
        }
    }

    private static ProposalTerms.AuthorityRequirement requirement(Proposal p) {
        return new ProposalTerms.AuthorityRequirement(p.getLevel(), p.getRequiredDiscountShare().value(),
                p.getInstallments(), p.getTotalAmount(), p.getDownPaymentShare().value(), p.getRenegotiationNumber());
    }
}
