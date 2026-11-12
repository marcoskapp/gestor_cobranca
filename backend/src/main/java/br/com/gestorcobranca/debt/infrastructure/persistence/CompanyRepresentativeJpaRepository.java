package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.CompanyRepresentative;
import br.com.gestorcobranca.debt.domain.repository.CompanyRepresentativeRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CompanyRepresentativeJpaRepository extends JpaRepository<CompanyRepresentative, UUID>,
        CompanyRepresentativeRepository {

    @Override
    List<CompanyRepresentative> findByCompanyDebtorIdOrderByCreatedAtAsc(UUID companyDebtorId);

    @Override
    Optional<CompanyRepresentative> findByCompanyDebtorIdAndDocumentHashAndEndedAtIsNull(UUID companyDebtorId,
                                                                                      String documentHash);

    @Override
    List<CompanyRepresentative> findByDocumentHashOrderByCreatedAtAsc(String documentHash);
}
