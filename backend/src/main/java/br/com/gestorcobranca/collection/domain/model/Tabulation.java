package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A ação automática ({@link TabulationFollowUp}) executa o efeito do fato relatado na mesma
 * transação do registro: abrir contestação, bloquear o contato por falecimento ou invalidar o
 * contato usado. Toda ação exige observação (a justificativa fica com a interação) e não se combina
 * com promessa ou retorno; abrir contestação pressupõe contato efetivo (só o devedor contesta).
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

    @Enumerated(EnumType.STRING)
    @Column(name = "follow_up", nullable = false, length = 30)
    private TabulationFollowUp followUp = TabulationFollowUp.NONE;

    /** Tipo de contestação pré-selecionado (só com {@code OPEN_DISPUTE}; nulo = o operador escolhe). */
    @Enumerated(EnumType.STRING)
    @Column(name = "dispute_type", length = 30)
    private DisputeType disputeType;

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

    /**
     * @param followUp    ação automática (nulo = {@code NONE})
     * @param disputeType tipo de contestação pré-selecionado, só com {@code OPEN_DISPUTE}
     */
    public record Definition(String name, boolean effectiveContact, boolean promise, boolean callback,
                             boolean requiresNote, int displayOrder, TabulationFollowUp followUp,
                             DisputeType disputeType) {

        public Definition {
            followUp = followUp == null ? TabulationFollowUp.NONE : followUp;
        }

        /** Tabulação sem ação automática. */
        public Definition(String name, boolean effectiveContact, boolean promise, boolean callback,
                          boolean requiresNote, int displayOrder) {
            this(name, effectiveContact, promise, callback, requiresNote, displayOrder, TabulationFollowUp.NONE, null);
        }
    }

    public void define(Definition d) {
        Guard.require(d.name() != null && !d.name().isBlank() && d.name().trim().length() <= 120,
                "tabulation.name_invalid", "Nome da tabulação obrigatório (até 120 caracteres)");
        Guard.require(!d.promise() || d.effectiveContact(), "tabulation.promise_requires_contact",
                "Promessa de pagamento pressupõe contato efetivo");
        Guard.require(!(d.promise() && d.callback()), "tabulation.promise_and_callback",
                "Tabulação não pode exigir promessa e retorno ao mesmo tempo");
        if (!d.followUp().isNone()) {
            Guard.require(!d.promise() && !d.callback(), "tabulation.follow_up_with_commitment",
                    "Tabulação com ação automática não registra promessa nem retorno");
            Guard.require(d.requiresNote(), "tabulation.follow_up_requires_note",
                    "Tabulação com ação automática exige observação");
        }
        Guard.require(d.followUp() != TabulationFollowUp.OPEN_DISPUTE || d.effectiveContact(),
                "tabulation.dispute_requires_contact", "Abrir contestação pressupõe contato efetivo com o devedor");
        Guard.require(d.disputeType() == null || d.followUp() == TabulationFollowUp.OPEN_DISPUTE,
                "tabulation.dispute_type_not_allowed", "Tipo de contestação só se aplica à ação de abrir contestação");
        this.name = d.name().trim();
        this.effectiveContact = d.effectiveContact();
        this.promise = d.promise();
        this.callback = d.callback();
        this.requiresNote = d.requiresNote();
        this.displayOrder = d.displayOrder();
        this.followUp = d.followUp();
        this.disputeType = d.disputeType();
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

    public TabulationFollowUp getFollowUp() {
        return followUp;
    }

    public DisputeType getDisputeType() {
        return disputeType;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public boolean isActive() {
        return active;
    }
}
