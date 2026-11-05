package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.TitleAmountDivergence;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TitleAmountDivergenceRepository {

    TitleAmountDivergence save(TitleAmountDivergence divergence);

    Optional<TitleAmountDivergence> findById(UUID id);

    Optional<TitleAmountDivergence> findOpenByTitleId(UUID titleId);

    List<TitleAmountDivergence> findByTitleIdOrderByDetectedAtDesc(UUID titleId);

    Page<TitleAmountDivergence> findOpen(Collection<UUID> creditorIds, boolean allCreditors, Pageable pageable);
}
