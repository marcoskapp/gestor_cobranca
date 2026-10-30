package br.com.gestorcobranca.organization.infrastructure.persistence;

import java.util.List;

import br.com.gestorcobranca.shared.paging.ChangeQuery;
import br.com.gestorcobranca.shared.paging.ChangeSpecifications;

import br.com.gestorcobranca.organization.domain.model.Portfolio;
import br.com.gestorcobranca.organization.domain.model.PortfolioStatus;
import br.com.gestorcobranca.organization.domain.repository.PortfolioRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.UUID;

interface PortfolioJpaRepository extends JpaRepository<Portfolio, UUID>, JpaSpecificationExecutor<Portfolio>,
        PortfolioRepository {

    @Override
    boolean existsByCreditorIdAndCode(UUID creditorId, String code);

    @Override
    default List<Portfolio> findChanges(UUID creditorId, ChangeQuery query) {
        return findAll(ChangeSpecifications.<Portfolio>window(query, List.of(creditorId)), ChangeSpecifications.page(query))
                .getContent();
    }

    @Override
    default Page<Portfolio> search(Collection<UUID> allowedCreditorIds, UUID creditorId, PortfolioStatus status,
                                   String text, Pageable pageable) {
        Specification<Portfolio> spec = Specification.unrestricted();
        if (allowedCreditorIds != null) {
            spec = spec.and((root, query, cb) -> allowedCreditorIds.isEmpty()
                    ? cb.disjunction() : root.get("creditorId").in(allowedCreditorIds));
        }
        if (creditorId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("creditorId"), creditorId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (text != null && !text.isBlank()) {
            String like = "%" + text.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), like),
                    cb.like(cb.lower(root.get("code")), like)));
        }
        return findAll(spec, pageable);
    }
}
