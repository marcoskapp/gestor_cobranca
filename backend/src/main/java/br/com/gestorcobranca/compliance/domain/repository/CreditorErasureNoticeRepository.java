package br.com.gestorcobranca.compliance.domain.repository;

import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice;
import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

public interface CreditorErasureNoticeRepository {

    CreditorErasureNotice save(CreditorErasureNotice notice);

    Optional<CreditorErasureNotice> findById(UUID id);

    /** Trava o aviso para registrar o aviso ao credor (dois registros simultâneos: o segundo vê o primeiro). */
    Optional<CreditorErasureNotice> findByIdForUpdate(UUID id);

    /** Filtros nulos não restringem. */
    Page<CreditorErasureNotice> search(Status status, UUID creditorId, UUID originId, Pageable pageable);
}
