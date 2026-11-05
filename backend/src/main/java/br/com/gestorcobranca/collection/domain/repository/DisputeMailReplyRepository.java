package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.DisputeMailReply;
import br.com.gestorcobranca.collection.domain.model.MailReplyStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** E-mails do credor recebidos para os pedidos (sem remoção: append-only no banco). */
public interface DisputeMailReplyRepository {

    DisputeMailReply save(DisputeMailReply reply);

    DisputeMailReply saveAndFlush(DisputeMailReply reply);

    Optional<DisputeMailReply> findById(UUID id);

    List<DisputeMailReply> findByDisputeIdOrderByReceivedAtAsc(UUID disputeId);

    boolean existsByProviderMessageId(String providerMessageId);

    /** E-mails do remetente (hash) desde o instante, para o limite por remetente. */
    long countBySenderHashAndReceivedAtAfter(String senderHash, Instant since);

    /** Fila da gestão: e-mails em quarentena, no escopo de credores (nulo = todos). */
    Page<DisputeMailReply> findByStatusAndCreditorIdIn(MailReplyStatus status, Collection<UUID> creditorIds,
                                                       Pageable pageable);

    Page<DisputeMailReply> findByStatus(MailReplyStatus status, Pageable pageable);
}
