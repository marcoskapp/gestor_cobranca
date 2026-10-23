package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.application.port.in.LegalBasisUseCases;
import br.com.gestorcobranca.compliance.domain.model.LegalBasis;
import br.com.gestorcobranca.compliance.domain.model.LegalBasisRecord;
import br.com.gestorcobranca.compliance.domain.model.ProcessingRole;
import br.com.gestorcobranca.compliance.domain.repository.LegalBasisRecordRepository;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
class LegalBasisService implements LegalBasisUseCases {

    private final LegalBasisRecordRepository records;
    private final CreditorUseCases creditors;
    private final PortfolioUseCases portfolios;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;
    private final Clock clock;

    LegalBasisService(LegalBasisRecordRepository records, CreditorUseCases creditors, PortfolioUseCases portfolios,
                      AccessScope accessScope, AuditTrail auditTrail, Clock clock) {
        this.records = records;
        this.creditors = creditors;
        this.portfolios = portfolios;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    @Override
    public LegalBasisView register(RegisterLegalBasisCommand command) {
        Guard.require(command.creditorId() != null, "legal_basis.creditor_required", "Credor obrigatório");
        accessScope.requireCreditor(command.creditorId());
        creditors.get(command.creditorId());
        if (command.portfolioId() != null && !portfolios.get(command.portfolioId()).creditorId().equals(command.creditorId())) {
            throw new BusinessRuleException("legal_basis.portfolio_mismatch", "Carteira não pertence ao credor informado");
        }
        LegalBasisRecord record = new LegalBasisRecord(command.creditorId(), command.portfolioId(),
                ComplianceEnums.parse(LegalBasis.class, command.legalBasis(), "legal_basis.basis_invalid", "Base legal"),
                ComplianceEnums.parseOptional(ProcessingRole.class, command.processingRole(), "legal_basis.role_invalid",
                        "Papel da assessoria"),
                command.contractReference(), command.validFrom(), command.validUntil(), command.legalResponsible(),
                command.retentionMonths(), command.notes());
        if (records.existsOverlapping(record.getCreditorId(), record.getPortfolioId(), record.getValidFrom(),
                record.getValidUntil())) {
            throw new ConflictException("legal_basis.overlap",
                    "Já existe base legal vigente no período para este credor/carteira; encerre-a antes");
        }
        records.save(record);
        auditTrail.record(AuditEntry.of("legal_basis.registered", "legal_basis_record", record.getId())
                .creditor(record.getCreditorId())
                .created("portfolioId", record.getPortfolioId())
                .created("legalBasis", record.getLegalBasis())
                .created("processingRole", record.getProcessingRole())
                .created("contractReference", record.getContractReference())
                .created("validFrom", record.getValidFrom())
                .created("validUntil", record.getValidUntil())
                .created("legalResponsible", record.getLegalResponsible())
                .created("retentionMonths", record.getRetentionMonths()));
        return view(record);
    }

    @Override
    public LegalBasisView end(UUID recordId, LocalDate lastDay, String reason) {
        LegalBasisRecord record = records.findById(recordId)
                .orElseThrow(() -> ResourceNotFoundException.of("legal_basis_record", recordId));
        accessScope.requireVisible("legal_basis_record", recordId, record.getCreditorId());
        accessScope.requireCreditor(record.getCreditorId());
        LocalDate previous = record.getValidUntil();
        record.end(lastDay, reason);
        records.save(record);
        auditTrail.record(AuditEntry.of("legal_basis.ended", "legal_basis_record", recordId)
                .creditor(record.getCreditorId())
                .reason(record.getEndReason())
                .change("validUntil", previous, record.getValidUntil()));
        return view(record);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LegalBasisView> listByCreditor(UUID creditorId) {
        accessScope.requireVisible("creditor", creditorId, creditorId);
        return records.findByCreditorIdOrderByValidFromDesc(creditorId).stream().map(LegalBasisService::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LegalBasisView> findEffective(UUID creditorId, UUID portfolioId, LocalDate date) {
        Guard.require(creditorId != null, "legal_basis.query_invalid", "Informe o credor");
        accessScope.requireVisible("creditor", creditorId, creditorId);
        LocalDate day = date == null ? LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)) : date;
        Optional<LegalBasisRecord> specific = portfolioId == null ? Optional.empty()
                : records.findEffective(creditorId, portfolioId, day);
        return specific.or(() -> records.findEffective(creditorId, null, day)).map(LegalBasisService::view);
    }

    static LegalBasisView view(LegalBasisRecord r) {
        return new LegalBasisView(r.getId(), r.getCreditorId(), r.getPortfolioId(), r.getLegalBasis().name(),
                r.getProcessingRole().name(), r.getContractReference(), r.getValidFrom(), r.getValidUntil(),
                r.getLegalResponsible(), r.getRetentionMonths(), r.getNotes(), r.getEndReason(), r.getCreatedAt());
    }
}
