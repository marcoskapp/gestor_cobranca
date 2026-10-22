package br.com.gestorcobranca.organization.infrastructure.persistence;

import br.com.gestorcobranca.organization.domain.model.Creditor;
import br.com.gestorcobranca.organization.domain.model.CreditorStatus;
import br.com.gestorcobranca.organization.domain.repository.CreditorRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.UUID;

/**
 * Implementação Spring Data do repositório de domínio. Consultas dinâmicas usam
 * {@link Specification} para compor apenas os filtros informados.
 */
interface CreditorJpaRepository extends JpaRepository<Creditor, UUID>, JpaSpecificationExecutor<Creditor>,
        CreditorRepository {

    @Override
    boolean existsByDocument(String document);

    @Override
    default Page<Creditor> search(Collection<UUID> allowedIds, String text, CreditorStatus status, Pageable pageable) {
        Specification<Creditor> spec = Specification.unrestricted();
        if (allowedIds != null) {
            spec = spec.and((root, query, cb) -> allowedIds.isEmpty() ? cb.disjunction() : root.get("id").in(allowedIds));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (text != null && !text.isBlank()) {
            String like = "%" + text.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("legalName")), like),
                    cb.like(cb.lower(root.get("tradeName")), like),
                    cb.like(cb.lower(root.get("externalCode")), like),
                    cb.like(root.get("document"), "%" + text.replaceAll("\\D", "") + "%")));
        }
        return findAll(spec, pageable);
    }
}
