package br.com.gestorcobranca.organization.application.service;

import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.domain.model.Creditor;
import br.com.gestorcobranca.organization.domain.model.CreditorStatus;
import br.com.gestorcobranca.organization.domain.repository.CreditorRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class CreditorService implements CreditorUseCases {

    private final CreditorRepository creditors;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;

    CreditorService(CreditorRepository creditors, AccessScope accessScope, AuditTrail auditTrail,
                    BusinessEventLogger businessEvents) {
        this.creditors = creditors;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
    }

    @Override
    public CreditorView create(CreditorCommand command) {
        String document = Document.parse(command.document()).number();
        if (creditors.existsByDocument(document)) {
            throw new ConflictException("creditor.document_taken", "Já existe credor com este documento");
        }
        Creditor creditor = creditors.save(new Creditor(command.legalName(), command.tradeName(), document,
                command.externalCode(), command.contactEmail()));
        auditTrail.record(AuditEntry.of("creditor.created", "creditor", creditor.getId())
                .creditor(creditor.getId())
                .created("legalName", creditor.getLegalName())
                .created("document", creditor.getDocument()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.CREDITOR_CREATED)
                .creditor(creditor.getId()).reference("creditor", creditor.getId()));
        return view(creditor);
    }

    @Override
    public CreditorView update(UUID creditorId, CreditorCommand command) {
        Creditor creditor = loadVisible(creditorId);
        accessScope.requireCreditor(creditorId);
        AuditEntry audit = AuditEntry.of("creditor.updated", "creditor", creditorId).creditor(creditorId)
                .change("legalName", creditor.getLegalName(), command.legalName())
                .change("tradeName", creditor.getTradeName(), command.tradeName())
                .change("externalCode", creditor.getExternalCode(), command.externalCode())
                .change("contactEmail", creditor.getContactEmail(), command.contactEmail());
        creditor.update(command.legalName(), command.tradeName(), command.externalCode(), command.contactEmail());
        creditors.save(creditor);
        auditTrail.record(audit);
        return view(creditor);
    }

    @Override
    public CreditorView changeStatus(UUID creditorId, boolean active) {
        Creditor creditor = loadVisible(creditorId);
        accessScope.requireCreditor(creditorId);
        CreditorStatus previous = creditor.getStatus();
        if (active) {
            creditor.activate();
        } else {
            creditor.deactivate();
        }
        creditors.save(creditor);
        auditTrail.record(AuditEntry.of("creditor.status_changed", "creditor", creditorId).creditor(creditorId)
                .change("status", previous, creditor.getStatus()));
        return view(creditor);
    }

    @Override
    @Transactional(readOnly = true)
    public CreditorView get(UUID creditorId) {
        return view(loadVisible(creditorId));
    }

    @Override
    @Transactional(readOnly = true)
    public CreditorView requireActive(UUID creditorId) {
        Creditor creditor = loadVisible(creditorId);
        if (!creditor.isActive()) {
            throw new BusinessRuleException("creditor.inactive", "Credor inativo");
        }
        return view(creditor);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CreditorView> getAll(Collection<UUID> creditorIds) {
        if (creditorIds == null || creditorIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> allowed = accessScope.restrictCreditors(creditorIds);
        return creditors.findAllByIdIn(allowed).stream().map(CreditorService::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CreditorView> search(String text, String status, PageQuery page) {
        Set<UUID> allowed = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(null);
        CreditorStatus statusFilter = status == null || status.isBlank() ? null : CreditorStatus.valueOf(status);
        return PageResponse.of(creditors.search(allowed, text, statusFilter,
                page.toPageable(Sort.by("tradeName"))), CreditorService::view);
    }

    private Creditor loadVisible(UUID creditorId) {
        Creditor creditor = creditors.findById(creditorId)
                .orElseThrow(() -> ResourceNotFoundException.of("creditor", creditorId));
        accessScope.requireVisible("creditor", creditorId, creditor.getId());
        return creditor;
    }

    static CreditorView view(Creditor c) {
        return new CreditorView(c.getId(), c.getLegalName(), c.getTradeName(), c.getDocument(), c.getExternalCode(),
                c.getContactEmail(), c.getStatus().name());
    }
}
