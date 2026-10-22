package br.com.gestorcobranca.organization.application.port.in;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Contratos de prestação de serviço entre assessoria e credor. */
public interface ServiceContractUseCases {

    ContractView create(CreateContractCommand command);

    ContractView updateTerms(UUID contractId, ContractTermsCommand command);

    ContractView terminate(UUID contractId, LocalDate endDate, String reason);

    ContractView get(UUID contractId);

    List<ContractView> listByCreditor(UUID creditorId);

    record CreateContractCommand(UUID creditorId, String number, ContractTermsCommand terms) {
    }

    record ContractTermsCommand(LocalDate startDate, LocalDate endDate, Integer returnAfterDays, int settlementDay,
                                String notes) {
    }

    record ContractView(UUID id, UUID creditorId, String number, LocalDate startDate, LocalDate endDate,
                        Integer returnAfterDays, int settlementDay, String notes, String status) {
    }
}
