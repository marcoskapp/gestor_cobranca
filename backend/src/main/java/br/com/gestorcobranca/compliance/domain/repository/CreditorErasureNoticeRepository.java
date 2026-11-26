package br.com.gestorcobranca.compliance.domain.repository;

import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice;
import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreditorErasureNoticeRepository {

    CreditorErasureNotice save(CreditorErasureNotice notice);

    Optional<CreditorErasureNotice> findById(UUID id);

    /** Trava o aviso para registrar o aviso ao credor (dois registros simultâneos: o segundo vê o primeiro). */
    Optional<CreditorErasureNotice> findByIdForUpdate(UUID id);

    /** Id do aviso do par devedor × credor (único, {@code uk_creditor_erasure_notice}). */
    Optional<UUID> findIdByDebtorIdAndCreditorId(UUID debtorId, UUID creditorId);

    /** Trava os avisos em ordem de id (sem impasse entre dois registros em lote). */
    List<CreditorErasureNotice> findAllByIdInForUpdate(Collection<UUID> ids);

    /** Pendentes do credor, da eliminação mais antiga à mais nova (mesmo critério dos cartões por credor). */
    List<CreditorErasureNotice> findPendingByCreditor(UUID creditorId, int limit);

    long countByCreditorIdAndStatus(UUID creditorId, Status status);

    /** Por credor com pendentes, da anonimização mais antiga à mais nova. */
    List<PendingNoticeCount> pendingByCreditor();

    /** Filtros nulos não restringem. */
    Page<CreditorErasureNotice> search(Status status, UUID creditorId, UUID originId, Pageable pageable);
}
