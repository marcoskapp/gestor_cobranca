package br.com.gestorcobranca.complaint.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Prazo de resposta por canal, configurado pela assessoria (dias corridos a partir do recebimento). Os prazos reais
 * variam por órgão, convênio e credor (ex.: o Procon informa o prazo na notificação), então o sistema só traz um
 * padrão editável (decisão G7, para revisão) e o prazo informado no registro sempre prevalece.
 */
@Entity
@Table(name = "complaint_deadline_rule", schema = "complaint")
public class ComplaintDeadlineRule extends TenantScopedEntity {

    public static final int MAX_DAYS = 120;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private ComplaintChannel channel;

    @Column(name = "response_days", nullable = false)
    private int responseDays;

    /** Dias antes do prazo em que a reclamação entra como "perto do prazo" na fila e em "Minhas pendências". */
    @Column(name = "warning_days", nullable = false)
    private int warningDays;

    protected ComplaintDeadlineRule() {
    }

    public ComplaintDeadlineRule(ComplaintChannel channel, int responseDays, int warningDays) {
        this.channel = Guard.requireNonNull(channel, "complaint.channel_required", "Canal obrigatório");
        change(responseDays, warningDays);
    }

    public void change(int days, int warning) {
        Guard.require(days >= 1 && days <= MAX_DAYS, "complaint.rule_days_invalid",
                "Prazo entre 1 e " + MAX_DAYS + " dias");
        Guard.require(warning >= 0 && warning < days, "complaint.rule_warning_invalid",
                "Aviso de prazo deve ser menor que o prazo");
        this.responseDays = days;
        this.warningDays = warning;
    }

    public ComplaintChannel getChannel() { return channel; }
    public int getResponseDays() { return responseDays; }
    public int getWarningDays() { return warningDays; }
}
