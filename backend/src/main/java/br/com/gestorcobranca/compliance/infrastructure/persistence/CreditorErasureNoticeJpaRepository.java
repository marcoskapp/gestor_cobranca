package br.com.gestorcobranca.compliance.infrastructure.persistence;

import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice;
import br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice.Status;
import br.com.gestorcobranca.compliance.domain.repository.CreditorErasureNoticeRepository;
import br.com.gestorcobranca.compliance.domain.repository.PendingNoticeCount;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Índices: {@code ix_creditor_erasure_notice_pending}, {@code _creditor} e {@code _origin} (V0511); remessa e contagem
 * por credor: {@code ix_creditor_erasure_notice_pending_creditor} (V0514).
 */
interface CreditorErasureNoticeJpaRepository extends JpaRepository<CreditorErasureNotice, UUID>,
        JpaSpecificationExecutor<CreditorErasureNotice>, CreditorErasureNoticeRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from CreditorErasureNotice n where n.id = :id")
    Optional<CreditorErasureNotice> findByIdForUpdate(@Param("id") UUID id);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from CreditorErasureNotice n where n.id in :ids order by n.id")
    List<CreditorErasureNotice> findAllByIdInForUpdate(@Param("ids") Collection<UUID> ids);

    @Override
    default List<CreditorErasureNotice> findPendingByCreditor(UUID creditorId, int limit) {
        return findByCreditorIdAndStatusOrderByErasedAtAscIdAsc(creditorId, Status.PENDING, Limit.of(limit));
    }

    List<CreditorErasureNotice> findByCreditorIdAndStatusOrderByErasedAtAscIdAsc(UUID creditorId, Status status,
                                                                                  Limit limit);

    @Override
    @Query("""
            select new br.com.gestorcobranca.compliance.domain.repository.PendingNoticeCount(
                   n.creditorId, count(n), min(n.erasedAt)) from CreditorErasureNotice n
             where n.status = br.com.gestorcobranca.compliance.domain.model.CreditorErasureNotice.Status.PENDING
             group by n.creditorId order by min(n.erasedAt)
            """)
    List<PendingNoticeCount> pendingByCreditor();

    /** Só os filtros informados entram na consulta (plano com o índice de cada um). */
    @Override
    default Page<CreditorErasureNotice> search(Status status, UUID creditorId, UUID originId, Pageable pageable) {
        Specification<CreditorErasureNotice> spec = Specification.unrestricted();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (creditorId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("creditorId"), creditorId));
        }
        if (originId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("originId"), originId));
        }
        return findAll(spec, pageable);
    }
}
