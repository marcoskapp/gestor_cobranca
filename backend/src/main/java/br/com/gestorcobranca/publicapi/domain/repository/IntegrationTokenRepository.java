package br.com.gestorcobranca.publicapi.domain.repository;

import br.com.gestorcobranca.publicapi.domain.model.IntegrationToken;
import br.com.gestorcobranca.publicapi.domain.model.TokenStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IntegrationTokenRepository {

    IntegrationToken save(IntegrationToken token);

    /** Grava e sincroniza com o banco (a rota global do prefixo referencia o token por chave estrangeira). */
    IntegrationToken saveAndFlush(IntegrationToken token);

    Optional<IntegrationToken> findById(UUID id);

    Optional<IntegrationToken> findByPrefix(String prefix);

    List<IntegrationToken> findAllByIdIn(Collection<UUID> ids);

    /**
     * @param allowedCreditorIds credores visíveis; {@code null} = sem restrição
     * @param status             filtro opcional
     */
    Page<IntegrationToken> search(Collection<UUID> allowedCreditorIds, UUID creditorId, TokenStatus status,
                                  Pageable pageable);
}
