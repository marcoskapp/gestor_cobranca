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

    /** Revisão mais recente do extrato do credor para o ciclo que começa em {@code periodStart}. */
    Optional<UsageStatement> findLatest(UUID creditorId, LocalDate periodStart);

    /** Extrato (qualquer revisão) do ciclo mais recente do credor — o fim dele marca o início do próximo ciclo. */
    Optional<UsageStatement> findLastCycle(UUID creditorId);

    /**
     * @param allowedCreditorIds credores visíveis; {@code null} = sem restrição
     * @param closedFrom         fim do ciclo a partir desta data (inclusiva), opcional
     * @param closedTo           fim do ciclo até esta data (inclusiva), opcional
     */
    Page<UsageStatement> search(Collection<UUID> allowedCreditorIds, UUID creditorId, LocalDate closedFrom,
                                LocalDate closedTo, Pageable pageable);
}
