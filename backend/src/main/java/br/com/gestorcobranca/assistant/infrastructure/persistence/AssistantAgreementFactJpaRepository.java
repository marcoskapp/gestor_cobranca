package br.com.gestorcobranca.assistant.infrastructure.persistence;

import br.com.gestorcobranca.assistant.domain.model.AssistantAgreementFact;
import br.com.gestorcobranca.assistant.domain.repository.AssistantAgreementFactRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface AssistantAgreementFactJpaRepository extends JpaRepository<AssistantAgreementFact, UUID>, AssistantAgreementFactRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from AssistantAgreementFact f where f.agreementId = :agreementId")
    Optional<AssistantAgreementFact> findByAgreementIdForUpdate(UUID agreementId);

    @Override
    @Modifying
    @Query(value = """
            insert into assistant.agreement_fact (id, tenant_id, version, created_at, updated_at, agreement_id, creditor_id,
                                                  portfolio_id, case_id, status, status_at)
            values (:id, :tenantId, 0, now(), now(), :agreementId, :creditorId, :portfolioId, :caseId, :status, :at)
            on conflict (tenant_id, agreement_id) do nothing
            """, nativeQuery = true)
    void insertIfAbsent(UUID id, UUID tenantId, UUID agreementId, UUID creditorId, UUID portfolioId, UUID caseId,
                        String status, Instant at);
}
