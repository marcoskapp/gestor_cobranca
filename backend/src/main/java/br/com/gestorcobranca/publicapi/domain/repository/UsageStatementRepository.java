package br.com.gestorcobranca.publicapi.domain.repository;

import br.com.gestorcobranca.publicapi.domain.model.UsageStatement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface UsageStatementRepository {

    UsageStatement save(UsageStatement statement);

    Optional<UsageStatement> findById(UUID id);

    /** Revisão mais recente do extrato do credor no mês. */
    Optional<UsageStatement> findLatest(UUID creditorId, LocalDate periodStart);

    /** @param allowedCreditorIds credores visíveis; {@code null} = sem restrição */
    Page<UsageStatement> search(Collection<UUID> allowedCreditorIds, UUID creditorId, LocalDate periodStart,
                                Pageable pageable);
}
