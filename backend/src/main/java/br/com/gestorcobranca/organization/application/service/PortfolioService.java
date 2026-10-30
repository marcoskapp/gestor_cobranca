package br.com.gestorcobranca.organization.application.service;

import br.com.gestorcobranca.shared.domain.Guard;

import br.com.gestorcobranca.shared.paging.ChangePage;
import br.com.gestorcobranca.shared.paging.ChangeQuery;

import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.domain.model.Creditor;
import br.com.gestorcobranca.organization.domain.model.Portfolio;
import br.com.gestorcobranca.organization.domain.model.PortfolioStatus;
import br.com.gestorcobranca.organization.domain.model.ServiceContract;
import br.com.gestorcobranca.organization.domain.repository.CreditorRepository;
import br.com.gestorcobranca.organization.domain.repository.PortfolioRepository;
import br.com.gestorcobranca.organization.domain.repository.ServiceContractRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.PreconditionFailedException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.organization.PortfolioStatusChanged;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class PortfolioService implements PortfolioUseCases {

    private final PortfolioRepository portfolios;
    private final CreditorRepository creditors;
    private final ServiceContractRepository contracts;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    PortfolioService(PortfolioRepository portfolios, CreditorRepository creditors, ServiceContractRepository contracts,
                     AccessScope accessScope, CurrentActor currentActor, AuditTrail auditTrail,
                     BusinessEventLogger businessEvents, ApplicationEventPublisher events, Clock clock) {
        this.portfolios = portfolios;
        this.creditors = creditors;
        this.contracts = contracts;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public PortfolioView create(CreatePortfolioCommand command) {
        Creditor creditor = creditors.findById(command.creditorId())
                .orElseThrow(() -> ResourceNotFoundException.of("creditor", command.creditorId()));
        accessScope.requireCreditor(creditor.getId());
        if (!creditor.isActive()) {
            throw new BusinessRuleException("creditor.inactive", "Credor inativo");
        }
        ServiceContract contract = contracts.findById(command.contractId())
                .filter(c -> c.getCreditorId().equals(creditor.getId()))
                .orElseThrow(() -> new BusinessRuleException("portfolio.contract_mismatch",
                        "Contrato não pertence ao credor informado"));
        if (!contract.isEffectiveOn(command.receivedOn() == null ? today() : command.receivedOn())) {
            throw new BusinessRuleException("portfolio.contract_not_effective",
                    "Contrato não está vigente na data de recebimento da carteira");
        }
        String code = command.code() == null ? null : command.code().trim().toUpperCase();
        if (code != null && portfolios.existsByCreditorIdAndCode(creditor.getId(), code)) {
            throw new ConflictException("portfolio.code_taken", "Já existe carteira com este código para o credor");
        }
        Portfolio portfolio = portfolios.save(new Portfolio(creditor.getId(), contract.getId(), code, command.name(),
                command.description(), command.receivedOn(), command.validUntil()));
        auditTrail.record(AuditEntry.of("portfolio.created", "portfolio", portfolio.getId())
                .creditor(creditor.getId())
                .created("code", portfolio.getCode())
                .created("name", portfolio.getName())
                .created("contractId", contract.getId())
                .created("validUntil", portfolio.getValidUntil()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PORTFOLIO_CREATED)
                .creditor(creditor.getId()).portfolio(portfolio.getId()).reference("portfolio", portfolio.getId()));
        return view(portfolio);
    }

    @Override
    public PortfolioView update(UUID portfolioId, UpdatePortfolioCommand command, Long expectedVersion) {
        Portfolio portfolio = loadVisible(portfolioId);
        accessScope.requireCreditor(portfolio.getCreditorId());
        requireVersion(portfolio, expectedVersion);
        AuditEntry audit = AuditEntry.of("portfolio.updated", "portfolio", portfolioId)
                .creditor(portfolio.getCreditorId())
                .change("name", portfolio.getName(), command.name())
                .change("receivedOn", portfolio.getReceivedOn(), command.receivedOn())
                .change("validUntil", portfolio.getValidUntil(), command.validUntil());
        portfolio.update(command.name(), command.description(), command.receivedOn(), command.validUntil());
        portfolios.save(portfolio);
        auditTrail.record(audit);
        return view(portfolio);
    }

    @Override
    public PortfolioView changeStatus(UUID portfolioId, String targetStatus, String reason, Long expectedVersion) {
        Portfolio portfolio = loadVisible(portfolioId);
        accessScope.requireCreditor(portfolio.getCreditorId());
        requireVersion(portfolio, expectedVersion);
        PortfolioStatus target = parseStatus(targetStatus);
        PortfolioStatus previous = portfolio.transitionTo(target, reason);
        portfolios.save(portfolio);

        auditTrail.record(AuditEntry.of("portfolio.status_changed", "portfolio", portfolioId)
                .creditor(portfolio.getCreditorId())
                .change("status", previous, target)
                .reason(reason));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PORTFOLIO_STATUS_CHANGED)
                .creditor(portfolio.getCreditorId()).portfolio(portfolioId).reference("portfolio", portfolioId)
                .attr("from", previous).attr("to", target));
        events.publishEvent(new PortfolioStatusChanged(Ids.newId(), currentActor.tenantId(), Instant.now(clock),
                portfolioId, portfolio.getCreditorId(), previous.name(), target.name()));
        return view(portfolio);
    }

    @Override
    @Transactional(readOnly = true)
    public PortfolioView get(UUID portfolioId) {
        return view(loadVisible(portfolioId));
    }

    @Override
    @Transactional(readOnly = true)
    public PortfolioView requireAcceptingImports(UUID portfolioId) {
        Portfolio portfolio = loadVisible(portfolioId);
        if (!portfolio.getStatus().acceptsImports()) {
            throw new BusinessRuleException("portfolio.not_accepting_imports",
                    "Carteira com status %s não aceita novas cargas".formatted(portfolio.getStatus()));
        }
        return view(portfolio);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PortfolioView> getAll(Collection<UUID> portfolioIds) {
        if (portfolioIds == null || portfolioIds.isEmpty()) {
            return List.of();
        }
        return portfolios.findAllByIdIn(portfolioIds).stream()
                .filter(p -> accessScope.canAccessCreditor(p.getCreditorId()))
                .map(this::view)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PortfolioView> search(UUID creditorId, String status, String text, PageQuery page) {
        Set<UUID> allowed = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(null);
        PortfolioStatus statusFilter = status == null || status.isBlank() ? null : parseStatus(status);
        return PageResponse.of(portfolios.search(allowed, creditorId, statusFilter, text,
                page.toPageable(Sort.by(Sort.Direction.DESC, "receivedOn"))), this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public ChangePage<PortfolioView> changes(UUID creditorId, ChangeQuery query) {
        Guard.require(creditorId != null, "portfolio.creditor_required", "Credor obrigatório");
        accessScope.requireVisible("creditor", creditorId, creditorId);
        return ChangePage.of(portfolios.findChanges(creditorId, query), query.limit(), Portfolio::getUpdatedAt,
                Portfolio::getId, Portfolio::getVersion, this::view);
    }

    private Portfolio loadVisible(UUID portfolioId) {
        Portfolio portfolio = portfolios.findById(portfolioId)
                .orElseThrow(() -> ResourceNotFoundException.of("portfolio", portfolioId));
        accessScope.requireVisible("portfolio", portfolioId, portfolio.getCreditorId());
        return portfolio;
    }

    /**
     * Concorrência otimista da API pública ({@code If-Match}). A gravação continua protegida pelo {@code @Version}: uma
     * alteração concorrente entre esta conferência e o commit resulta em conflito (409).
     */
    private static void requireVersion(Portfolio portfolio, Long expectedVersion) {
        if (expectedVersion != null && !expectedVersion.equals(portfolio.getVersion())) {
            throw new PreconditionFailedException("portfolio.version_mismatch",
                    "A carteira foi alterada depois da leitura. Consulte-a de novo e repita a operação.");
        }
    }

    private static PortfolioStatus parseStatus(String status) {
        try {
            return PortfolioStatus.valueOf(status);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessRuleException("portfolio.status_invalid", "Status de carteira inválido");
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    private PortfolioView view(Portfolio p) {
        return new PortfolioView(p.getId(), p.getCreditorId(), p.getContractId(), p.getCode(), p.getName(),
                p.getDescription(), p.getReceivedOn(), p.getValidUntil(), p.getStatus().name(), p.getStatusReason(),
                p.isOperableOn(today()), p.getVersion() == null ? 0L : p.getVersion(), p.getUpdatedAt());
    }
}
