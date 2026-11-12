package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.CompanyRepresentative;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CompanyRepresentativeRepository {

    CompanyRepresentative save(CompanyRepresentative representative);

    Optional<CompanyRepresentative> findById(UUID id);

    /** Representantes da empresa (vigentes e encerrados), na ordem de cadastro. */
    List<CompanyRepresentative> findByCompanyDebtorIdOrderByCreatedAtAsc(UUID companyDebtorId);

    /** Vigente da empresa com o CPF (único por empresa e CPF). */
    Optional<CompanyRepresentative> findByCompanyDebtorIdAndDocumentHashAndEndedAtIsNull(UUID companyDebtorId,
                                                                                      String documentHash);

    /** Todas as participações do CPF como representante (solicitação de titular). */
    List<CompanyRepresentative> findByDocumentHashOrderByCreatedAtAsc(String documentHash);

    void deleteAll(Iterable<? extends CompanyRepresentative> representatives);
}
