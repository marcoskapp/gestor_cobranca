package br.com.gestorcobranca.assistant.domain.repository;

import br.com.gestorcobranca.assistant.domain.model.AgreementFact;

import java.util.Optional;
import java.util.UUID;

public interface AgreementFactRepository {

    AgreementFact save(AgreementFact fact);

    /**
     * Cria o fato vazio do acordo se ainda não existir (idempotente sob concorrência, sem erro de
     * unicidade), para em seguida carregá-lo com lock.
     */
    void insertIfAbsent(UUID id, UUID tenantId, UUID agreementId, UUID creditorId, UUID portfolioId, UUID caseId,
                        String status, java.time.Instant at);

    /** Com lock: atribuição e mudanças de situação do mesmo acordo podem chegar juntas. */
    Optional<AgreementFact> findByAgreementIdForUpdate(UUID agreementId);
}
