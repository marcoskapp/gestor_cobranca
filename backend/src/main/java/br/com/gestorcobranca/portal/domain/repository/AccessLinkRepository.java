package br.com.gestorcobranca.portal.domain.repository;

import br.com.gestorcobranca.portal.domain.model.AccessLink;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccessLinkRepository {

    AccessLink save(AccessLink link);

    Optional<AccessLink> findById(UUID id);

    /** Com lock: o resgate concorrente do mesmo link é serializado (uso único). */
    Optional<AccessLink> findByTokenHashForUpdate(String tokenHash);

    /** Links ainda não usados nem revogados do devedor no portal. */
    List<AccessLink> findOpen(UUID configurationId, UUID debtorId);
}
