package br.com.gestorcobranca.analytics.application.service;

import br.com.gestorcobranca.analytics.application.port.in.PolicyHealthQueries;
import br.com.gestorcobranca.analytics.application.port.out.PolicyHealthReader;
import br.com.gestorcobranca.analytics.domain.model.PolicySegment;
import br.com.gestorcobranca.analytics.domain.service.PolicyHealthRules;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Saúde da política (decisão 1087): junta acordos (quebra precoce, pago sobre acordado, uso do desconto) e portal
 * (consultas sem oferta, "nenhuma condição cabe") por carteira × faixa de atraso e aplica {@link PolicyHealthRules}.
 */
@Service
@Transactional(readOnly = true)
class PolicyHealthService implements PolicyHealthQueries {

    static final int DEFAULT_DAYS = 180;
    static final int MAX_DAYS = 400;
    private static final Set<String> VALUE_BANDS = Set.of("V0_1K", "V1K_5K", "V5K_20K", "V20K_PLUS");
    /** Ordem das faixas de atraso na tabela (do menor ao maior atraso). */
    private static final List<String> BUCKET_ORDER =
            List.of("NOT_DUE", "D0_30", "D31_90", "D91_180", "D181_360", "D361_PLUS", "UNKNOWN");

    private final PolicyHealthReader reader;
    private final AnalyticsAccess access;
    private final Clock clock;

    PolicyHealthService(PolicyHealthReader reader, AnalyticsAccess access, Clock clock) {
        this.reader = reader;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public PolicyHealthView health(PolicyHealthFilter filter) {
        access.requireAny("policy_health", Permission.POLICY_MANAGE, Permission.REPORT_VIEW);
        Guard.require(filter != null && filter.creditorId() != null, "policy_health.creditor_required",
                "Escolha o credor");
        Set<UUID> allowed = access.creditors(Set.of(filter.creditorId()));
        if (allowed != null && !allowed.contains(filter.creditorId())) {
            access.denyScope("policy_health", "creditor");
        }
        Guard.require(filter.valueBand() == null || VALUE_BANDS.contains(filter.valueBand()),
                "policy_health.value_band_invalid", "Faixa de valor inválida");
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.ofInstant(now, TimeConfig.BUSINESS_ZONE);
        LocalDate to = filter.to() == null ? today : filter.to();
        LocalDate from = filter.from() == null ? to.minusDays(DEFAULT_DAYS) : filter.from();
        Guard.require(!from.isAfter(to), "policy_health.range_invalid", "Período inválido");
        Guard.require(ChronoUnit.DAYS.between(from, to) <= MAX_DAYS, "policy_health.range_too_long",
                "Período máximo de " + MAX_DAYS + " dias");

        Map<String, PolicySegment> merged = new LinkedHashMap<>();
        reader.agreements(filter.creditorId(), from, to, filter.valueBand(), now).forEach(s -> merge(merged, s));
        reader.portal(filter.creditorId(), from.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant(),
                to.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant()).forEach(s -> merge(merged, s));
        List<PolicySegment> segments = merged.values().stream()
                .sorted(Comparator.comparing((PolicySegment s) -> String.valueOf(s.portfolioId()))
                        .thenComparingInt(s -> s.delayBucket() == null ? Integer.MAX_VALUE
                                : BUCKET_ORDER.indexOf(s.delayBucket())))
                .toList();
        PolicySegment total = segments.stream().reduce(PolicySegment.empty(null, null), PolicySegment::plus);
        return new PolicyHealthView(filter.creditorId(), from, to, filter.valueBand(), now, view(total),
                segments.stream().map(PolicyHealthService::view).toList(),
                PolicyHealthRules.suggestions(segments, total).stream()
                        .map(s -> new SuggestionView(s.kind().name(), s.portfolioId(), s.delayBucket())).toList(),
                PolicyHealthRules.MIN_AGREEMENTS, PolicyHealthRules.MIN_MATURE, PolicyHealthRules.MIN_PORTAL);
    }

    private static void merge(Map<String, PolicySegment> merged, PolicySegment s) {
        String key = Objects.toString(s.portfolioId()) + "|" + Objects.toString(s.delayBucket());
        merged.merge(key, s, PolicySegment::plus);
    }

    private static SegmentHealth view(PolicySegment s) {
        return new SegmentHealth(s.portfolioId(), s.delayBucket(), s.agreements(), s.matureAgreements(), s.brokenEarly(),
                s.earlyBreakRate(), s.agreedAmount(), s.paidAmount(), s.paidOverAgreed(), s.averageEnvelopeUsage(),
                s.highEnvelopeShare(), s.portalSimulations(), s.portalWithoutOffer(), s.portalWithoutOfferShare(),
                s.cannotAfford());
    }
}
