package br.com.gestorcobranca.publicapi.domain.repository.platform;

import br.com.gestorcobranca.publicapi.domain.model.platform.PlatformStatement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Extratos da plataforma da assessoria corrente (RLS). */
public interface PlatformStatementRepository {

    PlatformStatement save(PlatformStatement statement);

    Optional<PlatformStatement> findById(UUID id);

    /** Revisão mais recente do extrato do ciclo que começa em {@code periodStart}. */
    Optional<PlatformStatement> findLatest(LocalDate periodStart);

    /** Extrato (qualquer revisão) do ciclo mais recente — o fim dele marca o início do próximo ciclo. */
    Optional<PlatformStatement> findLastCycle();

    boolean existsAny();

    /** @param closedFrom/closedTo fim do ciclo entre as datas (inclusivas), opcionais */
    Page<PlatformStatement> search(LocalDate closedFrom, LocalDate closedTo, Pageable pageable);
}
