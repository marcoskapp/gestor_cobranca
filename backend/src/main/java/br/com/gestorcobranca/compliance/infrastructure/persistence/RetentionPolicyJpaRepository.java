package br.com.gestorcobranca.compliance.infrastructure.persistence;

import br.com.gestorcobranca.compliance.domain.model.RetentionCategory;
import br.com.gestorcobranca.compliance.domain.model.RetentionPolicy;
import br.com.gestorcobranca.compliance.domain.repository.RetentionPolicyRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface RetentionPolicyJpaRepository extends JpaRepository<RetentionPolicy, UUID>, RetentionPolicyRepository {

    @Override
    Optional<RetentionPolicy> findByCategory(RetentionCategory category);

    @Override
    List<RetentionPolicy> findAllByOrderByCategoryAsc();
}
