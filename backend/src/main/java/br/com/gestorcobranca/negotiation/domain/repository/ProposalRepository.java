package br.com.gestorcobranca.negotiation.domain.repository;

import br.com.gestorcobranca.negotiation.domain.model.proposal.Proposal;
import br.com.gestorcobranca.negotiation.domain.model.proposal.ProposalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Propostas de acordo. */
public interface ProposalRepository {

    Proposal save(Proposal proposal);

    Optional<Proposal> findById(UUID id);

    /**
     * @param creditorIds credores permitidos (nulo = todos)
     * @param actorId     somente propostas deste ator (nulo = todas)
     */
    Page<Proposal> search(Collection<UUID> creditorIds, UUID creditorId, UUID debtorId, ProposalStatus status,
                          String actorType, UUID actorId, Pageable page);

    /** Propostas aguardando aprovação nos credores permitidos (nulo = todos), das mais antigas para as mais novas. */
    List<Proposal> findPendingApproval(Collection<UUID> creditorIds, int limit);

    /** Propostas em aberto com validade anterior a {@code today} (lote para expiração). */
    List<Proposal> findOpenExpiredBefore(LocalDate today, int limit);

    List<Proposal> findByDebtorId(UUID debtorId);
}
