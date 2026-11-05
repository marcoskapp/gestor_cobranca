package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.debt.application.port.spi.TitleAgreementGuard;
import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement;
import br.com.gestorcobranca.negotiation.domain.repository.AgreementRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementa a porta do debt (ADR-0010, {@code debt :: spi}): acordo em vigor que cobre o título. Só leitura, sem
 * escopo próprio — o debt já conferiu o título no escopo do ator.
 */
@Component
@Transactional(readOnly = true)
class AgreementTitleGuard implements TitleAgreementGuard {

    private final AgreementRepository agreements;

    AgreementTitleGuard(AgreementRepository agreements) {
        this.agreements = agreements;
    }

    @Override
    public Optional<UUID> activeAgreementCovering(UUID titleId) {
        return agreements.findActiveCovering(List.of(titleId)).stream()
                .map(Agreement::getId).min(Comparator.naturalOrder());
    }
}
