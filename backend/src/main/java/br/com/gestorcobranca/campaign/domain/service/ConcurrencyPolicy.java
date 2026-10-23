package br.com.gestorcobranca.campaign.domain.service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Controle de concorrência entre campanhas: num mesmo grupo de exclusividade, um devedor tem no
 * máximo uma inscrição em andamento. Uma campanha de prioridade <b>maior</b> substitui a inscrição
 * em andamento (que encerra com {@code PREEMPTED}); empate ou prioridade menor deixa o devedor de
 * fora (supressão por concorrência, registrada no painel). Grupo nulo = sem exclusividade.
 */
public final class ConcurrencyPolicy {

    private ConcurrencyPolicy() {
    }

    /** Inscrição em andamento do devedor no grupo. */
    public record Running(UUID enrollmentId, UUID campaignId, int priority) {
    }

    public sealed interface Decision permits Enroll, Preempt, Suppress {
    }

    public record Enroll() implements Decision {
    }

    /** Encerrar as inscrições informadas (de menor prioridade) e inscrever. */
    public record Preempt(List<Running> preempted) implements Decision {
    }

    /** Não inscrever: a inscrição informada tem prioridade igual ou maior. */
    public record Suppress(Running blocking) implements Decision {
    }

    public static Decision decide(UUID campaignId, String group, int priority, List<Running> runningInGroup) {
        if (group == null || runningInGroup.isEmpty()) {
            return new Enroll();
        }
        List<Running> others = runningInGroup.stream().filter(r -> !Objects.equals(r.campaignId(), campaignId)).toList();
        if (others.size() < runningInGroup.size()) {
            // a própria campanha já trata o devedor (outro caso do mesmo devedor)
            return new Suppress(runningInGroup.stream().filter(r -> Objects.equals(r.campaignId(), campaignId))
                    .findFirst().orElseThrow());
        }
        Running strongest = others.stream().max(Comparator.comparingInt(Running::priority)).orElseThrow();
        if (strongest.priority() >= priority) {
            return new Suppress(strongest);
        }
        return new Preempt(others);
    }
}
