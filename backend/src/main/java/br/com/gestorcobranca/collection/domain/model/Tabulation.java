package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.Locale;

/**
 * Tabulação (resultado) de interação, configurável por assessoria. As flags determinam os
 * efeitos e as exigências do registro:
 * <ul>
 *   <li>{@code effectiveContact} — houve contato com o devedor (CPC); conta para os indicadores
 *       de contato e leva o caso a {@code IN_NEGOTIATION};</li>
 *   <li>{@code promise} — exige os dados de uma promessa de pagamento;</li>
 *   <li>{@code callback} — exige o agendamento de um retorno;</li>
 *   <li>{@code requiresNote} — exige observação.</li>
 * </ul>
 * O código é imutável (as interações guardam o código e as flags vigentes no registro);
 * tabulações não são apagadas, apenas desativadas.
 */
@Entity
@Table(name = "tabulation", schema = "collection")
public class Tabulation extends TenantScopedEntity {

    @Column(name = "code", nullable = false, length = 40, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "effective_contact", nullable = false)
    private boolean effectiveContact;

    @Column(name = "promise", nullable = false)
    private boolean promise;

    @Column(name = "callback", nullable = false)
    private boolean callback;

    @Column(name = "requires_note", nullable = false)
    private boolean requiresNote;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected Tabulation() {
    }

    public Tabulation(String code, Definition definition) {
        Guard.require(code != null && code.trim().matches("[A-Za-z0-9_]{2,40}"), "tabulation.code_invalid",
                "Código da tabulação deve ter de 2 a 40 letras, números ou '_'");
        this.code = code.trim().toUpperCase(Locale.ROOT);
        this.active = true;
        define(definition);
    }

    public record Definition(String name, boolean effectiveContact, boolean promise, boolean callback,
                             boolean requiresNote, int displayOrder) {
    }

    public void define(Definition d) {
        Guard.require(d.name() != null && !d.name().isBlank() && d.name().trim().length() <= 120,
                "tabulation.name_invalid", "Nome da tabulação obrigatório (até 120 caracteres)");
        Guard.require(!d.promise() || d.effectiveContact(), "tabulation.promise_requires_contact",
                "Promessa de pagamento pressupõe contato efetivo");
        Guard.require(!(d.promise() && d.callback()), "tabulation.promise_and_callback",
                "Tabulação não pode exigir promessa e retorno ao mesmo tempo");
        this.name = d.name().trim();
        this.effectiveContact = d.effectiveContact();
        this.promise = d.promise();
        this.callback = d.callback();
        this.requiresNote = d.requiresNote();
        this.displayOrder = d.displayOrder();
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public boolean isEffectiveContact() {
        return effectiveContact;
    }

    public boolean isPromise() {
        return promise;
    }

    public boolean isCallback() {
        return callback;
    }

    public boolean isRequiresNote() {
        return requiresNote;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public boolean isActive() {
        return active;
    }
}
