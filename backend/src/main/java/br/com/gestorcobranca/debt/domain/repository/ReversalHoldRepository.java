package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.ReversalHold;
import br.com.gestorcobranca.debt.domain.model.ReversalHoldState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Retenções da cobrança após estorno de pagamento recebido pelo credor (sob a RLS do tenant corrente). */
public interface ReversalHoldRepository {

    ReversalHold save(ReversalHold hold);

    ReversalHold saveAndFlush(ReversalHold hold);

    Optional<ReversalHold> findById(UUID id);

    /** {@code SELECT ... FOR UPDATE}: chame depois da trava do devedor (ordem devedor → título → retenção). */
    Optional<ReversalHold> findByIdForUpdate(UUID id);

    /** Credor e devedor da retenção, sem carregar a entidade (escopo antes de qualquer trava). */
    Optional<Parties> findPartiesById(UUID id);

    boolean existsByReversalId(UUID reversalId);

    /**
     * Há retenção pendente do credor para o devedor, como devedor principal ou como coobrigado ativo do contrato de
     * um título retido.
     */
    boolean existsPendingForContact(UUID creditorId, UUID debtorId);

    /** Títulos com retenção pendente do credor para o devedor principal. */
    Set<UUID> findPendingTitleIds(UUID creditorId, UUID debtorId);

    /** Títulos com retenção pendente entre os informados. */
    Set<UUID> findPendingTitleIdsIn(Collection<UUID> titleIds);

    /** Parcelas do acordo com retenção pendente ou liberada a partir de {@code releasedSince} (carência). */
    Set<UUID> findBreakExemptInstallmentIds(UUID agreementId, Instant releasedSince);

    /** Parcelas do acordo com retenção pendente. */
    Set<UUID> findPendingInstallmentIds(UUID agreementId);

    /**
     * Um título próprio por coobrigado ativo e credor (de preferência em cobrança) dos contratos dos títulos
     * informados: basta um evento para o collection reavaliar o caso dele (RC2-04).
     */
    Set<UUID> findCoobligorTitleIds(Collection<UUID> titleIds);

    /** A alocação estornada é de um pagamento recebido pelo credor que gerou retenção. */
    boolean existsByAllocationId(UUID allocationId);

    List<ReversalHold> findByDebtorId(UUID debtorId);

    List<ReversalHold> findByPaymentIdOrderByPlacedAtDesc(UUID paymentId);

    List<ReversalHold> findByDebtorIdAndState(UUID debtorId, ReversalHoldState state);

    /**
     * Aba "Estornos em conferência": escopo explícito na SQL ({@code creditors} nulo = todos os credores do tenant;
     * nunca vazio), credor e situação como condições dentro do escopo.
     */
    Page<ReversalHold> search(Collection<UUID> creditors, boolean allCreditors, UUID creditorId, UUID paymentId,
                              String state, Instant from, Instant to, Pageable pageable);

    /** Estornos de pagamento recebido pelo credor (retenções criadas) por credor desde {@code since}. */
    List<CreditorCount> countByCreditorSince(Collection<UUID> creditorIds, Instant since);

    interface Parties {
        UUID getCreditorId();

        UUID getDebtorId();
    }

    interface CreditorCount {
        UUID getCreditorId();

        long getTotal();
    }
}
