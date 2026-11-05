package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.DisputeDebtorNotice;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeDebtorNoticeRepository {

    DisputeDebtorNotice save(DisputeDebtorNotice notice);

    List<DisputeDebtorNotice> findByDisputeIdOrderByCreatedAtAsc(UUID disputeId);

    Optional<DisputeDebtorNotice> findByMessageId(UUID messageId);
}
