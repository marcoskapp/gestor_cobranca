package br.com.gestorcobranca.complaint.domain.service;

import br.com.gestorcobranca.complaint.domain.model.ComplaintChannel;
import br.com.gestorcobranca.complaint.domain.model.ComplaintDeadlineRule;
import br.com.gestorcobranca.complaint.domain.model.DeadlineSource;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

/**
 * Prazo de resposta da reclamação: o informado pelo canal prevalece; senão a regra do canal; senão o padrão
 * ({@value #DEFAULT_DAYS} dias corridos do recebimento — decisão 1051, para revisão).
 */
public final class ComplaintDeadlines {

    public static final int DEFAULT_DAYS = 10;
    public static final int DEFAULT_WARNING_DAYS = 3;

    public record Resolved(LocalDate dueDate, DeadlineSource source, int warningDays) {
    }

    private ComplaintDeadlines() {
    }

    public static Resolved resolve(ComplaintChannel channel, LocalDate receivedOn, LocalDate informed,
                                   Collection<ComplaintDeadlineRule> rules) {
        Optional<ComplaintDeadlineRule> rule = rules.stream().filter(r -> r.getChannel() == channel).findFirst();
        int warning = rule.map(ComplaintDeadlineRule::getWarningDays).orElse(DEFAULT_WARNING_DAYS);
        if (informed != null) {
            if (informed.isBefore(receivedOn)) {
                throw new br.com.gestorcobranca.shared.domain.BusinessRuleException("complaint.due_before_received",
                        "O prazo informado não pode ser anterior ao recebimento");
            }
            return new Resolved(informed, DeadlineSource.INFORMED, warning);
        }
        return rule.map(r -> new Resolved(receivedOn.plusDays(r.getResponseDays()), DeadlineSource.CHANNEL_RULE,
                        r.getWarningDays()))
                .orElseGet(() -> new Resolved(receivedOn.plusDays(DEFAULT_DAYS), DeadlineSource.DEFAULT, warning));
    }
}
