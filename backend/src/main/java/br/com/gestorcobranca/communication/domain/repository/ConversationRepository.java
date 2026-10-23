package br.com.gestorcobranca.communication.domain.repository;

import br.com.gestorcobranca.communication.domain.model.Conversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository {

    Conversation save(Conversation conversation);

    Conversation saveAndFlush(Conversation conversation);

    Optional<Conversation> findById(UUID id);

    /** Com lock pessimista da linha: serializa mensagens e mudanças de dono simultâneas na conversa. */
    Optional<Conversation> findByIdForUpdate(UUID id);

    /** Conversa aberta do contato na conta para o credor ({@code creditorId} nulo = sem credor). */
    Optional<Conversation> findOpen(UUID channelAccountId, String contactHash, UUID creditorId);

    /** Conversas abertas do contato na conta, da última saída/mensagem mais recente para a mais antiga. */
    List<Conversation> findOpenByContact(UUID channelAccountId, String contactHash);

    /** Conversas (abertas ou não) do contato na conta com devedor identificado, da mais recente para a mais antiga. */
    List<Conversation> findIdentifiedByContact(UUID channelAccountId, String contactHash);

    List<Conversation> findOpenByCaseId(UUID caseId);

    /** Conversas abertas do devedor no credor ainda sem caso (vinculadas quando o caso abre). */
    List<Conversation> findOpenWithoutCase(UUID debtorId, UUID creditorId);

    Page<Conversation> search(ConversationSearchCriteria criteria, Pageable pageable);
}
