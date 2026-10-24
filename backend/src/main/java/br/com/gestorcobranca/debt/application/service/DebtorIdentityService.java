package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.DebtorIdentityUseCases;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.model.PersonType;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/** Confirmação de identidade sem revelar o documento (métrica {@code debt.identity_checks{outcome}}). */
@Service
@Transactional(readOnly = true)
class DebtorIdentityService implements DebtorIdentityUseCases {

    private final DebtorVisibility visibility;
    private final MeterRegistry registry;

    DebtorIdentityService(DebtorVisibility visibility, MeterRegistry registry) {
        this.visibility = visibility;
        this.registry = registry;
    }

    @Override
    public IdentityCheck check(UUID debtorId, String documentPrefix, LocalDate birthDate) {
        Debtor debtor = visibility.requireVisible(debtorId);
        String outcome;
        if (debtor.isAnonymized() || debtor.getBirthDate() == null || debtor.getPersonType() != PersonType.INDIVIDUAL) {
            outcome = "UNAVAILABLE";
        } else {
            outcome = debtor.matchesIdentity(documentPrefix, birthDate) ? "MATCHED" : "NOT_MATCHED";
        }
        registry.counter("debt.identity_checks", "outcome", outcome).increment();
        return new IdentityCheck(debtorId, outcome);
    }
}
