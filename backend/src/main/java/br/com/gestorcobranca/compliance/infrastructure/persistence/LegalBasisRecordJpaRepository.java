package br.com.gestorcobranca.compliance.infrastructure.persistence;

import br.com.gestorcobranca.compliance.domain.model.LegalBasisRecord;
import br.com.gestorcobranca.compliance.domain.repository.LegalBasisRecordRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface LegalBasisRecordJpaRepository extends JpaRepository<LegalBasisRecord, UUID>, LegalBasisRecordRepository {

    @Override
    List<LegalBasisRecord> findByCreditorIdOrderByValidFromDesc(UUID creditorId);

    @Override
    List<LegalBasisRecord> findByCreditorIdIn(Collection<UUID> creditorIds);

    /** Espelha a restrição de exclusão do banco para recusar com mensagem de negócio. */
    @Override
    @Query("""
            select case when count(r) > 0 then true else false end from LegalBasisRecord r
             where r.creditorId = :creditorId
               and ((:portfolioId is null and r.portfolioId is null) or r.portfolioId = :portfolioId)
               and (:until is null or r.validFrom <= :until)
               and (r.validUntil is null or r.validUntil >= :from)
            """)
    boolean existsOverlapping(UUID creditorId, UUID portfolioId, LocalDate from, LocalDate until);

    @Override
    @Query("""
            select r from LegalBasisRecord r
             where r.creditorId = :creditorId
               and ((:portfolioId is null and r.portfolioId is null) or r.portfolioId = :portfolioId)
               and r.validFrom <= :date and (r.validUntil is null or r.validUntil >= :date)
            """)
    Optional<LegalBasisRecord> findEffective(UUID creditorId, UUID portfolioId, LocalDate date);
}
