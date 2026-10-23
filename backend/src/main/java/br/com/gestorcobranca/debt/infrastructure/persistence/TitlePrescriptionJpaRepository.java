package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.PrescriptionClaim;
import br.com.gestorcobranca.debt.domain.model.TitlePrescription;
import br.com.gestorcobranca.debt.domain.repository.TitlePrescriptionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface TitlePrescriptionJpaRepository extends JpaRepository<TitlePrescription, UUID>, TitlePrescriptionRepository {

    @Override
    List<TitlePrescription> findByTitleId(UUID titleId);

    @Override
    Optional<TitlePrescription> findByTitleIdAndClaim(UUID titleId, PrescriptionClaim claim);

    /** A janela é a da regra aplicada ({@code alert_days}); índice parcial ix_title_prescription_estimated. */
    @Override
    @Query(nativeQuery = true, value = """
            select p.* from debt.title_prescription p
            join debt.prescription_rule r on r.id = p.rule_id
            join debt.debt_title t on t.id = p.title_id
            where t.status in ('OPEN', 'SUSPENDED')
              and p.estimated_date >= cast(:today as date)
              and p.estimated_date <= cast(:today as date) + r.alert_days
              and p.last_alerted_for is distinct from p.estimated_date
            order by p.estimated_date, p.id
            limit :limit
            """)
    List<TitlePrescription> findAlertCandidates(LocalDate today, int limit);

    @Override
    @Query(nativeQuery = true, value = """
            select distinct c.id from debt.debt_title t
            join debt.origin_contract c on c.id = t.origin_contract_id
            join debt.prescription_rule r on r.instrument_type = c.instrument_type
                 and (r.claim = 'COLLECTION' or c.executive_title)
            where t.status in ('OPEN', 'SUSPENDED')
              and not exists (select 1 from debt.title_prescription p where p.title_id = t.id and p.claim = r.claim)
            order by c.id
            limit :limit
            """)
    List<UUID> findContractIdsMissingPrescription(int limit);
}
