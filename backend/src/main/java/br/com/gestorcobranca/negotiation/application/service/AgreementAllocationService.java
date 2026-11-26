package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.application.port.in.allocation.AgreementAllocationQueries;
import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement;
import br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementInstallment;
import br.com.gestorcobranca.negotiation.domain.model.agreement.AgreementStatus;
import br.com.gestorcobranca.negotiation.domain.repository.AgreementRepository;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Leituras do acordo para a alocação de pagamentos (decisão 1435): projeção estreita, mesmo escopo da consulta. */
@Service
@Transactional(readOnly = true)
class AgreementAllocationService implements AgreementAllocationQueries {

    private static final String RESOURCE = "agreement";
    private static final List<AgreementStatus> ACTIVE = List.of(AgreementStatus.FORMALIZED, AgreementStatus.IN_PAYMENT);

    private final AgreementRepository agreements;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;

    AgreementAllocationService(AgreementRepository agreements, AccessScope accessScope, CurrentActor currentActor,
                               AccessLogger accessLogger) {
        this.agreements = agreements;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
    }

    @Override
    public AllocationAgreement agreement(UUID agreementId) {
        requireReader();
        Agreement a = agreements.findById(agreementId).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, agreementId));
        accessScope.requireVisible(RESOURCE, agreementId, a.getCreditorId());
        DebtorSelfScope.requireOwn(currentActor.get(), RESOURCE, agreementId, a.getDebtorId(), accessLogger);
        return view(a);
    }

    @Override
    public List<UUID> titleIdsForReversalHold(UUID agreementId) {
        Agreement a = agreements.findById(agreementId).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, agreementId));
        accessScope.requireVisible(RESOURCE, agreementId, a.getCreditorId());
        return List.copyOf(a.titleIds());
    }

    @Override
    public List<AllocationAgreement> active(UUID creditorId, UUID debtorId) {
        requireReader();
        Guard.require(creditorId != null && debtorId != null, "agreement.creditor_required", "Credor e devedor obrigatórios");
        accessScope.requireCreditor(creditorId);
        UUID debtor = DebtorSelfScope.debtorFilter(currentActor.get(), debtorId);
        List<AllocationAgreement> active = new ArrayList<>();
        for (AgreementStatus status : ACTIVE) {
            agreements.search(null, creditorId, debtor, null, status, PageRequest.of(0, 200, Sort.by("id")))
                    .forEach(a -> active.add(view(a)));
        }
        return active;
    }

    /**
     * Quem aloca um pagamento precisa das parcelas e dos títulos cobertos pelo acordo, sem que isso vire consulta de
     * acordos. Todo ator precisa de {@code AGREEMENT_VIEW} (pessoa da assessoria, sistema, assistente, devedor do
     * portal), salvo os dispensados: a pessoa do credor
     * (portal) e o token de integração com {@code PAYMENT_REGISTER_EXTERNAL} ({@code payments:write}, sem
     * {@code agreements:read} — antes o registro dele era recusado; decisão 1518) — os dois só informam pagamento
     * recebido diretamente pelo credor, ação autorizada antes pelo caso de uso do payment. Nenhum escopo de token dá
     * instrução, alocação ou realocação. Interface de consumo restrito ao payment (decisão 1435); aqui o escopo por
     * credor continua conferido.
     */
    private void requireReader() {
        Actor actor = currentActor.get();
        boolean integrationRegistering = actor.type() == Actor.ActorType.INTEGRATION
                && actor.has(Permission.PAYMENT_REGISTER_EXTERNAL);
        if (!actor.isCreditor() && !integrationRegistering) {
            accessScope.requirePermission(Permission.AGREEMENT_VIEW);
        }
    }

    private static AllocationAgreement view(Agreement a) {
        return new AllocationAgreement(a.getId(), a.getCreditorId(), a.getDebtorId(), a.getPortfolioId(), a.getCaseId(),
                a.getStatus().name(), a.titleIds(), a.getInstallments().stream().map(AgreementAllocationService::installment)
                .toList());
    }

    private static AllocationInstallment installment(AgreementInstallment i) {
        return new AllocationInstallment(i.getId(), i.getNumber(), i.getDueDate(), i.getAmount().amount(),
                i.getPaidAmount().amount(), i.getStatus().name());
    }
}
