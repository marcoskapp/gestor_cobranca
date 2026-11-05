package br.com.gestorcobranca.organization.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Contato do credor responsável por atender pedidos de contestação da assessoria (canal formal):
 * nome, e-mail e telefone opcional, principal (destinatário) ou cópia, do credor inteiro ou de uma
 * carteira específica. Dado de contato corporativo do credor — não é dado do devedor.
 */
@Entity
@Table(name = "creditor_dispute_contact", schema = "organization")
public class CreditorDisputeContact extends TenantScopedEntity {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^[0-9()+\\-\\s]{8,30}$");

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", updatable = false)
    private UUID portfolioId;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "email", nullable = false, length = 200)
    private String email;

    @Column(name = "phone", length = 30)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private DisputeContactRole role;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected CreditorDisputeContact() {
    }

    public CreditorDisputeContact(UUID creditorId, UUID portfolioId, String name, String email, String phone,
                                  DisputeContactRole role) {
        Guard.require(creditorId != null, "dispute_contact.creditor_required", "Credor obrigatório");
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.active = true;
        update(name, email, phone, role);
    }

    public void update(String name, String email, String phone, DisputeContactRole role) {
        Guard.require(name != null && !name.isBlank() && name.trim().length() <= 150, "dispute_contact.name_required",
                "Nome do contato obrigatório (até 150 caracteres)");
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        Guard.require(normalized.length() <= 200 && EMAIL.matcher(normalized).matches(), "dispute_contact.email_invalid",
                "E-mail do contato inválido");
        String phoneValue = phone == null || phone.isBlank() ? null : phone.trim();
        Guard.require(phoneValue == null || PHONE.matcher(phoneValue).matches(), "dispute_contact.phone_invalid",
                "Telefone do contato inválido");
        Guard.require(role != null, "dispute_contact.role_required", "Informe se é contato principal ou cópia");
        this.name = name.trim();
        this.email = normalized;
        this.phone = phoneValue;
        this.role = role;
    }

    public void deactivate() {
        Guard.requireState(active, "dispute_contact.inactive", "Contato já desativado");
        this.active = false;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public DisputeContactRole getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }
}
