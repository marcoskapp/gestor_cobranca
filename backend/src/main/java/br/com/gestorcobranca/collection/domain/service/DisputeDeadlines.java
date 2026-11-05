package br.com.gestorcobranca.collection.domain.service;

import br.com.gestorcobranca.collection.domain.model.DeadlineSource;
import br.com.gestorcobranca.collection.domain.model.DisputeDeadlineRule;
import br.com.gestorcobranca.collection.domain.model.DisputeType;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * Prazos de uma contestação na abertura (regra pura). Dois relógios independentes:
 * <ul>
 *   <li><b>resposta ao devedor</b> (prazo externo, compromisso com o consumidor): nunca é suspenso;</li>
 *   <li><b>prazo interno</b> (SLA da equipe), sempre até o prazo de resposta.</li>
 * </ul>
 * O tempo de espera pelo credor é acompanhado à parte, no prazo pedido a ele em cada pedido. Hierarquia: prazo
 * informado na abertura → regra do credor para o tipo → regra do credor → regra do tipo → padrão da assessoria.
 */
public final class DisputeDeadlines {

    private DisputeDeadlines() {
    }

    /**
     * @param responseDue    data do prazo de resposta ao devedor
     * @param internalDue    data do prazo interno (≤ resposta)
     * @param responseDays   dias corridos do prazo de resposta
     * @param internalDays   dias corridos do prazo interno
     */
    public record Deadlines(LocalDate responseDue, LocalDate internalDue, int responseDays, int internalDays,
                            DeadlineSource source) {
    }

    /**
     * @param informedResponseDue prazo de resposta informado (nulo = pelas regras)
     * @param rules               regras ativas da assessoria
     * @param defaultResponseDays padrão da assessoria para a resposta ao devedor
     * @param defaultInternalDays padrão da assessoria para o prazo interno
     */
    public static Deadlines resolve(LocalDate today, UUID creditorId, DisputeType type, LocalDate informedResponseDue,
                                    Collection<DisputeDeadlineRule> rules, int defaultResponseDays,
                                    int defaultInternalDays) {
        Optional<DisputeDeadlineRule> rule = rules.stream()
                .filter(DisputeDeadlineRule::isActive)
                .filter(r -> r.getCreditorId() == null || r.getCreditorId().equals(creditorId))
                .filter(r -> r.getType() == null || r.getType() == type)
                .min(Comparator.comparingInt(r -> rank(r.source())));
        int responseDays = rule.map(DisputeDeadlineRule::getResponseDays).orElse(defaultResponseDays);
        int internalDays = Math.min(responseDays, rule.map(DisputeDeadlineRule::getInternalDays).orElse(defaultInternalDays));
        if (informedResponseDue != null) {
            LocalDate internal = today.plusDays(internalDays);
            return new Deadlines(informedResponseDue, internal.isAfter(informedResponseDue) ? informedResponseDue : internal,
                    (int) java.time.temporal.ChronoUnit.DAYS.between(today, informedResponseDue), internalDays,
                    DeadlineSource.INFORMED);
        }
        DeadlineSource source = rule.map(DisputeDeadlineRule::source).orElse(DeadlineSource.TENANT_DEFAULT);
        return new Deadlines(today.plusDays(responseDays), today.plusDays(internalDays), responseDays, internalDays,
                source);
    }

    private static int rank(DeadlineSource source) {
        return switch (source) {
            case CREDITOR_TYPE -> 0;
            case CREDITOR -> 1;
            case TYPE -> 2;
            default -> 3;
        };
    }
}
