package br.com.gestorcobranca.organization.infrastructure.persistence;

import br.com.gestorcobranca.organization.domain.model.BusinessUnit;
import br.com.gestorcobranca.organization.domain.repository.BusinessUnitRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface BusinessUnitJpaRepository extends JpaRepository<BusinessUnit, UUID>, BusinessUnitRepository {

    @Override
    boolean existsByCode(String code);
}
