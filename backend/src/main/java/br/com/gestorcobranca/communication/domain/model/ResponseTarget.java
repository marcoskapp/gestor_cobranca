package br.com.gestorcobranca.communication.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Meta de primeira resposta da assessoria para um canal (SLA da caixa de entrada): a partir da
 * primeira mensagem do devedor sem resposta, a conversa entra em aviso após {@code warningMinutes}
 * e estoura após {@code breachMinutes} (minutos corridos). Sem registro, valem os padrões de
 * {@link br.com.gestorcobranca.communication.domain.service.ResponseTargets#DEFAULTS}.
 */
@Entity
@Table(name = "response_target", schema = "communication")
public class ResponseTarget extends TenantScopedEntity {

    /** Limite superior das metas (7 dias). */
    public static final int MAX_MINUTES = 7 * 24 * 60;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, updatable = false, length = 20)
    private ChannelType channel;

    @Column(name = "warning_minutes", nullable = false)
    private int warningMinutes;

    @Column(name = "breach_minutes", nullable = false)
    private int breachMinutes;

    protected ResponseTarget() {
    }

    public ResponseTarget(ChannelType channel, int warningMinutes, int breachMinutes) {
        Guard.require(channel != null, "response_target.channel_required", "Canal obrigatório");
        this.channel = channel;
        define(warningMinutes, breachMinutes);
    }

    public void define(int warningMinutes, int breachMinutes) {
        Guard.require(warningMinutes >= 1 && warningMinutes <= MAX_MINUTES, "response_target.warning_invalid",
                "Aviso entre 1 e %d minutos".formatted(MAX_MINUTES));
        Guard.require(breachMinutes >= 1 && breachMinutes <= MAX_MINUTES, "response_target.breach_invalid",
                "Limite entre 1 e %d minutos".formatted(MAX_MINUTES));
        Guard.require(warningMinutes < breachMinutes, "response_target.warning_after_breach",
                "O aviso precisa vir antes do limite");
        this.warningMinutes = warningMinutes;
        this.breachMinutes = breachMinutes;
    }

    public ChannelType getChannel() {
        return channel;
    }

    public int getWarningMinutes() {
        return warningMinutes;
    }

    public int getBreachMinutes() {
        return breachMinutes;
    }
}
