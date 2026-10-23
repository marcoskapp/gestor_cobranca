package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.CadenceExhaustion;

import java.util.Optional;
import java.util.UUID;

/** Réguas extrajudiciais esgotadas por caso de cobrança (append-only). */
public interface CadenceExhaustionRepository {

    CadenceExhaustion save(CadenceExhaustion exhaustion);

    boolean existsByEnrollmentId(UUID enrollmentId);

    /** Régua esgotada mais recente do caso de cobrança. */
    Optional<CadenceExhaustion> findFirstByCollectionCaseIdOrderByExhaustedAtDesc(UUID collectionCaseId);
}
