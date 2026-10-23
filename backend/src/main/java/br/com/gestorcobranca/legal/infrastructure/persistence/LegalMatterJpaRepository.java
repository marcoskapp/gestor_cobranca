package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.model.LegalStage;
import br.com.gestorcobranca.legal.domain.repository.LegalMatterRepository;
import br.com.gestorcobranca.legal.domain.repository.MatterSearchCriteria;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface LegalMatterJpaRepository extends JpaRepository<LegalMatter, UUID>, JpaSpecificationExecutor<LegalMatter>,
        LegalMatterRepository {

    EnumSet<LegalStage> TERMINAL = EnumSet.of(LegalStage.CLOSED, LegalStage.RETURNED_TO_CREDITOR);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from LegalMatter m where m.id = :id")
    Optional<LegalMatter> findByIdForUpdate(UUID id);

    @Override
    default Optional<LegalMatter> findOpenByCollectionCaseId(UUID collectionCaseId) {
        return findFirstByCollectionCaseIdAndStageNotIn(collectionCaseId, TERMINAL);
    }

    Optional<LegalMatter> findFirstByCollectionCaseIdAndStageNotIn(UUID collectionCaseId, Collection<LegalStage> stages);

    @Override
    @Query("select max(m.closedAt) from LegalMatter m where m.collectionCaseId = :collectionCaseId")
    Optional<Instant> lastClosedAtByCollectionCaseId(UUID collectionCaseId);

    @Override
    default List<LegalMatter> findOpenByTitleId(UUID titleId) {
        return findByTitleAndStageNotIn(titleId, TERMINAL);
    }

    @Query("select m from LegalMatter m join m.titleIds t where t = :titleId and m.stage not in :stages")
    List<LegalMatter> findByTitleAndStageNotIn(UUID titleId, Collection<LegalStage> stages);

    @Override
    List<LegalMatter> findAllByIdIn(Collection<UUID> ids);

    @Override
    default Page<LegalMatter> search(MatterSearchCriteria c, Pageable pageable) {
        return findAll(spec(c), pageable);
    }

    private static Specification<LegalMatter> spec(MatterSearchCriteria c) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (c.creditorScope() != null) {
                p.add(c.creditorScope().isEmpty() ? cb.disjunction() : root.get("creditorId").in(c.creditorScope()));
            }
            if (c.lawFirmScope() != null) {
                p.add(cb.equal(root.get("lawFirmId"), c.lawFirmScope()));
            }
            eq(p, cb, root, "creditorId", c.creditorId());
            eq(p, cb, root, "portfolioId", c.portfolioId());
            eq(p, cb, root, "debtorId", c.debtorId());
            eq(p, cb, root, "collectionCaseId", c.collectionCaseId());
            eq(p, cb, root, "responsibleType", c.responsibleType());
            eq(p, cb, root, "responsibleUserId", c.responsibleUserId());
            eq(p, cb, root, "lawFirmId", c.lawFirmId());
            if (c.stages() != null && !c.stages().isEmpty()) {
                p.add(root.get("stage").in(c.stages()));
            }
            if (c.open() != null) {
                p.add(c.open() ? cb.not(root.get("stage").in(TERMINAL)) : root.get("stage").in(TERMINAL));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    private static void eq(List<Predicate> p, CriteriaBuilder cb, Root<LegalMatter> root, String field, Object value) {
        if (value != null) {
            p.add(cb.equal(root.get(field), value));
        }
    }
}
