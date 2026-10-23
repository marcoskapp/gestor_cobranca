package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * Item do checklist documental da triagem (contrato/título original, demonstrativo de débito,
 * notificação de constituição em mora, procuração, registro da garantia…). Imutável.
 */
@Embeddable
public class ChecklistItem {

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "label", nullable = false, length = 150)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ChecklistStatus status;

    @Column(name = "required", nullable = false)
    private boolean required;

    protected ChecklistItem() {
    }

    /**
     * @param code     código estável do documento (ex.: {@code CONTRACT}, {@code DEBT_STATEMENT})
     * @param required obrigatório para concluir pela viabilidade
     */
    public ChecklistItem(String code, String label, ChecklistStatus status, boolean required) {
        Guard.require(code != null && code.matches("[A-Z][A-Z0-9_]{1,39}"), "legal_viability.checklist_code_invalid",
                "Código do item do checklist inválido (maiúsculas, dígitos e _)");
        Guard.require(label != null && !label.isBlank() && label.trim().length() <= 150,
                "legal_viability.checklist_label_invalid", "Descrição do item do checklist obrigatória (até 150)");
        Guard.require(status != null, "legal_viability.checklist_status_invalid", "Situação do item obrigatória");
        this.code = code;
        this.label = label.trim();
        this.status = status;
        this.required = required;
    }

    /** Item obrigatório faltante: impede concluir pela viabilidade. */
    public boolean blocking() {
        return required && status == ChecklistStatus.MISSING;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public ChecklistStatus getStatus() {
        return status;
    }

    public boolean isRequired() {
        return required;
    }
}
