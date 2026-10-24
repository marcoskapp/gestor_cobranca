package br.com.gestorcobranca.portal.domain.repository;

import br.com.gestorcobranca.portal.domain.model.PortalSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PortalSessionRepository {

    PortalSession save(PortalSession session);

    Optional<PortalSession> findById(UUID id);

    /** Sessões ativas do devedor, da mais antiga para a mais nova. */
    List<PortalSession> findActiveByDebtorId(UUID debtorId);

    List<PortalSession> findByDebtorId(UUID debtorId);

    /** Sessões ativas vencidas (prazo absoluto ou inatividade) para o job de limpeza. */
    List<PortalSession> findActiveStale(Instant absoluteBefore, Instant idleBefore, int limit);

    /** Listagem da gestão: sessões de um portal (opcionalmente de um devedor), das mais recentes. */
    Page<PortalSession> search(UUID configurationId, UUID debtorId, Pageable pageable);

    /** Atualização pontual do último acesso (sem versão) para não conflitar com requisições paralelas. */
    int touch(UUID sessionId, Instant at);
}
