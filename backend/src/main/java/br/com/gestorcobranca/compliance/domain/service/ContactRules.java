package br.com.gestorcobranca.compliance.domain.service;

import br.com.gestorcobranca.compliance.domain.model.ChannelRule;
import br.com.gestorcobranca.compliance.domain.model.ContactChannel;
import br.com.gestorcobranca.compliance.domain.model.ContactPolicy;
import br.com.gestorcobranca.compliance.domain.model.ContactRestriction;
import br.com.gestorcobranca.compliance.domain.model.ContactWindow;
import br.com.gestorcobranca.compliance.domain.model.DenialReason;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Regras do porteiro de contato (RB02, RB07), sem dependência de infraestrutura: recebe tudo o
 * que é preciso para decidir (restrições, regra do canal, janela, feriados, tentativas) e diz
 * se o contato é permitido agora e, se não for, por quê e a partir de quando poderá ser.
 * <p>
 * Convenções:
 * <ul>
 *   <li>horários, dias, feriados e limites são avaliados no fuso de operação;</li>
 *   <li>limite diário: tentativas no mesmo dia civil; limite semanal: na mesma semana civil
 *       (segunda a domingo);</li>
 *   <li>limites e intervalo contam as tentativas do devedor no canal em <em>todos</em> os
 *       credores — a proteção contra excesso de contato é do titular, não do credor;</li>
 *   <li>a política do credor prevalece sobre a da assessoria canal a canal; canal sem regra em
 *       nenhuma política só respeita a janela padrão da assessoria.</li>
 * </ul>
 */
public final class ContactRules {

    /** Limite de iterações ao procurar o próximo instante permitido (feriados encadeados etc.). */
    private static final int MAX_SEARCH_STEPS = 64;

    private final ZoneId zone;

    public ContactRules(ZoneId zone) {
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    /**
     * Situação a avaliar.
     *
     * @param debtorId          devedor a contatar
     * @param creditorId        credor em nome de quem se pretende contatar
     * @param channel           canal pretendido
     * @param contactValueHash  blind index do telefone/e-mail a usar (nulo se não informado)
     * @param at                instante pretendido
     * @param debtorAnonymized  dados pessoais do devedor eliminados
     * @param restrictions      restrições ativas do devedor e do valor de contato (filtradas aqui)
     * @param rule              regra efetiva do canal (nula = sem limites nem bloqueio de feriado)
     * @param window            janela efetiva (a da regra ou, sem ela, a da assessoria)
     * @param holidays          feriados cadastrados no horizonte avaliado
     * @param attempts          instantes das tentativas anteriores do devedor no canal (ao menos
     *                          as da semana civil corrente)
     */
    public record Situation(UUID debtorId, UUID creditorId, ContactChannel channel, String contactValueHash, Instant at,
                            boolean debtorAnonymized, Collection<ContactRestriction> restrictions, ChannelRule rule,
                            ContactWindow window, Set<LocalDate> holidays, Collection<Instant> attempts) {

        public Situation {
            Objects.requireNonNull(debtorId, "debtorId");
            Objects.requireNonNull(creditorId, "creditorId");
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(window, "window");
            restrictions = List.copyOf(restrictions);
            holidays = Set.copyOf(holidays);
            attempts = List.copyOf(attempts);
        }
    }

    /**
     * @param reasons       motivos de negação no instante pedido (vazio = permitido)
     * @param nextAllowedAt primeiro instante em que o contato passa a ser permitido; nulo se
     *                      permitido agora ou se o bloqueio não tem fim previsto
     */
    public record Decision(boolean allowed, List<DenialReason> reasons, Instant nextAllowedAt) {

        public Decision {
            reasons = List.copyOf(reasons);
        }
    }

    /**
     * Regra efetiva de um canal: a do credor, se a política vigente do credor tratar o canal;
     * senão a da assessoria.
     */
    public static Optional<ChannelRule> effectiveRule(Optional<ContactPolicy> creditorPolicy,
                                                      Optional<ContactPolicy> tenantPolicy, ContactChannel channel) {
        return creditorPolicy.flatMap(p -> p.ruleFor(channel)).or(() -> tenantPolicy.flatMap(p -> p.ruleFor(channel)));
    }

    /** Janela efetiva: a própria da regra ou, sem ela, a padrão da assessoria. */
    public static ContactWindow effectiveWindow(Optional<ChannelRule> rule, ContactWindow tenantWindow) {
        return rule.flatMap(ChannelRule::window).orElse(tenantWindow);
    }

    public Decision evaluate(Situation s) {
        if (s.debtorAnonymized()) {
            return new Decision(false, List.of(DenialReason.DEBTOR_ANONYMIZED), null);
        }
        Set<DenialReason> reasons = reasonsAt(s, s.at());
        if (reasons.isEmpty()) {
            return new Decision(true, List.of(), null);
        }
        return new Decision(false, new ArrayList<>(reasons), nextAllowed(s));
    }

    private Set<DenialReason> reasonsAt(Situation s, Instant at) {
        Set<DenialReason> reasons = EnumSet.noneOf(DenialReason.class);
        coveringAt(s, at).forEach(r -> reasons.add(r.denialReason()));

        LocalDateTime local = LocalDateTime.ofInstant(at, zone);
        Optional<LocalDate> openingDay = s.window().openingDate(local);
        if (openingDay.isEmpty()) {
            reasons.add(DenialReason.OUTSIDE_WINDOW);
        }
        ChannelRule rule = s.rule();
        if (rule == null) {
            return reasons;
        }
        LocalDate day = openingDay.orElse(local.toLocalDate());
        if (rule.isBlockOnHolidays() && s.holidays().contains(day)) {
            reasons.add(DenialReason.HOLIDAY);
        }
        if (rule.getMaxPerDay() != null && attemptsOnDay(s, local.toLocalDate()) >= rule.getMaxPerDay()) {
            reasons.add(DenialReason.DAILY_LIMIT);
        }
        if (rule.getMaxPerWeek() != null && attemptsInWeek(s, local.toLocalDate()) >= rule.getMaxPerWeek()) {
            reasons.add(DenialReason.WEEKLY_LIMIT);
        }
        if (rule.minInterval().isPresent()) {
            Optional<Instant> last = lastAttemptUpTo(s, at);
            if (last.isPresent() && last.get().plus(rule.minInterval().get()).isAfter(at)) {
                reasons.add(DenialReason.MIN_INTERVAL);
            }
        }
        return reasons;
    }

    /**
     * Avança o instante candidato até que todas as condições temporais sejam atendidas ao mesmo
     * tempo. Cada passo leva o candidato ao primeiro instante que satisfaz uma condição violada;
     * como nenhum passo retrocede, a busca termina quando o candidato não muda mais.
     */
    private Instant nextAllowed(Situation s) {
        Instant candidate = s.at();
        for (int step = 0; step < MAX_SEARCH_STEPS; step++) {
            Optional<Instant> next = advance(s, candidate);
            if (next.isEmpty()) {
                return null;
            }
            if (next.get().equals(candidate)) {
                return candidate;
            }
            candidate = next.get();
        }
        return null;
    }

    /** Próximo candidato a partir de {@code t}; vazio se houver bloqueio sem fim previsto. */
    private Optional<Instant> advance(Situation s, Instant t) {
        List<ContactRestriction> covering = coveringAt(s, t);
        if (!covering.isEmpty()) {
            if (covering.stream().anyMatch(r -> !r.isTemporary())) {
                return Optional.empty();
            }
            return Optional.of(covering.stream().map(ContactRestriction::getEndsAt).max(Instant::compareTo).orElseThrow());
        }
        LocalDateTime local = LocalDateTime.ofInstant(t, zone);
        LocalDateTime opening = s.window().nextOpening(local);
        if (!opening.equals(local)) {
            return Optional.of(toInstant(opening));
        }
        ChannelRule rule = s.rule();
        if (rule == null) {
            return Optional.of(t);
        }
        LocalDate openingDay = s.window().openingDate(local).orElseThrow();
        if (rule.isBlockOnHolidays() && s.holidays().contains(openingDay)) {
            return Optional.of(toInstant(s.window().firstOpeningAfter(openingDay)));
        }
        LocalDate day = local.toLocalDate();
        if (rule.getMaxPerDay() != null && attemptsOnDay(s, day) >= rule.getMaxPerDay()) {
            return Optional.of(toInstant(day.plusDays(1).atStartOfDay()));
        }
        if (rule.getMaxPerWeek() != null && attemptsInWeek(s, day) >= rule.getMaxPerWeek()) {
            return Optional.of(toInstant(day.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay()));
        }
        if (rule.minInterval().isPresent()) {
            Optional<Instant> last = lastAttemptUpTo(s, t);
            if (last.isPresent()) {
                Instant earliest = last.get().plus(rule.minInterval().get());
                if (earliest.isAfter(t)) {
                    return Optional.of(earliest);
                }
            }
        }
        return Optional.of(t);
    }

    private List<ContactRestriction> coveringAt(Situation s, Instant at) {
        return s.restrictions().stream()
                .filter(r -> r.isEffectiveAt(at) && r.covers(s.debtorId(), s.creditorId(), s.channel(), s.contactValueHash()))
                .toList();
    }

    private long attemptsOnDay(Situation s, LocalDate day) {
        return s.attempts().stream().filter(a -> localDate(a).equals(day)).count();
    }

    private long attemptsInWeek(Situation s, LocalDate day) {
        LocalDate monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate nextMonday = monday.plusWeeks(1);
        return s.attempts().stream().map(this::localDate)
                .filter(d -> !d.isBefore(monday) && d.isBefore(nextMonday))
                .count();
    }

    private Optional<Instant> lastAttemptUpTo(Situation s, Instant at) {
        return s.attempts().stream().filter(a -> !a.isAfter(at)).max(Instant::compareTo);
    }

    private LocalDate localDate(Instant instant) {
        return LocalDate.ofInstant(instant, zone);
    }

    private Instant toInstant(LocalDateTime local) {
        return local.atZone(zone).toInstant();
    }

    /** Início da semana civil (segunda-feira 00:00 local) que contém o instante. */
    public Instant startOfWeek(Instant at) {
        LocalDate monday = localDate(at).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return toInstant(monday.atStartOfDay());
    }
}
