package br.com.gestorcobranca.organization.domain.repository;

import br.com.gestorcobranca.organization.domain.model.CreditorDisputeContact;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreditorDisputeContactRepository {

    CreditorDisputeContact save(CreditorDisputeContact contact);

    Optional<CreditorDisputeContact> findById(UUID id);

    List<CreditorDisputeContact> findByCreditorIdOrderByPortfolioIdAscRoleAscNameAsc(UUID creditorId);

    List<CreditorDisputeContact> findByCreditorIdAndActiveTrue(UUID creditorId);
}
