package br.com.gestorcobranca.organization.application.service;

import br.com.gestorcobranca.organization.application.port.in.ContractIntegrationPricingUseCases;
import br.com.gestorcobranca.organization.domain.model.ContractIntegrationPricing;
import br.com.gestorcobranca.organization.domain.model.ServiceContract;
import br.com.gestorcobranca.organization.domain.repository.ContractIntegrationPricingRepository;
import br.com.gestorcobranca.organization.domain.repository.ServiceContractRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Condições de cobrança das integrações no contrato do credor. Cada definição grava uma nova versão (nunca altera a
 * anterior); o ciclo de consumo usa a versão registrada antes do seu início — alteração vale do próximo ciclo em
 * diante. Concorrência: a versão é única por contrato (restrição no banco); duas gravações simultâneas no mesmo
 * contrato fazem a segunda falhar em vez de duplicar a versão.
 */
@Service
@Transactional
class ContractIntegrationPricingService implements ContractIntegrationPricingUseCases {

    private final ContractIntegrationPricingRepository pricings;
    private final ServiceContractRepository contracts;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final Clock clock;

    ContractIntegrationPricingService(ContractIntegrationPricingRepository pricings, ServiceContractRepository contracts,
                                      AccessScope accessScope, CurrentActor currentActor, AuditTrail auditTrail,
                                      BusinessEventLogger businessEvents, Clock clock) {
        this.pricings = pricings;
        this.contracts = contracts;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.clock = clock;
    }

    @Override
    public IntegrationPricingView define(UUID contractId, IntegrationPricingCommand c) {
        accessScope.requirePermission(Permission.CONTRACT_MANAGE);
        Guard.require(c != null, "contract_pricing.terms_required", "Condições obrigatórias");
        ServiceContract contract = loadVisible(contractId);
        accessScope.requireCreditor(contract.getCreditorId());
        Optional<ContractIntegrationPricing> previous = pricings.findByContractIdOrderByPricingVersionDesc(contractId)
                .stream().findFirst();
        ContractIntegrationPricing pricing = pricings.save(new ContractIntegrationPricing(contract,
                pricings.maxVersion(contractId) + 1, new ContractIntegrationPricing.Terms(c.usageClosingDay(),
                c.monthlyFee(), c.includedRequests(), c.pricePerRequest(), c.includedMbIn(), c.pricePerMbIn(),
                c.includedMbOut(), c.pricePerMbOut(), c.includedRecords(), c.pricePerRecord()),
                Instant.now(clock), currentActor.actorId(), c.reason()));
        auditTrail.record(AuditEntry.of("contract.integration_pricing_defined", "service_contract", contractId)
                .creditor(contract.getCreditorId()).reason(pricing.getReason())
                .meta("pricingId", pricing.getId()).meta("version", pricing.getPricingVersion())
                .change("usageClosingDay", previous.map(ContractIntegrationPricing::getUsageClosingDay).orElse(null),
                        pricing.getUsageClosingDay())
                .change("monthlyFee", previous.map(ContractIntegrationPricing::getMonthlyFee).orElse(null),
                        pricing.getMonthlyFee())
                .change("includedRequests", previous.map(ContractIntegrationPricing::getIncludedRequests).orElse(null),
                        pricing.getIncludedRequests())
                .change("pricePerRequest", previous.map(ContractIntegrationPricing::getPricePerRequest).orElse(null),
                        pricing.getPricePerRequest())
                .change("includedMbIn", previous.map(ContractIntegrationPricing::getIncludedMbIn).orElse(null),
                        pricing.getIncludedMbIn())
                .change("pricePerMbIn", previous.map(ContractIntegrationPricing::getPricePerMbIn).orElse(null),
                        pricing.getPricePerMbIn())
                .change("includedMbOut", previous.map(ContractIntegrationPricing::getIncludedMbOut).orElse(null),
                        pricing.getIncludedMbOut())
                .change("pricePerMbOut", previous.map(ContractIntegrationPricing::getPricePerMbOut).orElse(null),
                        pricing.getPricePerMbOut())
                .change("includedRecords", previous.map(ContractIntegrationPricing::getIncludedRecords).orElse(null),
                        pricing.getIncludedRecords())
                .change("pricePerRecord", previous.map(ContractIntegrationPricing::getPricePerRecord).orElse(null),
                        pricing.getPricePerRecord()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.CONTRACT_INTEGRATION_PRICING_DEFINED)
                .creditor(contract.getCreditorId()).reference("service_contract", contractId)
                .attr("version", pricing.getPricingVersion()).attr("usageClosingDay", pricing.getUsageClosingDay())
                .attr("includedRequests", pricing.getIncludedRequests()).attr("includedMbIn", pricing.getIncludedMbIn())
                .attr("includedMbOut", pricing.getIncludedMbOut()).attr("includedRecords", pricing.getIncludedRecords())
                .amount(pricing.getMonthlyFee()));
        return view(pricing, contract);
    }

    @Override
    @Transactional(readOnly = true)
    public List<IntegrationPricingView> history(UUID contractId) {
        ServiceContract contract = loadVisible(contractId);
        return pricings.findByContractIdOrderByPricingVersionDesc(contractId).stream()
                .map(p -> view(p, contract)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IntegrationPricingView> applicable(UUID creditorId, Instant cycleStart) {
        Guard.require(creditorId != null && cycleStart != null, "contract_pricing.required",
                "Credor e início do ciclo obrigatórios");
        accessScope.requireVisible("creditor", creditorId, creditorId);
        LocalDate day = cycleStart.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
        return contracts.findByCreditorId(creditorId).stream()
                .filter(k -> !day.isBefore(k.getStartDate()) && (k.getEndDate() == null || !day.isAfter(k.getEndDate())))
                .max(Comparator.comparing(ServiceContract::getStartDate).thenComparing(ServiceContract::getNumber))
                .flatMap(k -> pricings.findFirstByContractIdAndRegisteredAtBeforeOrderByPricingVersionDesc(k.getId(),
                        cycleStart).map(p -> view(p, k)));
    }

    private ServiceContract loadVisible(UUID contractId) {
        ServiceContract contract = contracts.findById(contractId)
                .orElseThrow(() -> ResourceNotFoundException.of("service_contract", contractId));
        accessScope.requireVisible("service_contract", contractId, contract.getCreditorId());
        return contract;
    }

    private static IntegrationPricingView view(ContractIntegrationPricing p, ServiceContract contract) {
        return new IntegrationPricingView(p.getId(), p.getContractId(), contract.getNumber(), p.getCreditorId(),
                p.getPricingVersion(), p.getUsageClosingDay(), p.getMonthlyFee(), p.getIncludedRequests(),
                p.getPricePerRequest(), p.getIncludedMbIn(), p.getPricePerMbIn(), p.getIncludedMbOut(),
                p.getPricePerMbOut(), p.getIncludedRecords(), p.getPricePerRecord(), p.getRegisteredAt(),
                p.getRegisteredBy(), p.getReason());
    }
}
