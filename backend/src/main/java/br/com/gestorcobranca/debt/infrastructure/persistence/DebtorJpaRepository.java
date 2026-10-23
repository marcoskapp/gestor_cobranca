package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import br.com.gestorcobranca.debt.domain.repository.InactiveDebtorCandidate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface DebtorJpaRepository extends JpaRepository<Debtor, UUID>, JpaSpecificationExecutor<Debtor>, DebtorRepository {

    @Override
    Optional<Debtor> findByDocumentHash(String documentHash);

    /** Id zero: menor UUID possível, início da paginação por chave. */
    UUID FIRST_ID = new UUID(0L, 0L);

    @Override
    default List<InactiveDebtorCandidate> findInactive(Instant closedBefore, UUID afterId, int limit) {
        return findInactiveCandidates(closedBefore, afterId == null ? FIRST_ID : afterId, Limit.of(limit));
    }

    /**
     * Um único agrupamento por devedor: a última movimentação é a maior data de alteração dos
     * títulos (ou o cadastro, sem títulos), e nenhum título pode estar em cobrança.
     */
    @Query("""
            select new br.com.gestorcobranca.debt.domain.repository.InactiveDebtorCandidate(
                       d.id, coalesce(max(t.updatedAt), d.createdAt))
              from Debtor d left join DebtTitle t on t.debtorId = d.id
             where d.status = br.com.gestorcobranca.debt.domain.model.DebtorStatus.ACTIVE
               and d.id > :afterId
             group by d.id, d.createdAt
            having coalesce(max(t.updatedAt), d.createdAt) < :closedBefore
               and sum(case when t.status in (br.com.gestorcobranca.debt.domain.model.TitleStatus.OPEN,
                                              br.com.gestorcobranca.debt.domain.model.TitleStatus.SUSPENDED)
                            then 1 else 0 end) = 0
             order by d.id
            """)
    List<InactiveDebtorCandidate> findInactiveCandidates(Instant closedBefore, UUID afterId, Limit limit);

    /**
     * O escopo por credor é aplicado por subconsulta nos títulos ({@code exists}), sem duplicar
     * devedores com vários títulos e sem expor devedores só de credores fora do escopo.
     */
    @Override
    default Page<Debtor> search(Collection<UUID> allowedCreditorIds, UUID creditorId, UUID portfolioId, String text,
                                String documentHash, Pageable pageable) {
        Specification<Debtor> spec = Specification.unrestricted();
        if (allowedCreditorIds != null || creditorId != null || portfolioId != null) {
            spec = spec.and((root, query, cb) -> {
                if (allowedCreditorIds != null && allowedCreditorIds.isEmpty()) {
                    return cb.disjunction();
                }
                Subquery<UUID> titles = query.subquery(UUID.class);
                Root<DebtTitle> title = titles.from(DebtTitle.class);
                titles.select(title.get("id"));
                var predicate = cb.equal(title.get("debtorId"), root.get("id"));
                if (allowedCreditorIds != null) {
                    predicate = cb.and(predicate, title.get("creditorId").in(allowedCreditorIds));
                }
                if (creditorId != null) {
                    predicate = cb.and(predicate, cb.equal(title.get("creditorId"), creditorId));
                }
                if (portfolioId != null) {
                    predicate = cb.and(predicate, cb.equal(title.get("portfolioId"), portfolioId));
                }
                titles.where(predicate);
                return cb.exists(titles);
            });
        }
        if (documentHash != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("documentHash"), documentHash));
        } else if (text != null && !text.isBlank()) {
            String like = "%" + text.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("name")), like));
        }
        return findAll(spec, pageable);
    }
}
