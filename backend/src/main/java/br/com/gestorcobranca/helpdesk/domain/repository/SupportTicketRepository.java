package br.com.gestorcobranca.helpdesk.domain.repository;

import br.com.gestorcobranca.helpdesk.domain.model.SupportTicket;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupportTicketRepository {

    SupportTicket save(SupportTicket ticket);

    Optional<SupportTicket> findById(UUID id);

    /** Com bloqueio de linha (confirmações simultâneas do mesmo chamado são serializadas). */
    Optional<SupportTicket> findByIdForUpdate(UUID id);

    /** Chamado aberto pela mesma pessoa com o mesmo texto desde o instante (deduplicação). */
    Optional<SupportTicket> findOpenedDuplicate(UUID userId, String contentSha256, Instant since, UUID excludeId);

    /** Rascunhos vencidos (expiração). */
    List<SupportTicket> findPendingExpiredBefore(Instant now, int limit);
}
