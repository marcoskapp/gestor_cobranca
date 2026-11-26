package br.com.gestorcobranca.analytics.application.service;

import br.com.gestorcobranca.analytics.application.port.in.creditor.CreditorIndicatorsQueries;
import br.com.gestorcobranca.analytics.application.port.out.CreditorIndicatorsReader;
import br.com.gestorcobranca.analytics.application.port.out.CreditorIndicatorsReader.AgreementStanding;
import br.com.gestorcobranca.analytics.application.port.out.CreditorIndicatorsReader.MonthTotals;
import br.com.gestorcobranca.analytics.application.port.out.CreditorIndicatorsReader.PortfolioTotals;
import br.com.gestorcobranca.analytics.application.port.out.CreditorIndicatorsReader.RecoveryTotals;
import br.com.gestorcobranca.analytics.domain.model.CreditorPeriod;
import br.com.gestorcobranca.analytics.domain.model.Ratio;
import br.com.gestorcobranca.analytics.domain.service.CreditorIndicatorRules;
import br.com.gestorcobranca.analytics.domain.service.CreditorIndicatorRules.PortfolioShare;
import br.com.gestorcobranca.analytics.domain.service.Indicators;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.persistence.CreditorPortalReadTimeout;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Indicadores do próprio credor (portal do credor, fatia 7). O credor vem do ator {@code CREDITOR} (outro tipo →
 * {@code denyActorType}, 403) com {@code CREDITOR_PORTAL_OVERVIEW}; não usa {@link AnalyticsAccess} nem as consultas
 * da assessoria. <b>Cache curto</b> por (assessoria, credor, período), consultado só depois da permissão: até 60 s e
 * enquanto não houver refresh novo dos snapshots.
 */
@Service
@Transactional(readOnly = true)
class CreditorIndicatorsService implements CreditorIndicatorsQueries {

    private static final String RESOURCE = "creditor_indicators";
    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final int CACHE_MAX_ENTRIES = 2_000;
    private static final int MONTHS = 6;

    private record CacheKey(UUID tenantId, UUID creditorId, CreditorPeriod period, LocalDate today) {
    }

    private record CacheEntry(Instant refreshedAt, Instant at, CreditorIndicators value) {
    }

    private final CreditorIndicatorsReader reader;
    private final CurrentActor currentActor;
    private final AccessScope accessScope;
    private final Clock clock;
    private final CreditorPortalReadTimeout readTimeout;
    private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    CreditorIndicatorsService(CreditorIndicatorsReader reader, CurrentActor currentActor, AccessScope accessScope,
                              Clock clock, CreditorPortalReadTimeout readTimeout) {
        this.reader = reader;
        this.currentActor = currentActor;
        this.accessScope = accessScope;
        this.clock = clock;
        this.readTimeout = readTimeout;
    }

    @Override
    public CreditorIndicators indicators(String period) {
        Actor actor = currentActor.get();
        if (!actor.isCreditor()) {
            throw accessScope.denyActorType(RESOURCE);
        }
        accessScope.requirePermission(Permission.CREDITOR_PORTAL_OVERVIEW);
        readTimeout.apply();
        UUID creditorId = actor.creditorScope().creditorIds().iterator().next();
        CreditorPeriod p = CreditorPeriod.parse(period);
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.ofInstant(now, TimeConfig.BUSINESS_ZONE);
        CacheKey key = new CacheKey(actor.tenantId(), creditorId, p, today);
        Instant refreshedAt = reader.lastRefreshedAt();
        CacheEntry hit = cache.get(key);
        if (hit != null && Objects.equals(hit.refreshedAt(), refreshedAt) && hit.at().plus(CACHE_TTL).isAfter(now)) {
            CreditorIndicators v = hit.value();
            return new CreditorIndicators(v.period(), v.from(), v.to(), v.previousFrom(), v.previousTo(), v.recovery(),
                    v.monthly(), v.byPortfolio(), v.balance(), v.agreements(), v.dataUntil(), true);
        }
        CreditorIndicators computed = compute(creditorId, p, today);
        if (cache.size() >= CACHE_MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(key, new CacheEntry(refreshedAt, now, computed));
        return computed;
    }

    private CreditorIndicators compute(UUID creditorId, CreditorPeriod period, LocalDate today) {
        CreditorPeriod.Range range = period.range(today);
        CreditorPeriod.Range previous = period.previous(today);
        RecoveryTotals current = reader.recovery(creditorId, range.from(), range.to());
        RecoveryTotals before = reader.recovery(creditorId, previous.from(), previous.to());
        CreditorRecovery recovery = new CreditorRecovery(net(current), creditorNet(current), net(before),
                creditorNet(before));

        // série fixa de 6 meses (o corrente incluído), com zero nos meses sem movimento
        YearMonth last = YearMonth.from(today);
        YearMonth first = last.minusMonths(MONTHS - 1L);
        Map<LocalDate, RecoveryTotals> byMonth = new java.util.HashMap<>();
        for (MonthTotals m : reader.recoveryByMonth(creditorId, first.atDay(1), today)) {
            byMonth.put(m.month(), m.totals());
        }
        List<CreditorMonth> monthly = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            RecoveryTotals t = byMonth.get(m.atDay(1));
            monthly.add(new CreditorMonth(m.toString(), t == null ? zero() : net(t), t == null ? zero() : creditorNet(t)));
        }

        List<PortfolioTotals> portfolios = reader.recoveryByPortfolio(creditorId, range.from(), range.to());
        Map<UUID, String> names = reader.portfolioNames(creditorId,
                portfolios.stream().map(PortfolioTotals::portfolioId).filter(Objects::nonNull).toList());
        List<CreditorPortfolioShare> byPortfolio = CreditorIndicatorRules.topWithOthers(portfolios.stream()
                        // carteira sem nome no credor (nunca deveria acontecer) vai para "Outros", sem rótulo de outro credor
                        .map(t -> new PortfolioShare(t.portfolioId() != null && names.containsKey(t.portfolioId())
                                ? t.portfolioId() : null, names.get(t.portfolioId()), scale(Indicators.net(t.gross(), t.reversed()))))
                        .toList())
                .stream().map(s -> new CreditorPortfolioShare(s.portfolioId(), s.name(), s.recovered(),
                        s.portfolioId() == null)).toList();

        Instant dataUntil = reader.dataUntil();
        LocalDate referenceDate = dataUntil == null ? null : LocalDate.ofInstant(dataUntil, TimeConfig.BUSINESS_ZONE);
        CreditorIndicatorsReader.BalanceTotals b = reader.balance(creditorId);
        CreditorBalance balance = new CreditorBalance(scale(b.amount()), b.titles(),
                b.referenceDate() != null ? b.referenceDate() : referenceDate);

        long active = 0;
        long onTrack = 0;
        for (AgreementStanding s : reader.activeAgreements(creditorId)) {
            active += s.agreements();
            if (CreditorIndicatorRules.onTrack(s.hasOverdueInstallment(), s.breakSuspended())) {
                onTrack += s.agreements();
            }
        }
        Ratio ratio = CreditorIndicatorRules.onTrackRatio(onTrack, active);
        CreditorAgreements agreements = new CreditorAgreements(onTrack, active, ratio.value(),
                CreditorIndicatorRules.onTrackAlert(ratio), referenceDate);

        return new CreditorIndicators(period.name(), range.from(), range.to(), previous.from(), previous.to(), recovery,
                List.copyOf(monthly), byPortfolio, balance, agreements, dataUntil, false);
    }

    private static BigDecimal net(RecoveryTotals t) {
        return scale(Indicators.net(t.gross(), t.reversed()));
    }

    private static BigDecimal creditorNet(RecoveryTotals t) {
        return scale(Indicators.net(t.creditorGross(), t.creditorReversed()));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_EVEN);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }
}
