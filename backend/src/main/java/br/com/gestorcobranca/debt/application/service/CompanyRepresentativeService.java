package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.CompanyRepresentativeUseCases;
import br.com.gestorcobranca.debt.domain.model.CompanyRepresentative;
import br.com.gestorcobranca.debt.domain.model.Debtor;
import br.com.gestorcobranca.debt.domain.model.RepresentativeRole;
import br.com.gestorcobranca.debt.domain.repository.CompanyRepresentativeRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
class CompanyRepresentativeService implements CompanyRepresentativeUseCases {

    static final String RESOURCE = "company_representative";

    private final CompanyRepresentativeRepository representatives;
    private final RepresentativeRegistry registry;
    private final PersonalDataIndex index;
    private final DebtorVisibility visibility;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final Clock clock;

    CompanyRepresentativeService(CompanyRepresentativeRepository representatives, RepresentativeRegistry registry,
                                 PersonalDataIndex index, DebtorVisibility visibility, AccessScope accessScope,
                                 CurrentActor currentActor, AuditTrail auditTrail, AccessLogger accessLogger,
                                 Clock clock) {
        this.representatives = representatives;
        this.registry = registry;
        this.index = index;
        this.visibility = visibility;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RepresentativeView> list(UUID companyId) {
        visibility.requireVisible(companyId);
        LocalDate today = DebtViews.today(clock);
        return representatives.findByCompanyDebtorIdOrderByCreatedAtAsc(companyId).stream()
                .sorted(Comparator.comparing((CompanyRepresentative r) -> !r.isActive()))
                .map(r -> DebtViews.representative(r, today)).toList();
    }

    @Override
    public RepresentativeView add(UUID companyId, AddRepresentativeCommand command) {
        accessScope.requirePermission(Permission.DEBTOR_MANAGE);
        Guard.require(command != null, "representative.request_required", "Informe o representante");
        Debtor company = visibility.requireVisible(companyId);
        Document cpf = cpf(command.document());
        if (registry.findActive(company, cpf).isPresent()) {
            throw new ConflictException("representative.duplicate", "Este CPF já é representante vigente da empresa");
        }
        CompanyRepresentative r = registry.create(company, cpf, command.name(), role(command.role()),
                command.canNegotiate(), command.validUntil(), command.evidenceNote(), CompanyRepresentative.Source.MANUAL);
        return DebtViews.representative(r, DebtViews.today(clock));
    }

    @Override
    public RepresentativeView update(UUID companyId, UUID representativeId, UpdateRepresentativeCommand command) {
        accessScope.requirePermission(Permission.DEBTOR_MANAGE);
        Guard.require(command != null, "representative.request_required", "Informe o representante");
        CompanyRepresentative r = load(companyId, representativeId);
        registry.update(r, command.name(), role(command.role()), command.canNegotiate(), command.validUntil(),
                command.evidenceNote(), CompanyRepresentative.Source.MANUAL);
        return DebtViews.representative(r, DebtViews.today(clock));
    }

    @Override
    public RepresentativeView end(UUID companyId, UUID representativeId, String reason) {
        accessScope.requirePermission(Permission.DEBTOR_MANAGE);
        CompanyRepresentative r = load(companyId, representativeId);
        r.end(reason, Instant.now(clock));
        representatives.save(r);
        auditTrail.record(AuditEntry.of("company_representative.ended", RESOURCE, r.getId())
                .meta("companyDebtorId", companyId).reason(r.getEndReason())
                .change("active", true, false));
        return DebtViews.representative(r, DebtViews.today(clock));
    }

    @Override
    @Transactional(readOnly = true)
    public RevealedRepresentative revealDocument(UUID companyId, UUID representativeId) {
        accessScope.requirePermission(Permission.DEBTOR_SENSITIVE_VIEW);
        CompanyRepresentative r = load(companyId, representativeId);
        accessLogger.log(AccessEvent.of(AccessEventType.SENSITIVE_DATA_VIEWED, currentActor.get(),
                Map.of("resource", RESOURCE, "resourceId", representativeId.toString(), "debtorId", companyId.toString(),
                        "field", "document")));
        return new RevealedRepresentative(representativeId, companyId, r.cpf().formatted());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RepresentativeView> negotiator(UUID companyId, String cpf) {
        Debtor company = visibility.requireVisible(companyId);
        Document document;
        try {
            document = Document.parse(cpf);
        } catch (BusinessRuleException e) {
            return Optional.empty();
        }
        if (!company.isCompany() || company.isAnonymized() || document.type() != Document.DocumentType.CPF) {
            return Optional.empty();
        }
        LocalDate today = DebtViews.today(clock);
        return representatives.findByCompanyDebtorIdAndDocumentHashAndEndedAtIsNull(companyId, index.representative(document))
                .filter(r -> r.canNegotiateOn(today))
                .map(r -> DebtViews.representative(r, today));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasNegotiators(UUID companyId) {
        visibility.requireVisible(companyId);
        LocalDate today = DebtViews.today(clock);
        return representatives.findByCompanyDebtorIdOrderByCreatedAtAsc(companyId).stream()
                .anyMatch(r -> r.canNegotiateOn(today));
    }

    @Override
    @Transactional(readOnly = true)
    public RepresentativeView get(UUID companyId, UUID representativeId) {
        return DebtViews.representative(load(companyId, representativeId), DebtViews.today(clock));
    }

    private CompanyRepresentative load(UUID companyId, UUID representativeId) {
        visibility.requireVisible(companyId);
        return representatives.findById(representativeId)
                .filter(r -> r.getCompanyDebtorId().equals(companyId))
                .orElseThrow(() -> ResourceNotFoundException.of(RESOURCE, representativeId));
    }

    private static Document cpf(String raw) {
        Document document = Document.parse(raw);
        Guard.require(document.type() == Document.DocumentType.CPF, "representative.cpf_required",
                "O representante é uma pessoa física: informe o CPF");
        return document;
    }

    static RepresentativeRole role(String value) {
        return DebtViews.parseEnum(RepresentativeRole.class, value, "representative.role_invalid", "Papel do representante");
    }
}
