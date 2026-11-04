package br.com.gestorcobranca.publicapi.domain.repository;

import br.com.gestorcobranca.publicapi.domain.model.IntegrationToken;
import br.com.gestorcobranca.publicapi.domain.model.TokenStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
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

    /** Credores que têm ou já tiveram token (candidatos ao fechamento dos ciclos de consumo). */
    List<UUID> findDistinctCreditorIds();

    /**
     * @param allowedCreditorIds credores visíveis; {@code null} = sem restrição
     * @param statuses           situações gravadas aceitas; {@code null} = todas
     */
    Page<IntegrationToken> search(Collection<UUID> allowedCreditorIds, UUID creditorId, Collection<TokenStatus> statuses,
                                  Pageable pageable);

    /** Todos os tokens da assessoria corrente (controle pela plataforma). */
    List<IntegrationToken> findAll();

    /** Pausas com retomada automática vencida em {@code now}, da assessoria corrente. */
    List<IntegrationToken> findResumeDue(Instant now);
}
