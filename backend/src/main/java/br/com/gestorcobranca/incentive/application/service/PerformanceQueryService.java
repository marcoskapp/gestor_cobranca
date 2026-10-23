package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.identity.application.port.in.WorkforceQueries.TeamStructure;
import br.com.gestorcobranca.incentive.application.port.in.PerformanceQueries;
import br.com.gestorcobranca.incentive.application.port.out.IndicatorReader;
import br.com.gestorcobranca.incentive.application.port.out.IndicatorReader.Dimension;
import br.com.gestorcobranca.incentive.application.port.out.IndicatorReader.Filter;
import br.com.gestorcobranca.incentive.application.port.out.IndicatorReader.QualityTotals;
import br.com.gestorcobranca.incentive.application.port.out.IndicatorReader.SafraTotals;
import br.com.gestorcobranca.incentive.application.port.out.IndicatorReader.Totals;
import br.com.gestorcobranca.incentive.domain.model.DelayBucket;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.security.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Indicadores do §2 com as fórmulas do desenho (contrato com painéis e planos). Taxas como
 * frações com 4 casas; valores com 2; tempos médios em segundos com 2.
 */
@Service
@Transactional(readOnly = true)
class PerformanceQueryService implements PerformanceQueries {

    private static final long MAX_RANGE_DAYS = 400;
    private static final BigDecimal HOUR = BigDecimal.valueOf(3600);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** Métricas cuja fonte ainda não publica eventos de integração (pendências documentadas no desenho). */
    static final List<String> UNAVAILABLE = List.of(
            "effort.callbacksKeptRate", "effort.handoffsReceived", "conversion.contactToProposal",
            "conversion.proposalToAgreement", "conversion.promisesKeptRate", "conversion.handoffResolution",
            "financial.expectedRecoveryRatio");

    private final IndicatorReader reader;
    private final IncentiveAccess access;
    private final AccessScope accessScope;
    private final Workforce workforce;

    PerformanceQueryService(IndicatorReader reader, IncentiveAccess access, AccessScope accessScope, Workforce workforce) {
        this.reader = reader;
        this.access = access;
        this.accessScope = accessScope;
        this.workforce = workforce;
    }

    @Override
    public IndicatorReport indicators(IndicatorQuery q) {
        Guard.require(q.from() != null && q.to() != null && !q.to().isBefore(q.from()), "performance.range_invalid",
                "Período inválido");
        Guard.require(ChronoUnit.DAYS.between(q.from(), q.to()) <= MAX_RANGE_DAYS, "performance.range_too_long",
                "Período máximo de 400 dias");
        String groupBy = q.groupBy() == null || q.groupBy().isBlank() ? "NONE" : q.groupBy().trim().toUpperCase();
        Guard.require(Set.of("NONE", "USER", "TEAM", "CREDITOR", "PORTFOLIO", "DELAY_BUCKET", "CHANNEL").contains(groupBy),
                "performance.group_by_invalid", "Agrupamento inválido");
        if (q.delayBucket() != null) {
            StatementService.enumOf(DelayBucket.class, q.delayBucket(), "performance.delay_bucket_invalid");
        }
        List<TeamStructure> teams = workforce.teams();
        Set<UUID> users = users(q, teams);
        Set<UUID> creditors = accessScope.restrictCreditors(q.creditorId() == null ? Set.of() : Set.of(q.creditorId()));
        if ((users != null && users.isEmpty()) || (!accessScope.hasAllCreditors() && creditors.isEmpty())
                || (q.creditorId() != null && creditors.isEmpty())) {
            return new IndicatorReport(q.from(), q.to(), groupBy, List.of(), UNAVAILABLE);
        }
        Filter filter = new Filter(q.from(), q.to(), users, creditors,
                q.portfolioId() == null ? Set.of() : Set.of(q.portfolioId()),
                q.delayBucket() == null ? Set.of() : Set.of(q.delayBucket().trim().toUpperCase()),
                q.channel() == null ? Set.of() : Set.of(q.channel().trim().toUpperCase()));
        List<IndicatorRow> rows = switch (groupBy) {
            case "TEAM" -> byTeam(filter, teams, q.teamId());
            case "USER" -> byUser(filter);
            case "NONE" -> List.of(row("ALL", single(reader.totals(filter, Dimension.NONE)),
                    single(reader.safra(filter, Dimension.NONE)), people(filter), filter));
            default -> byDimension(filter, Dimension.valueOf(groupBy));
        };
        return new IndicatorReport(q.from(), q.to(), groupBy, rows, UNAVAILABLE);
    }

    /** Usuários do filtro dentro do escopo (null = todos). */
    private Set<UUID> users(IndicatorQuery q, List<TeamStructure> teams) {
        Set<UUID> visible = access.visibleUsers();
        Set<UUID> users = visible == null ? null : new HashSet<>(visible);
        if (q.userId() != null) {
            access.requireVisible(q.userId(), "performance");
            users = new HashSet<>(Set.of(q.userId()));
        }
        if (q.teamId() != null) {
            Set<UUID> teamPeople = teams.stream().filter(t -> t.id().equals(q.teamId())).findFirst()
                    .map(PerformanceQueryService::peopleOf).orElse(Set.of());
            users = users == null ? new HashSet<>(teamPeople)
                    : users.stream().filter(teamPeople::contains).collect(Collectors.toCollection(HashSet::new));
        }
        return users;
    }

    private List<IndicatorRow> byUser(Filter filter) {
        Map<String, Totals> totals = index(reader.totals(filter, Dimension.USER), Totals::groupKey);
        Map<String, SafraTotals> safra = index(reader.safra(filter, Dimension.USER), SafraTotals::groupKey);
        Set<String> keys = new java.util.TreeSet<>(totals.keySet());
        keys.addAll(safra.keySet());
        List<IndicatorRow> rows = new ArrayList<>();
        for (String key : keys) {
            UUID user = UUID.fromString(key);
            Filter own = withUsers(filter, Set.of(user));
            rows.add(row(key, totals.get(key), safra.get(key), Set.of(user), own));
        }
        return rows;
    }

    private List<IndicatorRow> byTeam(Filter filter, List<TeamStructure> teams, UUID teamId) {
        Set<UUID> visibleTeams = access.visibleTeams();
        List<IndicatorRow> rows = new ArrayList<>();
        for (TeamStructure team : teams) {
            if ((teamId != null && !team.id().equals(teamId)) || (visibleTeams != null && !visibleTeams.contains(team.id()))) {
                continue;
            }
            Set<UUID> people = peopleOf(team);
            if (filter.userIds() != null) {
                people.retainAll(filter.userIds());
            }
            if (people.isEmpty()) {
                continue;
            }
            Filter teamFilter = withUsers(filter, people);
            rows.add(row(team.id().toString(), single(reader.totals(teamFilter, Dimension.NONE)),
                    single(reader.safra(teamFilter, Dimension.NONE)), people, teamFilter));
        }
        return rows;
    }

    private List<IndicatorRow> byDimension(Filter filter, Dimension dimension) {
        Map<String, Totals> totals = index(reader.totals(filter, dimension), Totals::groupKey);
        Map<String, SafraTotals> safra = index(reader.safra(filter, dimension), SafraTotals::groupKey);
        Set<String> keys = new java.util.TreeSet<>(totals.keySet());
        keys.addAll(safra.keySet());
        return keys.stream().map(k -> row(k, totals.get(k), safra.get(k), null, null)).toList();
    }

    /**
     * @param people     pessoas da linha (null quando a dimensão não é por pessoa: sem tempo logado,
     *                   taxa de recuperação e conduta)
     * @param rowFilter  filtro da linha para métricas por pessoa
     */
    private IndicatorRow row(String key, Totals t, SafraTotals s, Set<UUID> people, Filter rowFilter) {
        Totals totals = t == null ? emptyTotals(key) : t;
        Long logged = null;
        BigDecimal recoveryRate = null;
        QualityTotals conduct = null;
        BigDecimal recovered = nz(totals.accruedAmount()).subtract(nz(totals.reversedAmount()));
        if (people != null || (rowFilter != null && rowFilter.userIds() == null)) {
            Collection<UUID> who = people;
            logged = reader.loggedTime(rowFilter.from(), rowFilter.to(), who).stream()
                    .mapToLong(IndicatorReader.LoggedTime::seconds).sum();
            Instant start = rowFilter.from().atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
            if (who != null) {
                recoveryRate = ratio(recovered, reader.eligibleBalanceAt(who, start, rowFilter.creditorIds()));
            }
            conduct = sum(reader.quality(rowFilter.from(), rowFilter.to(), who));
        }
        BigDecimal hours = logged == null || logged == 0 ? null
                : BigDecimal.valueOf(logged).divide(HOUR, 6, RoundingMode.HALF_EVEN);
        Effort effort = new Effort(logged, logged == null ? null : ratio(totals.talkSeconds(), logged),
                totals.workedCases() == null ? 0 : totals.workedCases(), totals.attempts(), totals.effectiveContacts(),
                ratio(totals.effectiveContacts(), totals.attempts()), totals.answeredCalls(),
                average(BigDecimal.valueOf(totals.talkSeconds()), totals.answeredCalls()),
                totals.medianResponseSeconds(), null, null);
        Conversion conversion = new Conversion(totals.agreements(), null, null,
                ratio(totals.agreements(), totals.effectiveContacts()), null,
                hours == null ? null : BigDecimal.valueOf(totals.agreements()).divide(hours, 4, RoundingMode.HALF_EVEN), null);
        Financial financial = new Financial(money(totals.negotiatedAmount()), money(recovered), recoveryRate,
                hours == null ? null : recovered.divide(hours, 2, RoundingMode.HALF_EVEN),
                totals.firstInstallments() <= 0 ? null
                        : recovered.divide(BigDecimal.valueOf(totals.firstInstallments()), 2, RoundingMode.HALF_EVEN),
                ratio(nz(totals.discountAmount()), nz(totals.updatedBalance())),
                totals.envelopeCount() == 0 ? null : nz(totals.envelopeUsageSum())
                        .divide(BigDecimal.valueOf(totals.envelopeCount()).multiply(HUNDRED), 4, RoundingMode.HALF_EVEN),
                average(BigDecimal.valueOf(totals.concessionLevelSum()), totals.concessionCount()),
                ratio(nz(totals.downPaymentAmount()), nz(totals.agreementAmount())), null);
        long formalized = s == null ? 0 : s.formalized();
        Quality quality = new Quality(formalized, s == null ? null : ratio(s.broken30(), formalized),
                s == null ? null : ratio(s.broken60(), formalized), s == null ? null : ratio(s.broken90(), formalized),
                s == null ? null : ratio(s.firstPaid(), formalized), s == null ? null : ratio(s.renegotiated(), formalized),
                ratio(nz(totals.reversedAmount()), nz(totals.accruedAmount())),
                conduct == null ? null : conduct.upheldDisputes(), conduct == null ? null : conduct.complaints(),
                conduct == null ? null : conduct.complianceViolations(), conduct == null ? null : conduct.averageScore());
        return new IndicatorRow(key, effort, conversion, financial, quality);
    }

    private static QualityTotals sum(List<QualityTotals> rows) {
        long complaints = 0;
        long upheld = 0;
        long violations = 0;
        long severe = 0;
        long evaluations = 0;
        BigDecimal weighted = BigDecimal.ZERO;
        for (QualityTotals r : rows) {
            complaints += r.complaints();
            upheld += r.upheldDisputes();
            violations += r.complianceViolations();
            severe += r.severeViolations();
            evaluations += r.evaluations();
            if (r.averageScore() != null) {
                weighted = weighted.add(r.averageScore().multiply(BigDecimal.valueOf(r.evaluations())));
            }
        }
        return new QualityTotals(null, complaints, upheld, violations, severe, evaluations,
                evaluations == 0 ? null : weighted.divide(BigDecimal.valueOf(evaluations), 2, RoundingMode.HALF_EVEN));
    }

    private static Set<UUID> people(Filter filter) {
        return filter.userIds() == null ? null : new HashSet<>(filter.userIds());
    }

    private static Filter withUsers(Filter f, Collection<UUID> users) {
        return new Filter(f.from(), f.to(), users, f.creditorIds(), f.portfolioIds(), f.delayBuckets(), f.channels());
    }

    private static Set<UUID> peopleOf(TeamStructure team) {
        Set<UUID> people = new HashSet<>(team.memberIds());
        if (team.supervisorId() != null) {
            people.add(team.supervisorId());
        }
        return people;
    }

    private static <T> T single(List<T> rows) {
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static <T> Map<String, T> index(List<T> rows, Function<T, String> key) {
        return rows.stream().collect(Collectors.toMap(key, Function.identity()));
    }

    private static Totals emptyTotals(String key) {
        return new Totals(key, 0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, 0L, null);
    }

    private static BigDecimal ratio(long numerator, long denominator) {
        return denominator == 0 ? null
                : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return denominator == null || denominator.signum() == 0 ? null
                : numerator.divide(denominator, 4, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal average(BigDecimal sum, long count) {
        return count == 0 ? null : sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal money(BigDecimal value) {
        return nz(value).setScale(2, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
