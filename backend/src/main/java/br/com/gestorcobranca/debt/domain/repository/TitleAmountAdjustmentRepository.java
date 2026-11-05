package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.TitleAmountAdjustment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Pedidos de correção de valor: inclusão e o desfecho (gravado uma vez); nunca removidos. */
public interface TitleAmountAdjustmentRepository {

    TitleAmountAdjustment save(TitleAmountAdjustment adjustment);

    Optional<TitleAmountAdjustment> findById(UUID id);

    List<TitleAmountAdjustment> findByTitleIdOrderByRequestedAtDesc(UUID titleId);

    boolean existsByTitleIdAndStatus(UUID titleId, br.com.gestorcobranca.debt.domain.model.AmountAdjustmentStatus status);

    /** Fila de aprovação nos credores do escopo (nulo = todos), sem os pedidos de {@code excludeRequester}. */
    Page<TitleAmountAdjustment> findPending(Collection<UUID> creditorIds, boolean allCreditors, UUID excludeRequester,
                                            Pageable pageable);
}
