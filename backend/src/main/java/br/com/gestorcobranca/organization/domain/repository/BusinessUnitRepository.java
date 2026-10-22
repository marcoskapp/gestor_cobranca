package br.com.gestorcobranca.organization.domain.repository;

import br.com.gestorcobranca.organization.domain.model.BusinessUnit;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BusinessUnitRepository {

    BusinessUnit save(BusinessUnit unit);

    Optional<BusinessUnit> findById(UUID id);

    boolean existsByCode(String code);

    List<BusinessUnit> findAll();
}
