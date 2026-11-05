package br.com.gestorcobranca.organization.application.service;

import br.com.gestorcobranca.organization.application.port.in.CreditorDisputeContactUseCases;
import br.com.gestorcobranca.organization.domain.model.CreditorDisputeContact;
import br.com.gestorcobranca.organization.domain.model.DisputeContactRole;
import br.com.gestorcobranca.organization.domain.model.Portfolio;
import br.com.gestorcobranca.organization.domain.repository.CreditorDisputeContactRepository;
import br.com.gestorcobranca.organization.domain.repository.CreditorRepository;
import br.com.gestorcobranca.organization.domain.repository.PortfolioRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional
class CreditorDisputeContactService implements CreditorDisputeContactUseCases {

    /** Limite de contatos ativos por credor (somando o credor e as carteiras). */
    static final int MAX_ACTIVE = 30;

    private final CreditorDisputeContactRepository contacts;
    private final CreditorRepository creditors;
    private final PortfolioRepository portfolios;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;

    CreditorDisputeContactService(CreditorDisputeContactRepository contacts, CreditorRepository creditors,
                                  PortfolioRepository portfolios, AccessScope accessScope, AuditTrail auditTrail) {
        this.contacts = contacts;
        this.creditors = creditors;
        this.portfolios = portfolios;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DisputeContactView> list(UUID creditorId) {
        requireVisibleCreditor(creditorId);
        return contacts.findByCreditorIdOrderByPortfolioIdAscRoleAscNameAsc(creditorId).stream()
                .map(CreditorDisputeContactService::view).toList();
    }

    @Override
    public DisputeContactView create(UUID creditorId, DisputeContactCommand command) {
        requireVisibleCreditor(creditorId);
        accessScope.requireCreditor(creditorId);
        Guard.require(command != null, "dispute_contact.required", "Dados do contato obrigatórios");
        if (command.portfolioId() != null) {
            portfolios.findById(command.portfolioId())
                    .map(Portfolio::getCreditorId)
                    .filter(creditorId::equals)
                    .orElseThrow(() -> new BusinessRuleException("dispute_contact.portfolio_invalid",
                            "A carteira não pertence ao credor"));
        }
        List<CreditorDisputeContact> active = contacts.findByCreditorIdAndActiveTrue(creditorId);
        if (active.size() >= MAX_ACTIVE) {
            throw new ConflictException("dispute_contact.limit", "Limite de %d contatos ativos por credor"
                    .formatted(MAX_ACTIVE));
        }
        CreditorDisputeContact contact = new CreditorDisputeContact(creditorId, command.portfolioId(), command.name(),
                command.email(), command.phone(), role(command.role()));
        requireUnique(active, contact, null);
        CreditorDisputeContact saved = contacts.save(contact);
        auditTrail.record(AuditEntry.of("creditor_dispute_contact.created", "creditor_dispute_contact", saved.getId())
                .creditor(creditorId)
                .created("portfolioId", saved.getPortfolioId())
                .created("name", saved.getName())
                .created("email", saved.getEmail())
                .created("role", saved.getRole()));
        return view(saved);
    }

    @Override
    public DisputeContactView update(UUID creditorId, UUID contactId, DisputeContactCommand command) {
        Guard.require(command != null, "dispute_contact.required", "Dados do contato obrigatórios");
        CreditorDisputeContact contact = loadForCommand(creditorId, contactId);
        Guard.requireState(contact.isActive(), "dispute_contact.inactive", "Contato desativado");
        AuditEntry audit = AuditEntry.of("creditor_dispute_contact.updated", "creditor_dispute_contact", contactId)
                .creditor(creditorId);
        String beforeName = contact.getName();
        String beforeEmail = contact.getEmail();
        String beforePhone = contact.getPhone();
        DisputeContactRole beforeRole = contact.getRole();
        contact.update(command.name(), command.email(), command.phone(), role(command.role()));
        requireUnique(contacts.findByCreditorIdAndActiveTrue(creditorId), contact, contactId);
        CreditorDisputeContact saved = contacts.save(contact);
        auditTrail.record(audit.change("name", beforeName, saved.getName())
                .change("email", beforeEmail, saved.getEmail())
                .change("phone", beforePhone, saved.getPhone())
                .change("role", beforeRole, saved.getRole()));
        return view(saved);
    }

    @Override
    public DisputeContactView deactivate(UUID creditorId, UUID contactId) {
        CreditorDisputeContact contact = loadForCommand(creditorId, contactId);
        contact.deactivate();
        CreditorDisputeContact saved = contacts.save(contact);
        auditTrail.record(AuditEntry.of("creditor_dispute_contact.deactivated", "creditor_dispute_contact", contactId)
                .creditor(creditorId).change("active", true, false));
        return view(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Recipients recipients(UUID creditorId, UUID portfolioId) {
        requireVisibleCreditor(creditorId);
        List<CreditorDisputeContact> active = contacts.findByCreditorIdAndActiveTrue(creditorId);
        List<CreditorDisputeContact> ofPortfolio = portfolioId == null ? List.of()
                : active.stream().filter(c -> portfolioId.equals(c.getPortfolioId())).toList();
        boolean portfolioLevel = ofPortfolio.stream().anyMatch(c -> c.getRole() == DisputeContactRole.PRIMARY);
        List<CreditorDisputeContact> chosen = portfolioLevel ? ofPortfolio
                : active.stream().filter(c -> c.getPortfolioId() == null).toList();
        Comparator<CreditorDisputeContact> order = Comparator.comparing(CreditorDisputeContact::getName)
                .thenComparing(CreditorDisputeContact::getEmail);
        return new Recipients(
                chosen.stream().filter(c -> c.getRole() == DisputeContactRole.PRIMARY).sorted(order)
                        .map(CreditorDisputeContactService::recipient).toList(),
                chosen.stream().filter(c -> c.getRole() == DisputeContactRole.COPY).sorted(order)
                        .map(CreditorDisputeContactService::recipient).toList(),
                portfolioLevel);
    }

    private void requireUnique(List<CreditorDisputeContact> active, CreditorDisputeContact candidate, UUID self) {
        boolean taken = active.stream()
                .filter(c -> !c.getId().equals(self))
                .anyMatch(c -> Objects.equals(c.getPortfolioId(), candidate.getPortfolioId())
                        && c.getEmail().equals(candidate.getEmail()));
        if (taken) {
            throw new ConflictException("dispute_contact.email_taken",
                    "Já existe contato ativo com este e-mail para o credor (ou a carteira)");
        }
    }

    private CreditorDisputeContact loadForCommand(UUID creditorId, UUID contactId) {
        requireVisibleCreditor(creditorId);
        accessScope.requireCreditor(creditorId);
        return contacts.findById(contactId).filter(c -> c.getCreditorId().equals(creditorId))
                .orElseThrow(() -> ResourceNotFoundException.of("creditor_dispute_contact", contactId));
    }

    private void requireVisibleCreditor(UUID creditorId) {
        Guard.require(creditorId != null, "dispute_contact.creditor_required", "Credor obrigatório");
        creditors.findById(creditorId).orElseThrow(() -> ResourceNotFoundException.of("creditor", creditorId));
        accessScope.requireVisible("creditor", creditorId, creditorId);
    }

    private static DisputeContactRole role(String value) {
        Guard.require(value != null && !value.isBlank(), "dispute_contact.role_required",
                "Informe se é contato principal ou cópia");
        try {
            return DisputeContactRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("dispute_contact.role_invalid", "Papel do contato inválido: " + value);
        }
    }

    private static Recipient recipient(CreditorDisputeContact c) {
        return new Recipient(c.getId(), c.getName(), c.getEmail());
    }

    static DisputeContactView view(CreditorDisputeContact c) {
        return new DisputeContactView(c.getId(), c.getCreditorId(), c.getPortfolioId(), c.getName(), c.getEmail(),
                c.getPhone(), c.getRole().name(), c.isActive(), c.getVersion());
    }
}
