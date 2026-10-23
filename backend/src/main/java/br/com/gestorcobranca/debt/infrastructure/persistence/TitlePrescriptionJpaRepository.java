package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.TitlePrescription;
import br.com.gestorcobranca.debt.domain.repository.TitlePrescriptionRepository;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface TitlePrescriptionJpaRepository extends JpaRepository<TitlePrescription, UUID>, TitlePrescriptionRepository {

    @Override
    Optional<TitlePrescription> findByTitleId(UUID titleId);

    @Override
    default List<TitlePrescription> findAlertCandidates(LocalDate from, LocalDate to, int limit) {
        return findAlertCandidates(from, to, Limit.of(limit));
    }

    @Query("""
            select p from TitlePrescription p join DebtTitle t on t.id = p.titleId
            where t.status in (br.com.gestorcobranca.debt.domain.model.TitleStatus.OPEN,
                               br.com.gestorcobranca.debt.domain.model.TitleStatus.SUSPENDED)
            and p.estimatedDate between :from and :to
            and (p.lastAlertedFor is null or p.lastAlertedFor <> p.estimatedDate)
            order by p.estimatedDate, p.id
            """)
    List<TitlePrescription> findAlertCandidates(LocalDate from, LocalDate to, Limit limit);
}
