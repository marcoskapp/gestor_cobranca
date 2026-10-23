package br.com.gestorcobranca.communication.domain.repository;

import br.com.gestorcobranca.communication.domain.model.MessageTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface MessageTemplateRepository {

    MessageTemplate save(MessageTemplate template);

    MessageTemplate saveAndFlush(MessageTemplate template);

    Optional<MessageTemplate> findById(UUID id);

    /** Versão publicada do código para o credor ({@code creditorId} nulo = da assessoria). */
    Optional<MessageTemplate> findPublished(UUID creditorId, String code);

    /** Maior versão já criada do código (0 se nenhuma). */
    int maxVersion(UUID creditorId, String code);

    /**
     * @param creditorIds credores permitidos (nulo = sem restrição)
     * @param includeTenantWide incluir templates da assessoria (sem credor)
     */
    Page<MessageTemplate> search(Collection<UUID> creditorIds, boolean includeTenantWide, UUID creditorId, String code,
                                 String channel, String status, Pageable pageable);
}
