package br.com.gestorcobranca.costing.domain.service;

import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.CostFact;
import br.com.gestorcobranca.costing.domain.model.CostWarningCode;
import br.com.gestorcobranca.costing.domain.model.Usage.LoggedTime;
import br.com.gestorcobranca.costing.domain.model.Usage.WorkSpan;
import br.com.gestorcobranca.costing.domain.service.WorkTimeline.PersonDay;
import br.com.gestorcobranca.costing.domain.service.WorkTimeline.Target;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Custo de pessoas (desenho §4, §4.1 e decisões §9 Q1/Q2):
 * <ul>
 *   <li><b>HUMAN_DIRECT</b> = tempo direto da pessoa no credor/carteira no dia × valor-hora da faixa vigente da pessoa
 *       no dia. O tempo direto vem da {@link WorkTimeline}: atendimentos simultâneos da mesma pessoa (inclusive de
 *       credores e canais diferentes) dividem o instante em partes iguais, e o total da pessoa no dia nunca passa da
 *       união dos trechos de trabalho — a mesma hora não é cobrada duas vezes;</li>
 *   <li><b>HUMAN_IDLE</b> = (tempo logado − tempo direto da pessoa no dia, nunca negativo) × valor-hora, rateado pelos
 *       credores/carteiras na proporção do tempo direto da mesma pessoa no mesmo dia. Sem tempo direto no dia, todo o
 *       tempo logado vira ociosidade <b>sem credor</b> (linha da assessoria, visível só a quem vê todos os
 *       credores).</li>
 * </ul>
 * Pessoa sem faixa vigente no dia: horas com custo zero e aviso {@code HOURS_WITHOUT_BAND} no mesmo
 * credor/carteira. A pessoa só existe aqui, em memória: os fatos não têm usuário.
 */
public final class HumanCost {

    static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);
    private static final MathContext MC = MathContext.DECIMAL64;

    private HumanCost() {
    }

    /**
     * @param spans  trechos de trabalho das pessoas (podem se sobrepor)
     * @param logged tempo logado por pessoa e dia
     * @param zone   fuso do dia de negócio
     */
    public static void apply(Collection<WorkSpan> spans, Collection<LoggedTime> logged, ZoneId zone, LocalDate from,
                             LocalDate to, RateBook rates, CostAccumulator acc) {
        Map<PersonDay, Map<Target, BigDecimal>> parts = WorkTimeline.split(spans, zone, from, to);
        Map<PersonDay, Long> loggedByPerson = new LinkedHashMap<>();
        for (LoggedTime l : logged) {
            loggedByPerson.merge(new PersonDay(l.day(), l.userId()), Math.max(0, l.seconds()), Long::sum);
        }
        for (Map.Entry<PersonDay, Map<Target, BigDecimal>> e : parts.entrySet()) {
            PersonDay pd = e.getKey();
            BigDecimal directTotal = WorkTimeline.total(e.getValue());
            if (directTotal.signum() <= 0) {
                continue;
            }
            Optional<BigDecimal> rate = rates.hourlyCost(pd.userId(), pd.day());
            BigDecimal idle = BigDecimal.valueOf(loggedByPerson.getOrDefault(pd, 0L)).subtract(directTotal)
                    .max(BigDecimal.ZERO);
            for (Map.Entry<Target, BigDecimal> p : e.getValue().entrySet()) {
                Target t = p.getKey();
                add(acc, pd.day(), t.creditorId(), t.portfolioId(), CostCategory.HUMAN_DIRECT, hours(p.getValue()),
                        rate);
                if (idle.signum() > 0) {
                    BigDecimal idleSeconds = idle.multiply(p.getValue()).divide(directTotal, MC);
                    add(acc, pd.day(), t.creditorId(), t.portfolioId(), CostCategory.HUMAN_IDLE, hours(idleSeconds),
                            rate);
                }
            }
        }
        // logado sem nenhum tempo direto no dia: ociosidade sem credor
        loggedByPerson.forEach((pd, seconds) -> {
            if (seconds > 0 && !parts.containsKey(pd) && !pd.day().isBefore(from) && !pd.day().isAfter(to)) {
                add(acc, pd.day(), null, null, CostCategory.HUMAN_IDLE, hours(BigDecimal.valueOf(seconds)),
                        rates.hourlyCost(pd.userId(), pd.day()));
            }
        });
    }

    private static void add(CostAccumulator acc, LocalDate day, UUID creditorId, UUID portfolioId, CostCategory category,
                            BigDecimal hours, Optional<BigDecimal> rate) {
        acc.add(day, creditorId, portfolioId, category, rate.map(hours::multiply).orElse(BigDecimal.ZERO), hours,
                CostFact.Unit.HOURS);
        if (rate.isEmpty()) {
            acc.warn(day, creditorId, portfolioId, CostWarningCode.HOURS_WITHOUT_BAND, hours);
        }
    }

    static BigDecimal hours(BigDecimal seconds) {
        return seconds.divide(SECONDS_PER_HOUR, MC);
    }
}
