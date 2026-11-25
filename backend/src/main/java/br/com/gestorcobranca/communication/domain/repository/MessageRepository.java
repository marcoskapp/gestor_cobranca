package br.com.gestorcobranca.communication.domain.repository;

import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.Message;
import br.com.gestorcobranca.communication.domain.model.MessageStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository {

    Message save(Message message);

    Message saveAndFlush(Message message);

    Optional<Message> findById(UUID id);

    Optional<Message> findByIdempotencyKey(String idempotencyKey);

    Optional<Message> findByChannelAndProviderMessageId(ChannelType channel, String providerMessageId);

    Page<Message> findByConversationIdOrderByCreatedAtDesc(UUID conversationId, Pageable pageable);

    /**
     * Mensagens das conversas do devedor, das mais recentes para as mais antigas.
     *
     * @param allowedCreditorIds credores do escopo (nulo = todos)
     * @param creditorId         somente conversas deste credor (nulo = todas)
     */
    Page<Message> findByDebtor(UUID debtorId, UUID creditorId, Collection<UUID> allowedCreditorIds, Pageable pageable);

    /** Mensagens da conversa na situação (anonimização: as que ainda aguardam envio). */
    List<Message> findByConversationIdAndStatus(UUID conversationId, MessageStatus status);

    /**
     * Anonimização do titular: descarta conteúdo, assunto e variáveis das mensagens da conversa e troca o id do provedor
     * (o wamid do WhatsApp pode codificar o número; o Message-ID do e-mail, o remetente) pelo SHA-256 dele com o prefixo
     * {@code anon:} — mantém o índice único sem identificar o titular. Em lote, sem decifrar; incrementa a versão
     * (concorrência com webhooks). Atualização de situação que chegar depois não encontra a mensagem (decisão 1457).
     *
     * @return quantas mensagens foram alteradas
     */
    int purgeContentOfConversation(UUID conversationId, Instant now);

    /** Mensagens com conteúdo criadas antes do corte (retenção), mais antigas primeiro. */
    List<Message> findContentOlderThan(Instant cutoff, int limit);
}
