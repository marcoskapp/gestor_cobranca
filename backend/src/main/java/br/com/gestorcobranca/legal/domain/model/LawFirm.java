package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Escritório de advocacia parceiro. Seus advogados são usuários da assessoria vinculados como
 * membros ({@link LawFirmMember}) e só acessam os casos jurídicos atribuídos ao escritório (além
 * do escopo por credor). Escritório inativo: membros sem acesso e sem novas atribuições.
 */
@Entity
@Table(name = "law_firm", schema = "legal")
public class LawFirm extends TenantScopedEntity {

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "cnpj", nullable = false, length = 14, updatable = false)
    private String cnpj;

    @Column(name = "oab_registration", length = 40)
    private String oabRegistration;

    @Column(name = "contact_email", length = 150)
    private String contactEmail;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected LawFirm() {
    }

    public LawFirm(String name, String cnpj, String oabRegistration, String contactEmail) {
        Document document = Document.parse(cnpj);
        Guard.require(document.type() == Document.DocumentType.CNPJ, "law_firm.cnpj_invalid",
                "Escritório deve ser identificado por CNPJ");
        this.cnpj = document.number();
        this.active = true;
        describe(name, oabRegistration, contactEmail);
    }

    /** @return true se algo mudou */
    public boolean describe(String name, String oabRegistration, String contactEmail) {
        Guard.require(name != null && !name.isBlank() && name.trim().length() <= 150, "law_firm.name_required",
                "Nome do escritório obrigatório (até 150 caracteres)");
        String oab = oabRegistration == null || oabRegistration.isBlank() ? null : oabRegistration.trim();
        Guard.require(oab == null || oab.length() <= 40, "law_firm.oab_too_long", "Registro na OAB até 40 caracteres");
        String email = contactEmail == null || contactEmail.isBlank() ? null : contactEmail.trim().toLowerCase();
        Guard.require(email == null || (email.length() <= 150 && email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")),
                "law_firm.email_invalid", "E-mail de contato inválido");
        boolean changed = !name.trim().equals(this.name) || !java.util.Objects.equals(oab, this.oabRegistration)
                || !java.util.Objects.equals(email, this.contactEmail);
        this.name = name.trim();
        this.oabRegistration = oab;
        this.contactEmail = email;
        return changed;
    }

    public boolean setActive(boolean active) {
        boolean changed = this.active != active;
        this.active = active;
        return changed;
    }

    public String getName() {
        return name;
    }

    public String getCnpj() {
        return cnpj;
    }

    public String getOabRegistration() {
        return oabRegistration;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public boolean isActive() {
        return active;
    }
}
