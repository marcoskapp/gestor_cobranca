package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.repository.DebtorRepository;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

interface DebtorJpaRepository extends JpaRepository<Debtor, UUID>, JpaSpecificationExecutor<Debtor>, DebtorRepository {

    @Override
    Optional<Debtor> findByDocumentHash(String documentHash);

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
