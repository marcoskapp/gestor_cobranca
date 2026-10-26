package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.application.port.out.NegotiationPendingWorkQueries;
import br.com.gestorcobranca.negotiation.domain.model.proposal.Proposal;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkItem;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRequest;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkSource;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pendências do módulo:
 * <ul>
 *   <li>propostas acima da alçada que o usuário pode aprovar — a mesma fila da tela de aprovações
 *       ({@link ProposalApprovalQueue}: escopo, alçada do aprovador para o credor e exclusão das
 *       próprias), com prazo pela validade da proposta;</li>
 *   <li>propostas do próprio usuário aprovadas (aguardando o aceite do devedor) e aceitas (aguardando a
 *       formalização do acordo).</li>
 * </ul>
 */
@Component
class NegotiationPendingWork implements PendingWorkSource {

    static final String PROPOSAL_APPROVAL = "negotiation.proposal_approval";
    static final String AWAITING_ACCEPTANCE = "negotiation.proposal_acceptance";
    static final String AWAITING_FORMALIZATION = "negotiation.agreement_formalization";

    private final ProposalApprovalQueue approvalQueue;
    private final NegotiationPendingWorkQueries queries;
    private final AccessScope accessScope;

    NegotiationPendingWork(ProposalApprovalQueue approvalQueue, NegotiationPendingWorkQueries queries,
                           AccessScope accessScope) {
        this.approvalQueue = approvalQueue;
        this.queries = queries;
        this.accessScope = accessScope;
    }

    @Override
    public String id() {
        return "negotiation";
    }

    @Override
    public String label() {
        return "Negociação";
    }

    @Override
    public boolean appliesTo(Actor actor) {
        return actor.has(Permission.NEGOTIATION_APPROVE) || actor.has(Permission.AGREEMENT_FORMALIZE);
    }

    @Override
    public List<PendingWorkItem> pendingFor(PendingWorkRequest request) {
        Actor actor = request.actor();
        List<PendingWorkItem> items = new ArrayList<>();
        if (actor.has(Permission.NEGOTIATION_APPROVE)) {
            items.add(PendingWorkItem.of(PROPOSAL_APPROVAL, "Propostas acima da alçada para aprovar",
                            summarize(approvalQueue.eligibleFor(actor), request.today()))
                    .hint("Analise as condições e aprove ou recuse.")
                    .route("/negociacao/aprovacoes")
                    .singleRoute(id -> "/negociacao/propostas/" + id).build());
        }
        if (actor.has(Permission.AGREEMENT_FORMALIZE)) {
            Set<UUID> scope = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
            items.add(own(AWAITING_ACCEPTANCE, "Propostas aprovadas aguardando o aceite do devedor",
                    "Apresente as condições aprovadas e registre o aceite.", "APPROVED", scope, actor, request.today()));
            items.add(own(AWAITING_FORMALIZATION, "Aceites aguardando a formalização do acordo",
                    "Formalize o acordo antes do fim da validade.", "ACCEPTED", scope, actor, request.today()));
        }
        return items;
    }

    private PendingWorkItem own(String type, String label, String hint, String status, Set<UUID> scope, Actor actor,
                                LocalDate today) {
        return PendingWorkItem.of(type, label, queries.ownProposals(scope, actor.id(), status, today)).hint(hint)
                .route("/negociacao/propostas").param("situacao", status).param("minhas", "true")
                .singleRoute(id -> "/negociacao/propostas/" + id).build();
    }

    /** Contagem da fila já filtrada pela alçada (validade da proposta como prazo). */
    static PendingWorkCount summarize(List<Proposal> eligible, LocalDate today) {
        if (eligible.isEmpty()) {
            return PendingWorkCount.NONE;
        }
        long overdue = eligible.stream().filter(p -> p.getValidUntil().isBefore(today)).count();
        long dueToday = eligible.stream().filter(p -> p.getValidUntil().isEqual(today)).count();
        Instant oldest = eligible.stream().map(Proposal::getCreatedAt).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        LocalDate nextDue = eligible.stream().map(Proposal::getValidUntil).min(Comparator.naturalOrder()).orElse(null);
        return new PendingWorkCount(eligible.size(), overdue, dueToday, oldest, nextDue,
                eligible.size() == 1 ? eligible.getFirst().getId() : null);
    }
}
