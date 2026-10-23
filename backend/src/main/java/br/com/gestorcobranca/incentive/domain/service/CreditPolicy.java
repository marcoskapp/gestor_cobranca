package br.com.gestorcobranca.incentive.domain.service;

import br.com.gestorcobranca.incentive.domain.model.attribution.AgreementOrigin;
import br.com.gestorcobranca.incentive.domain.model.attribution.AttributionCredit;
import br.com.gestorcobranca.incentive.domain.model.attribution.AttributionParticipant;
import br.com.gestorcobranca.incentive.domain.model.attribution.BeneficiaryType;
import br.com.gestorcobranca.incentive.domain.model.attribution.ParticipantRole;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules.AttributionRules;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.Percentage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolve os créditos de um acordo a partir da origem, dos participantes e das regras de
 * atribuição do plano (§3.2–§3.4):
 * <ul>
 *   <li>origens humanas (inclusive copiloto e handoff): crédito ao closer humano com o peso da origem;</li>
 *   <li>{@code ASSISTANT_AUTONOMOUS}: sem crédito individual;</li>
 *   <li>portal/campanha: crédito ao influenciador — último humano com contato efetivo no caso dentro
 *       da janela do plano (apurado pelo módulo) ou, na falta desse histórico, o informado no evento;</li>
 *   <li>equipe dona da fila: crédito de 100% para o pool, em qualquer origem (o plano decide no
 *       fechamento se o autônomo entra);</li>
 *   <li>renegociação de acordo do próprio beneficiário: fator {@code ownRenegotiationWeightPercent}.</li>
 * </ul>
 * Créditos de peso zero também são registrados (transparência e simulação de planos).
 */
public final class CreditPolicy {

    public static final String ORIGIN_WEIGHT = "ORIGIN_WEIGHT";
    public static final String INFLUENCE_WINDOW = "INFLUENCE_WINDOW";
    public static final String INFLUENCER_FROM_EVENT = "INFLUENCER_FROM_EVENT";
    public static final String TEAM_POOL = "TEAM_POOL";

    private CreditPolicy() {
    }

    /**
     * @param influencerInWindow último humano com contato efetivo dentro da janela, se apurado
     * @param replacedCloser     closer humano do acordo substituído (renegociação), se houver
     */
    public static List<AttributionCredit> resolve(AgreementOrigin origin, List<AttributionParticipant> participants,
                                                  UUID teamId, AttributionRules rules, Optional<UUID> influencerInWindow,
                                                  Optional<UUID> replacedCloser) {
        List<AttributionCredit> credits = new ArrayList<>();
        Percentage weight = Percentage.of(rules.weightOf(origin));
        if (origin.creditsCloser()) {
            humanWithRole(participants, ParticipantRole.CLOSER).ifPresent(closer -> credits.add(
                    individual(closer, ParticipantRole.CLOSER, weight, ORIGIN_WEIGHT, rules, replacedCloser)));
        } else if (origin.creditsInfluencer()) {
            if (influencerInWindow.isPresent()) {
                credits.add(individual(influencerInWindow.get(), ParticipantRole.INFLUENCER, weight, INFLUENCE_WINDOW, rules,
                        replacedCloser));
            } else {
                humanWithRole(participants, ParticipantRole.INFLUENCER).ifPresent(influencer -> credits.add(
                        individual(influencer, ParticipantRole.INFLUENCER, weight, INFLUENCER_FROM_EVENT, rules,
                                replacedCloser)));
            }
        }
        if (teamId != null) {
            credits.add(new AttributionCredit(BeneficiaryType.TEAM, teamId, null, Percentage.HUNDRED, Percentage.HUNDRED,
                    TEAM_POOL));
        }
        return credits;
    }

    private static AttributionCredit individual(UUID userId, ParticipantRole role, Percentage weight, String reason,
                                                AttributionRules rules, Optional<UUID> replacedCloser) {
        boolean ownRenegotiation = replacedCloser.map(userId::equals).orElse(false);
        Percentage factor = ownRenegotiation ? Percentage.of(rules.ownRenegotiationWeightPercent()) : Percentage.HUNDRED;
        return new AttributionCredit(BeneficiaryType.USER, userId, role, weight, factor,
                ownRenegotiation ? reason + "+OWN_RENEGOTIATION" : reason);
    }

    private static Optional<UUID> humanWithRole(List<AttributionParticipant> participants, ParticipantRole role) {
        return participants.stream().filter(p -> p.getRole() == role && p.isHuman())
                .map(AttributionParticipant::getActorId).findFirst();
    }

    /**
     * Valor negociado incremental de uma renegociação (§3.4): só o que excede o que ainda estava
     * em aberto (já creditado como negociado) no acordo substituído. Sem acordo substituído, o
     * valor integral.
     *
     * @param replacedAmount valor do acordo substituído (null = desconhecido → valor integral)
     * @param replacedPaid   pago no acordo substituído
     */
    public static Money incrementalAmount(Money agreementAmount, Money replacedAmount, Money replacedPaid) {
        if (replacedAmount == null) {
            return agreementAmount;
        }
        Money stillOpen = replacedAmount.minus(replacedPaid == null ? Money.ZERO : replacedPaid);
        Money incremental = agreementAmount.minus(stillOpen.isNegative() ? Money.ZERO : stillOpen);
        return incremental.isNegative() ? Money.ZERO : incremental.min(agreementAmount);
    }
}
