package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.AuthorizationStatus;
import br.com.gestorcobranca.legal.domain.model.CreditorAuthorization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreditorAuthorizationRepository {

    CreditorAuthorization save(CreditorAuthorization authorization);

    Optional<CreditorAuthorization> findById(UUID id);

    List<CreditorAuthorization> findByMatterIdOrderByCreatedAtDesc(UUID matterId);

    Optional<CreditorAuthorization> findFirstByMatterIdAndStatus(UUID matterId, AuthorizationStatus status);

    /** Grava as alterações pendentes (substituição da concessão antes da nova, por causa do índice único parcial). */
    void flush();
}
