package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.AgreementRecord;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgreementRecordRepository {

    AgreementRecord save(AgreementRecord record);

    Optional<AgreementRecord> findByAgreementId(UUID agreementId);

    long countByCollectionCaseIdAndBrokenTrue(UUID collectionCaseId);

    List<AgreementRecord> findByMatterIdOrderByLastEventAtDesc(UUID matterId);
}
