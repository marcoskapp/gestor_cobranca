package br.com.gestorcobranca.compliance.domain.repository;

import br.com.gestorcobranca.compliance.domain.model.PrivacyNoticeAcknowledgment;

import java.util.List;
import java.util.UUID;

public interface PrivacyNoticeAcknowledgmentRepository {

    PrivacyNoticeAcknowledgment save(PrivacyNoticeAcknowledgment acknowledgment);

    boolean existsBySourceEventId(UUID sourceEventId);

    /** Reconhecimentos do titular, do mais recente ao mais antigo. */
    List<PrivacyNoticeAcknowledgment> findByDebtorIdOrderByAcknowledgedAtDesc(UUID debtorId);
}
