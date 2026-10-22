package br.com.gestorcobranca.organization.domain.model;

import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Empresa credora que contrata a assessoria para recuperar suas dívidas. Cada credor tem
 * contratos, carteiras, políticas de negociação e comissão próprias.
 */
@Entity
@Table(name = "creditor", schema = "organization")
public class Creditor extends TenantScopedEntity {

    @Column(name = "legal_name", nullable = false, length = 200)
    private String legalName;

    @Column(name = "trade_name", nullable = false, length = 200)
    private String tradeName;

    /** CNPJ (ou CPF, no caso de credor pessoa física) — dado empresarial, não sensível. */
    @Column(name = "document", nullable = false, length = 14)
    private String document;

    /** Código do credor no sistema de origem (integrações). */
    @Column(name = "external_code", length = 60)
    private String externalCode;

    @Column(name = "contact_email", length = 200)
    private String contactEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CreditorStatus status;

    protected Creditor() {
    }

    public Creditor(String legalName, String tradeName, String document, String externalCode, String contactEmail) {
        this.legalName = requireText(legalName, "creditor.legal_name_required");
        this.tradeName = tradeName == null || tradeName.isBlank() ? this.legalName : tradeName.trim();
        this.document = Document.parse(document).number();
        this.externalCode = blankToNull(externalCode);
        this.contactEmail = blankToNull(contactEmail);
        this.status = CreditorStatus.ACTIVE;
    }

    public void update(String legalName, String tradeName, String externalCode, String contactEmail) {
        this.legalName = requireText(legalName, "creditor.legal_name_required");
        this.tradeName = tradeName == null || tradeName.isBlank() ? this.legalName : tradeName.trim();
        this.externalCode = blankToNull(externalCode);
        this.contactEmail = blankToNull(contactEmail);
    }

    public void deactivate() {
        this.status = CreditorStatus.INACTIVE;
    }

    public void activate() {
        this.status = CreditorStatus.ACTIVE;
    }

    public boolean isActive() {
        return status == CreditorStatus.ACTIVE;
    }

    private static String requireText(String value, String code) {
        Guard.require(value != null && !value.isBlank(), code, "Campo obrigatório");
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public String getLegalName() {
        return legalName;
    }

    public String getTradeName() {
        return tradeName;
    }

    public String getDocument() {
        return document;
    }

    public String getExternalCode() {
        return externalCode;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public CreditorStatus getStatus() {
        return status;
    }
}
