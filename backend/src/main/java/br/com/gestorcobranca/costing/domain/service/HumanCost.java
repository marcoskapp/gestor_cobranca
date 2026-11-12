package br.com.gestorcobranca.costing.domain.service;

import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.CostFact;
import br.com.gestorcobranca.costing.domain.model.CostWarningCode;
import br.com.gestorcobranca.costing.domain.model.Usage.DirectTime;
import br.com.gestorcobranca.costing.domain.model.Usage.LoggedTime;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Custo de pessoas (desenho §4 e decisões §9 Q1/Q2):
 * <ul>
 *   <li><b>HUMAN_DIRECT</b> = tempo direto da pessoa no credor/carteira no dia × valor-hora da faixa vigente da pessoa
 *       no dia;</li>
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

    private record PersonDay(LocalDate day, UUID userId) {
    }

    private record Part(UUID creditorId, UUID portfolioId, long seconds) {
    }

    public static void apply(Collection<DirectTime> direct, Collection<LoggedTime> logged, RateBook rates,
                             CostAccumulator acc) {
        Map<PersonDay, List<Part>> parts = new LinkedHashMap<>();
        for (DirectTime d : direct) {
            if (d.seconds() > 0) {
                parts.computeIfAbsent(new PersonDay(d.day(), d.userId()), k -> new ArrayList<>())
                        .add(new Part(d.creditorId(), d.portfolioId(), d.seconds()));
            }
        }
        Map<PersonDay, Long> loggedByPerson = new LinkedHashMap<>();
        for (LoggedTime l : logged) {
            loggedByPerson.merge(new PersonDay(l.day(), l.userId()), Math.max(0, l.seconds()), Long::sum);
        }
        for (Map.Entry<PersonDay, List<Part>> e : parts.entrySet()) {
            PersonDay pd = e.getKey();
            Optional<BigDecimal> rate = rates.hourlyCost(pd.userId(), pd.day());
            long directTotal = e.getValue().stream().mapToLong(Part::seconds).sum();
            long idle = Math.max(0, loggedByPerson.getOrDefault(pd, 0L) - directTotal);
            for (Part p : e.getValue()) {
                BigDecimal directHours = hours(BigDecimal.valueOf(p.seconds()));
                add(acc, pd.day(), p.creditorId(), p.portfolioId(), CostCategory.HUMAN_DIRECT, directHours, rate);
                if (idle > 0) {
                    BigDecimal idleSeconds = BigDecimal.valueOf(idle).multiply(BigDecimal.valueOf(p.seconds()))
                            .divide(BigDecimal.valueOf(directTotal), MC);
                    add(acc, pd.day(), p.creditorId(), p.portfolioId(), CostCategory.HUMAN_IDLE, hours(idleSeconds),
                            rate);
                }
            }
        }
        // logado sem nenhum tempo direto no dia: ociosidade sem credor
        loggedByPerson.forEach((pd, seconds) -> {
            if (seconds > 0 && !parts.containsKey(pd)) {
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
