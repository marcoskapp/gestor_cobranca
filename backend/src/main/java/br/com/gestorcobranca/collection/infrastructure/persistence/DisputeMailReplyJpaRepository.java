package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.DisputeMailReply;
import br.com.gestorcobranca.collection.domain.model.MailReplyStatus;
import br.com.gestorcobranca.collection.domain.repository.DisputeMailReplyRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Sem remoção: o e-mail recebido fica (gatilho append-only no banco). */
interface DisputeMailReplyJpaRepository extends Repository<DisputeMailReply, UUID>, DisputeMailReplyRepository {

    @Override
    DisputeMailReply save(DisputeMailReply reply);

    @Override
    DisputeMailReply saveAndFlush(DisputeMailReply reply);

    @Override
    Optional<DisputeMailReply> findById(UUID id);

    @Override
    List<DisputeMailReply> findByDisputeIdOrderByReceivedAtAsc(UUID disputeId);

    @Override
    boolean existsByProviderMessageId(String providerMessageId);

    @Override
    long countBySenderHashAndReceivedAtAfter(String senderHash, Instant since);

    @Override
    Page<DisputeMailReply> findByStatusAndCreditorIdIn(MailReplyStatus status, Collection<UUID> creditorIds,
                                                       Pageable pageable);

    @Override
    Page<DisputeMailReply> findByStatus(MailReplyStatus status, Pageable pageable);
}
