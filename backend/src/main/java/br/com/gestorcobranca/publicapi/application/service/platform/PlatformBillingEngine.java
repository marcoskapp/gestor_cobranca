package br.com.gestorcobranca.publicapi.application.service.platform;

import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorView;
import br.com.gestorcobranca.publicapi.application.PublicApiProperties;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.AllowanceUse;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.BreakdownView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.ChargeLineView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.CreatePolicyCommand;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.DailyPoint;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.PolicyTermsCommand;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.PolicyVersionView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.PolicyView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.PositionView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.ProjectionView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.StatementRef;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.StatementView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.TierChargeView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.TierView;
import br.com.gestorcobranca.publicapi.application.port.out.CsvWriter;
import br.com.gestorcobranca.publicapi.application.port.out.UsageStore;
import br.com.gestorcobranca.publicapi.domain.model.BillableUsage;
import br.com.gestorcobranca.publicapi.domain.model.IntegrationToken;
import br.com.gestorcobranca.publicapi.domain.model.Proration;
import br.com.gestorcobranca.publicapi.domain.model.UsageTotals;
import br.com.gestorcobranca.publicapi.domain.model.platform.PlatformBillingPolicy;
import br.com.gestorcobranca.publicapi.domain.model.platform.PlatformBillingPolicyVersion;
import br.com.gestorcobranca.publicapi.domain.model.platform.PlatformPriceTerms;
import br.com.gestorcobranca.publicapi.domain.model.platform.PlatformStatement;
import br.com.gestorcobranca.publicapi.domain.model.platform.VolumeTier;
import br.com.gestorcobranca.publicapi.domain.repository.IntegrationTokenRepository;
import br.com.gestorcobranca.publicapi.domain.repository.platform.PlatformBillingPolicyRepository;
import br.com.gestorcobranca.publicapi.domain.repository.platform.PlatformBillingPolicyVersionRepository;
import br.com.gestorcobranca.publicapi.domain.repository.platform.PlatformStatementRepository;
import br.com.gestorcobranca.publicapi.domain.service.BillingCycles;
import br.com.gestorcobranca.publicapi.domain.service.BillingCycles.Cycle;
import br.com.gestorcobranca.publicapi.domain.service.ChargeLines;
import br.com.gestorcobranca.publicapi.domain.service.UsageProjection;
import br.com.gestorcobranca.publicapi.domain.service.platform.PlatformPriceCalculator;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.CurrentActor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Motor do faturamento da plataforma à assessoria, sempre <strong>no tenant corrente</strong> (a assessoria cobrada):
 * política e versões, calendário dos ciclos, cálculo sobre o consumo de todos os tokens da assessoria, posição em
 * tempo real, fechamento e extratos. Não confere permissões: quem chama é o caso de uso da plataforma (depois de
 * conferir {@code PLATFORM_BILLING_*} e trocar o tenant da transação para a assessoria), o caso de uso da própria
 * assessoria (somente leitura) ou o fechamento automático (ator de sistema da assessoria).
 * <p>
 * <b>Calendário (o mesmo do credor, decisões 621 e 624, aplicado à política da plataforma — decisão 740):</b> os ciclos
 * começam no início da vigência; cada ciclo fecha no primeiro dia de fechamento (da versão que vale no início dele)
 * igual ou posterior e o último termina no fim da vigência; primeiro, último e de transição são proporcionais aos
 * dias. A versão de um ciclo é a registrada antes do início dele; sem nenhuma, a primeira versão, se registrada antes do
 * fim do ciclo (o primeiro cadastro vale já no ciclo em andamento). Ciclos encerrados antes do primeiro cadastro nunca
 * são cobrados (sem retroatividade). Ciclo encerrado gera extrato quando tem mensalidade maior que zero ou consumo.
 */
@Component
class PlatformBillingEngine {

    static final ZoneId ZONE = TimeConfig.BUSINESS_ZONE;
    static final String NO_POLICY = "NO_POLICY";
    private static final TypeReference<List<ChargeLineView>> LINES = new TypeReference<>() {
    };
    private static final TypeReference<List<BreakdownView>> BREAKDOWN = new TypeReference<>() {
    };
    private static final TypeReference<List<VolumeTier>> TIERS = new TypeReference<>() {
    };

    /** Origem do fechamento (tag da métrica e atributo do evento de negócio). */
    enum Trigger { SCHEDULED, MANUAL, REVISION }

    private final PlatformBillingPolicyRepository policies;
    private final PlatformBillingPolicyVersionRepository versions;
    private final PlatformStatementRepository statements;
    private final IntegrationTokenRepository tokens;
    private final UsageStore usage;
    private final CreditorUseCases creditors;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final CurrentActor currentActor;
    private final JsonCodec json;
    private final CsvWriter csv;
    private final PublicApiProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    PlatformBillingEngine(PlatformBillingPolicyRepository policies, PlatformBillingPolicyVersionRepository versions,
                          PlatformStatementRepository statements, IntegrationTokenRepository tokens, UsageStore usage,
                          CreditorUseCases creditors, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                          CurrentActor currentActor, JsonCodec json, CsvWriter csv, PublicApiProperties properties,
                          MeterRegistry meterRegistry, Clock clock) {
        this.policies = policies;
        this.versions = versions;
        this.statements = statements;
        this.tokens = tokens;
        this.usage = usage;
        this.creditors = creditors;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.currentActor = currentActor;
        this.json = json;
        this.csv = csv;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    // ================================================================== política

    /** Política e versões em ordem crescente (lidas uma vez por operação). */
    private record State(PlatformBillingPolicy policy, List<PlatformBillingPolicyVersion> versions) {

        PlatformBillingPolicyVersion first() {
            return versions.getFirst();
        }
    }

    private Optional<State> state(boolean forUpdate) {
        Optional<PlatformBillingPolicy> policy = forUpdate ? policies.findCurrentForUpdate() : policies.findCurrent();
        return policy.map(p -> new State(p, versions.findByPolicyIdOrderByPolicyVersionAsc(p.getId())))
                .filter(s -> !s.versions().isEmpty());
    }

    PolicyView policyView() {
        UUID tenantId = currentActor.tenantId();
        Optional<State> st = state(false);
        boolean hasStatements = statements.existsAny();
        if (st.isEmpty()) {
            return new PolicyView(tenantId, null, null, null, List.of(), hasStatements);
        }
        Instant now = Instant.now(clock);
        Pending current = currentCycle(st, now, LocalDate.ofInstant(now, ZONE));
        UUID currentVersion = current.version().map(PlatformBillingPolicyVersion::getId).orElse(null);
        List<PolicyVersionView> list = st.get().versions().reversed().stream()
                .map(v -> versionView(v, v.getId().equals(currentVersion))).toList();
        PlatformBillingPolicy p = st.get().policy();
        return new PolicyView(tenantId, p.getId(), p.getStartDate(), p.getEndDate(), list, hasStatements);
    }

    PolicyView createPolicy(CreatePolicyCommand command, UUID requestedBy) {
        Guard.require(command != null && command.terms() != null, "platform_policy.terms_required",
                "Condições obrigatórias");
        if (policies.findCurrentForUpdate().isPresent()) {
            throw new ConflictException("platform_policy.already_exists",
                    "A assessoria já tem política: registre uma nova versão");
        }
        Instant now = Instant.now(clock);
        PlatformBillingPolicy policy = policies.save(new PlatformBillingPolicy(command.startDate(), command.endDate(),
                LocalDate.ofInstant(now, ZONE)));
        PlatformBillingPolicyVersion v1 = register(policy, 1, command.terms(), command.reason(), requestedBy, now);
        auditTrail.record(AuditEntry.of("platform_billing.policy_created", "platform_billing_policy", policy.getId())
                .reason(v1.getReason())
                .created("startDate", policy.getStartDate()).created("endDate", policy.getEndDate())
                .created("version", 1).created("terms", termsAudit(v1))
                .meta("requestedByPlatformUserId", requestedBy));
        return policyView();
    }

    PolicyView addVersion(PolicyTermsCommand terms, String reason, UUID requestedBy) {
        State st = state(true).orElseThrow(() -> new BusinessRuleException("platform_policy.not_found",
                "A assessoria ainda não tem política de cobrança da plataforma"));
        Instant now = Instant.now(clock);
        int next = st.versions().getLast().getPolicyVersion() + 1;
        PlatformBillingPolicyVersion previous = st.versions().getLast();
        PlatformBillingPolicyVersion created = register(st.policy(), next, terms, reason, requestedBy, now);
        auditTrail.record(AuditEntry.of("platform_billing.policy_version_added", "platform_billing_policy",
                        st.policy().getId())
                .reason(created.getReason())
                .change("terms", termsAudit(previous), termsAudit(created))
                .created("version", next)
                .meta("requestedByPlatformUserId", requestedBy));
        return policyView();
    }

    PolicyView changeTerm(LocalDate startDate, LocalDate endDate, String reason, UUID requestedBy) {
        State st = state(true).orElseThrow(() -> new BusinessRuleException("platform_policy.not_found",
                "A assessoria ainda não tem política de cobrança da plataforma"));
        Guard.require(reason != null && !reason.isBlank(), "platform_policy.reason_required",
                "Informe o motivo da alteração da vigência");
        PlatformBillingPolicy policy = st.policy();
        LocalDate previousStart = policy.getStartDate();
        LocalDate previousEnd = policy.getEndDate();
        Optional<PlatformStatement> last = statements.findLastCycle();
        // o fim não pode cortar um ciclo já fechado (sem cobrança retroativa)
        Guard.require(endDate == null || last.isEmpty() || !endDate.isBefore(last.get().getPeriodEnd()),
                "platform_policy.end_before_closed", "O fim da vigência não pode ser anterior a um ciclo já fechado");
        policy.changeTerm(startDate, endDate, last.isPresent(), LocalDate.now(clock.withZone(ZONE)));
        policies.save(policy);
        auditTrail.record(AuditEntry.of("platform_billing.policy_term_changed", "platform_billing_policy", policy.getId())
                .reason(reason.strip())
                .change("startDate", previousStart, policy.getStartDate())
                .change("endDate", previousEnd, policy.getEndDate())
                .meta("requestedByPlatformUserId", requestedBy));
        return policyView();
    }

    private PlatformBillingPolicyVersion register(PlatformBillingPolicy policy, int number, PolicyTermsCommand c,
                                                  String reason, UUID requestedBy, Instant now) {
        Guard.require(c != null, "platform_policy.terms_required", "Condições obrigatórias");
        List<VolumeTier> tiers = c.requestTiers() == null ? List.of()
                : c.requestTiers().stream().map(t -> t == null ? null : new VolumeTier(t.upTo(), t.unitPrice())).toList();
        PlatformPriceTerms terms = new PlatformPriceTerms(c.closingDay(), c.monthlyFee(), c.includedRequests(),
                c.pricePerRequest(), tiers, c.includedMbIn(), c.pricePerMbIn(), c.includedMbOut(), c.pricePerMbOut(),
                c.includedRecords(), c.pricePerRecord());
        PlatformBillingPolicyVersion v = versions.save(new PlatformBillingPolicyVersion(policy.getId(), number, terms,
                json.toJson(terms.requestTiers()), now, requestedBy, reason));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PLATFORM_BILLING_POLICY_DEFINED)
                .reference("platform_billing_policy", policy.getId()).amount(terms.monthlyFee())
                .attr("policyVersion", number).attr("closingDay", terms.closingDay())
                .attr("tiered", terms.tieredRequests()).attr("tiers", terms.requestTiers().size())
                .attr("includedRequests", terms.includedRequests()).attr("includedMbIn", terms.includedMbIn())
                .attr("includedMbOut", terms.includedMbOut()).attr("includedRecords", terms.includedRecords())
                .attr("startDate", policy.getStartDate().toString()));
        return v;
    }

    private Map<String, Object> termsAudit(PlatformBillingPolicyVersion v) {
        PlatformPriceTerms t = terms(v);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("closingDay", t.closingDay());
        m.put("monthlyFee", t.monthlyFee());
        m.put("includedRequests", t.includedRequests());
        m.put("pricePerRequest", t.pricePerRequest());
        m.put("requestTiers", t.requestTiers().stream().map(x -> (x.upTo() == null ? "∞" : x.upTo()) + "@" + x.unitPrice())
                .collect(Collectors.joining(",")));
        m.put("includedMbIn", t.includedMbIn());
        m.put("pricePerMbIn", t.pricePerMbIn());
        m.put("includedMbOut", t.includedMbOut());
        m.put("pricePerMbOut", t.pricePerMbOut());
        m.put("includedRecords", t.includedRecords());
        m.put("pricePerRecord", t.pricePerRecord());
        return m;
    }

    private PlatformPriceTerms terms(PlatformBillingPolicyVersion v) {
        return v.terms(json.fromJson(v.getRequestTiers(), TIERS));
    }

    private PolicyVersionView versionView(PlatformBillingPolicyVersion v, boolean current) {
        PlatformPriceTerms t = terms(v);
        return new PolicyVersionView(v.getId(), v.getPolicyVersion(), t.closingDay(), t.monthlyFee(),
                t.includedRequests(), t.pricePerRequest(),
                t.requestTiers().stream().map(x -> new TierView(x.upTo(), x.unitPrice())).toList(), t.includedMbIn(),
                t.pricePerMbIn(), t.includedMbOut(), t.pricePerMbOut(), t.includedRecords(), t.pricePerRecord(),
                v.getRegisteredAt(), v.getRegisteredBy(), v.getReason(), current);
    }

    // ================================================================== ciclos

    /** Ciclo, versão da política aplicada e tipo (REGULAR, POLICY_START, POLICY_END, TRANSITION ou NO_POLICY). */
    private record Pending(Cycle cycle, Optional<PlatformBillingPolicyVersion> version, String kind) {
    }

    private final class Calendar {

        private final State st;

        Calendar(State st) {
            this.st = st;
        }

        /** Versão registrada antes das 00:00 do dia (a mais recente). */
        Optional<PlatformBillingPolicyVersion> registeredBefore(LocalDate day) {
            Instant at = day.atStartOfDay(ZONE).toInstant();
            return st.versions().reversed().stream().filter(v -> v.getRegisteredAt().isBefore(at)).findFirst();
        }

        /** Versão do ciclo; sem nenhuma registrada antes do início, a primeira, se registrada antes do fim. */
        Optional<PlatformBillingPolicyVersion> versionFor(Cycle cycle) {
            return registeredBefore(cycle.start()).or(() -> Optional.of(st.first())
                    .filter(first -> first.getRegisteredAt().isBefore(cycle.to(ZONE))));
        }

        int closingDayAt(LocalDate start) {
            return registeredBefore(start).orElse(st.first()).getClosingDay();
        }

        boolean beyondEnd(LocalDate start) {
            LocalDate end = st.policy().getEndDate();
            return end != null && start.isAfter(end);
        }

        Pending startingAt(LocalDate start) {
            int closingDay = closingDayAt(start);
            Cycle cycle = BillingCycles.startingAt(start, closingDay, st.policy().getEndDate());
            String kind;
            if (!cycle.end().equals(BillingCycles.closingDateOnOrAfter(start, closingDay))) {
                kind = "POLICY_END";
            } else if (start.equals(st.policy().getStartDate())) {
                kind = "POLICY_START";
            } else {
                kind = BillingCycles.proration(cycle).isFull() ? "REGULAR" : "TRANSITION";
            }
            return new Pending(cycle, versionFor(cycle), kind);
        }

        /**
         * Primeiro ciclo cobrável: o primeiro do calendário da vigência que termina no dia do primeiro cadastro ou
         * depois (sem retroatividade); vazio se a vigência terminou antes do cadastro.
         */
        Optional<LocalDate> firstStart() {
            LocalDate since = LocalDate.ofInstant(st.first().getRegisteredAt(), ZONE);
            Cycle c = BillingCycles.firstEndingOnOrAfter(st.policy().getStartDate(), st.policy().getEndDate(),
                    st.first().getClosingDay(), since);
            return c.end().isBefore(since) ? Optional.empty() : Optional.of(c.start());
        }
    }

    /**
     * Próximo ciclo a fechar: o seguinte ao último extrato (ou o primeiro cobrável), pulando ciclos encerrados sem
     * mensalidade e sem consumo; vazio sem política, depois do fim da vigência ou antes de qualquer ciclo cobrável.
     */
    private Optional<Pending> pendingCycle(Calendar calendar, Instant now) {
        Optional<LocalDate> start = statements.findLastCycle().map(s -> s.getPeriodEnd().plusDays(1))
                .or(calendar::firstStart);
        if (start.isEmpty() || calendar.beyondEnd(start.get())) {
            return Optional.empty();
        }
        Pending candidate = calendar.startingAt(start.get());
        while (candidate.cycle().endedAt(now, ZONE) && !generatesStatement(candidate)) {
            LocalDate next = candidate.cycle().end().plusDays(1);
            if (calendar.beyondEnd(next)) {
                return Optional.empty();
            }
            candidate = calendar.startingAt(next);
        }
        return Optional.of(candidate);
    }

    private boolean generatesStatement(Pending p) {
        if (p.version().map(v -> v.getMonthlyFee().signum() > 0).orElse(false)) {
            return true;
        }
        Instant to = p.cycle().to(ZONE);
        return usage.firstTenantUsageAt(p.cycle().from(ZONE)).filter(t -> t.isBefore(to)).isPresent();
    }

    /**
     * Ciclo que contém hoje, coerente com o fechamento. Sem política (ou fora da vigência), o mês corrente sem valor —
     * até a véspera do início da vigência, quando ela começa no futuro.
     */
    private Pending currentCycle(Optional<State> st, Instant now, LocalDate today) {
        if (st.isEmpty()) {
            return noPolicy(today, null);
        }
        Calendar calendar = new Calendar(st.get());
        Optional<Pending> pending = pendingCycle(calendar, now);
        if (pending.isEmpty()) {
            return noPolicy(today, null);
        }
        if (pending.get().cycle().start().isAfter(today)) {
            return noPolicy(today, pending.get().cycle().start().minusDays(1));
        }
        Pending p = pending.get();
        while (p.cycle().end().isBefore(today)) {
            LocalDate next = p.cycle().end().plusDays(1);
            if (calendar.beyondEnd(next)) {
                return noPolicy(today, null);
            }
            p = calendar.startingAt(next);
        }
        return p;
    }

    private static Pending noPolicy(LocalDate today, LocalDate lastDay) {
        Cycle month = BillingCycles.containing(today, 31);
        return new Pending(BillingCycles.startingAt(month.start(), month.closingDay(), lastDay), Optional.empty(),
                NO_POLICY);
    }

    // ================================================================== cálculo

    private record Computed(UsageTotals totals, BillableUsage billable, Proration proration,
                            Optional<PlatformPriceCalculator.Result> result, List<ChargeLineView> lines,
                            List<BreakdownView> breakdown, int creditors, int tokens, String usageHash) {

        BigDecimal total() {
            return result.map(PlatformPriceCalculator.Result::total).orElse(BigDecimal.ZERO.setScale(2));
        }
    }

    /**
     * Consumo do ciclo de todos os tokens da assessoria (filtro sem credor nem token), MB arredondados por token e
     * direção (a soma dos tokens fecha com a soma dos extratos dos credores) e o detalhamento por credor, token e
     * operação.
     *
     * @param labels busca os nomes de credores e tokens (dispensável no consolidado)
     */
    private Computed compute(Pending pending, boolean labels) {
        Cycle cycle = pending.cycle();
        UsageStore.Filter filter = new UsageStore.Filter(cycle.from(ZONE), cycle.to(ZONE), null, null, null);
        UsageTotals totals = usage.totals(filter);
        List<UsageStore.TokenRow> byToken = usage.byToken(filter);
        BillableUsage billable = BillableUsage.sumOfTokens(byToken.stream().map(UsageStore.TokenRow::totals).toList());
        Proration proration = BillingCycles.proration(cycle);
        Optional<PlatformPriceCalculator.Result> result = pending.version()
                .map(v -> PlatformPriceCalculator.calculate(terms(v), billable, proration));
        List<ChargeLineView> lines = result.map(r -> r.lines().stream().map(PlatformBillingEngine::lineView).toList())
                .orElse(List.of());

        Map<UUID, BillableUsage> perCreditor = new TreeMap<>(Comparator.comparing(UUID::toString));
        Map<UUID, Long> perCreditorRequests = new TreeMap<>(Comparator.comparing(UUID::toString));
        for (UsageStore.TokenRow r : byToken) {
            perCreditor.merge(r.creditorId(), BillableUsage.ofToken(r.totals()), BillableUsage::plus);
            perCreditorRequests.merge(r.creditorId(), r.totals().requests(), Long::sum);
        }
        Map<UUID, String> creditorNames = labels ? creditors.getAll(perCreditor.keySet()).stream()
                .collect(Collectors.toMap(CreditorView::id, CreditorView::tradeName)) : Map.of();
        Map<UUID, IntegrationToken> tokenNames = labels ? tokens.findAllByIdIn(byToken.stream()
                .map(UsageStore.TokenRow::tokenId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(IntegrationToken::getId, Function.identity())) : Map.of();

        List<BreakdownView> breakdown = new ArrayList<>();
        perCreditor.forEach((creditorId, b) -> breakdown.add(new BreakdownView("CREDITOR", creditorId.toString(),
                creditorNames.get(creditorId), null, quantities(perCreditorRequests.get(creditorId), b))));
        byToken.stream().sorted(Comparator.comparing((UsageStore.TokenRow r) -> r.creditorId().toString())
                .thenComparing(r -> r.tokenId().toString())).forEach(r -> {
            IntegrationToken t = tokenNames.get(r.tokenId());
            breakdown.add(new BreakdownView("TOKEN", r.tokenId().toString(),
                    t == null ? null : t.getDisplayPrefix() + " · " + t.getName(), r.creditorId(),
                    quantities(r.totals().requests(), BillableUsage.ofToken(r.totals()))));
        });
        usage.byRoute(filter).stream().sorted(Comparator.comparing(r -> r.method() + " " + r.route())).forEach(r -> {
            Map<String, Long> m = new LinkedHashMap<>();
            m.put("requests", r.totals().requests());
            m.put("billableRequests", r.totals().billableRequests());
            m.put("records", r.totals().records());
            breakdown.add(new BreakdownView("ROUTE", r.method() + " " + r.route(), null, null, m));
        });

        // marca do consumo: só chaves e quantidades (nomes de credores e tokens podem mudar sem mudar o consumo)
        Map<String, Object> hashed = new LinkedHashMap<>();
        hashed.put("tenantId", String.valueOf(currentActor.tenantId()));
        hashed.put("periodStart", cycle.start().toString());
        hashed.put("periodEnd", cycle.end().toString());
        hashed.put("totals", totalsMap(totals, billable));
        hashed.put("breakdown", breakdown.stream().map(b -> List.of(b.kind(), b.key(),
                b.creditorId() == null ? "" : b.creditorId().toString(), b.totals())).toList());
        return new Computed(totals, billable, proration, result, lines, List.copyOf(breakdown), perCreditor.size(),
                byToken.size(), sha256(json.toJson(hashed)));
    }

    private static Map<String, Long> quantities(Long requests, BillableUsage b) {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("requests", requests == null ? 0L : requests);
        m.put("billableRequests", b.requests());
        m.put("billableMbIn", b.mbIn());
        m.put("billableMbOut", b.mbOut());
        m.put("records", b.records());
        return m;
    }

    private static Map<String, Long> totalsMap(UsageTotals t, BillableUsage b) {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("requests", t.requests());
        m.put("billableRequests", b.requests());
        m.put("throttled", t.throttled());
        m.put("clientErrors", t.clientErrors());
        m.put("serverErrors", t.serverErrors());
        m.put("billableMbIn", b.mbIn());
        m.put("billableMbOut", b.mbOut());
        m.put("records", b.records());
        return m;
    }

    private static ChargeLineView lineView(PlatformPriceCalculator.Line l) {
        return new ChargeLineView(l.metric(), l.quantity(), l.included(), l.billable(), l.unitPrice(), l.amount(),
                l.tiers().stream().map(t -> new TierChargeView(t.from(), t.to(), t.units(), t.unitPrice(), t.amount()))
                        .toList());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ================================================================== posição

    /**
     * Posição do ciclo em andamento.
     *
     * @param detailed com detalhamento (nomes), crescimento diário e linhas; sem ele (consolidado), só os totais
     */
    PositionView position(boolean detailed) {
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.ofInstant(now, ZONE);
        Optional<State> st = state(false);
        Pending current = currentCycle(st, now, today);
        Cycle cycle = current.cycle();
        Computed c = compute(current, detailed);
        int daysElapsed = (int) ChronoUnit.DAYS.between(cycle.start(), today) + 1;
        int daysRemaining = (int) ChronoUnit.DAYS.between(today, cycle.end());
        List<AllowanceUse> allowances = c.lines().stream()
                .filter(l -> !PlatformPriceCalculator.MONTHLY_FEE.equals(l.metric()))
                .map(l -> new AllowanceUse(l.metric(), l.quantity(), l.included(),
                        ChargeLines.allowancePercent(l.quantity(), l.included())))
                .toList();
        Optional<PlatformPriceTerms> terms = current.version().map(this::terms);
        BillableUsage projected = UsageProjection.dailyAverage(c.billable(), daysElapsed, daysRemaining);
        BigDecimal projectedAmount = terms.map(t -> PlatformPriceCalculator.calculate(t, projected, c.proration()).total())
                .orElse(BigDecimal.ZERO.setScale(2));
        ProjectionView projection = new ProjectionView(UsageProjection.METHOD, projected.requests(), projected.mbIn(),
                projected.mbOut(), projected.records(), projectedAmount);
        List<DailyPoint> daily = detailed ? growth(cycle, today, terms, c.proration()) : List.of();
        StatementRef last = statements.findLastCycle().map(PlatformBillingEngine::ref).orElse(null);
        Optional<Instant> updatedAt = detailed ? usage.lastUpdatedAt(new UsageStore.Filter(cycle.from(ZONE),
                cycle.to(ZONE), null, null, null)) : Optional.empty();
        long millis = Duration.between(now, Instant.now(clock)).toMillis();
        if (detailed) {
            Timer.builder("publicapi.platform_billing.position.duration").register(meterRegistry)
                    .record(Duration.ofMillis(millis));
        }
        return new PositionView(currentActor.tenantId(), current.version().isPresent(), cycle.start(), cycle.end(),
                cycle.closingDay(), c.proration().cycleDays(), c.proration().referenceDays(), current.kind(), today,
                daysElapsed, daysRemaining, current.version().map(PlatformBillingPolicyVersion::getPolicyVersion).orElse(null),
                current.version().map(PlatformBillingPolicyVersion::getRegisteredAt).orElse(null), c.totals().requests(),
                c.billable().requests(), c.billable().mbIn(), c.billable().mbOut(), c.billable().records(), c.creditors(),
                c.tokens(), c.total(), "BRL", detailed ? c.lines() : List.of(), detailed ? allowances : List.of(),
                detailed ? c.breakdown() : List.of(), daily, projection, last, updatedAt.orElse(null),
                properties.usageFlushInterval().toSeconds(), millis);
    }

    /** Acumulado diário do ciclo até hoje: MB arredondados por token sobre o acumulado, como no fechamento. */
    private List<DailyPoint> growth(Cycle cycle, LocalDate until, Optional<PlatformPriceTerms> terms,
                                    Proration proration) {
        LocalDate last = until.isAfter(cycle.end()) ? cycle.end() : until;
        if (last.isBefore(cycle.start())) {
            return List.of();
        }
        Map<LocalDate, List<UsageStore.DailyTokenRow>> byDay = usage.dailyByToken(new UsageStore.Filter(
                cycle.from(ZONE), cycle.to(ZONE), null, null, null), ZONE.getId()).stream()
                .collect(Collectors.groupingBy(UsageStore.DailyTokenRow::day));
        Map<UUID, UsageTotals> cumulative = new LinkedHashMap<>();
        List<DailyPoint> points = new ArrayList<>();
        int day = 1;
        for (LocalDate d = cycle.start(); !d.isAfter(last); d = d.plusDays(1), day++) {
            for (UsageStore.DailyTokenRow r : byDay.getOrDefault(d, List.of())) {
                cumulative.merge(r.tokenId(), r.totals(), UsageTotals::plus);
            }
            BillableUsage b = BillableUsage.sumOfTokens(cumulative.values());
            BigDecimal amount = terms.map(t -> PlatformPriceCalculator.calculate(t, b, proration).total())
                    .orElse(BigDecimal.ZERO.setScale(2));
            points.add(new DailyPoint(day, d, b.requests(), b.mbIn(), b.mbOut(), b.records(), amount));
        }
        return points;
    }

    // ================================================================== extratos

    StatementView preview() {
        Instant now = Instant.now(clock);
        State st = state(false).orElseThrow(() -> new BusinessRuleException("platform_policy.not_found",
                "A assessoria ainda não tem política de cobrança da plataforma"));
        Pending pending = pendingCycle(new Calendar(st), now).orElseThrow(() -> new BusinessRuleException(
                "platform_statement.nothing_to_close", "Não há ciclo a fechar para a assessoria"));
        Computed c = compute(pending, true);
        return new StatementView(null, currentActor.tenantId(), pending.cycle().start(), pending.cycle().end(),
                pending.cycle().endedAt(now, ZONE), 0, pending.cycle().closingDay(), c.proration().cycleDays(),
                c.proration().referenceDays(), pending.kind(),
                pending.version().map(PlatformBillingPolicyVersion::getPolicyVersion).orElse(0), c.totals().requests(),
                c.billable().requests(), c.billable().mbIn(), c.billable().mbOut(), c.billable().records(),
                c.creditors(), c.tokens(), c.total(), "BRL", c.lines(), c.breakdown(), c.usageHash(), null, null, null);
    }

    /**
     * Fecha o próximo ciclo encerrado ou gera nova revisão de um ciclo fechado. A política fica bloqueada até o fim da
     * transação: fechamentos simultâneos da mesma assessoria (manual e automático, instâncias diferentes) são feitos
     * um depois do outro, e o segundo encontra o ciclo já fechado (idempotência).
     *
     * @param requestedBy usuário da plataforma; {@code null} no fechamento automático
     */
    StatementView close(LocalDate periodStart, String reason, UUID requestedBy) {
        Instant now = Instant.now(clock);
        State st = state(true).orElseThrow(() -> new BusinessRuleException("platform_policy.not_found",
                "A assessoria ainda não tem política de cobrança da plataforma"));
        Calendar calendar = new Calendar(st);
        if (periodStart != null) {
            Optional<PlatformStatement> existing = statements.findLatest(periodStart);
            if (existing.isPresent()) {
                if (reason == null || reason.isBlank()) {
                    throw new ConflictException("platform_statement.already_closed",
                            "Extrato do ciclo já fechado; informe o motivo para gerar nova revisão");
                }
                PlatformStatement previous = existing.get();
                // mesmo período e mesma versão (registrada antes do fim do ciclo): só o consumo pode mudar
                Cycle cycle = new Cycle(previous.getPeriodStart(), previous.getPeriodEnd(), previous.getClosingDay());
                Pending revision = new Pending(cycle, calendar.versionFor(cycle), previous.getCycleKind());
                return save(revision, previous, reason, requestedBy, Trigger.REVISION);
            }
        }
        Pending pending = pendingCycle(calendar, now).orElseThrow(() -> new BusinessRuleException(
                "platform_statement.nothing_to_close", "Não há ciclo a fechar para a assessoria"));
        if (periodStart != null && !periodStart.equals(pending.cycle().start())) {
            throw new BusinessRuleException("platform_statement.period_invalid",
                    "O próximo ciclo a fechar começa em " + pending.cycle().start());
        }
        Guard.require(pending.cycle().endedAt(now, ZONE), "platform_statement.period_open",
                "Só ciclos encerrados podem ser fechados");
        return save(pending, null, reason, requestedBy, Trigger.MANUAL);
    }

    /** Fecha, em ordem, todos os ciclos encerrados ainda sem extrato (fechamento automático). */
    int closeEndedCycles() {
        Instant now = Instant.now(clock);
        Optional<State> st = state(true);
        if (st.isEmpty()) {
            return 0;
        }
        Calendar calendar = new Calendar(st.get());
        int closed = 0;
        Optional<Pending> pending = pendingCycle(calendar, now);
        while (pending.isPresent() && pending.get().cycle().endedAt(now, ZONE)) {
            save(pending.get(), null, null, null, Trigger.SCHEDULED);
            closed++;
            pending = pendingCycle(calendar, now);
        }
        return closed;
    }

    private StatementView save(Pending pending, PlatformStatement previous, String reason, UUID requestedBy,
                               Trigger trigger) {
        PlatformBillingPolicyVersion version = pending.version().orElseThrow(() -> new BusinessRuleException(
                "platform_statement.no_policy_version", "Não há versão da política para o ciclo"));
        Computed c = compute(pending, true);
        Cycle cycle = pending.cycle();
        int revision = previous == null ? 1 : previous.getRevision() + 1;
        PlatformStatement statement = statements.save(new PlatformStatement(cycle.start(), cycle.end(), revision,
                cycle.closingDay(), c.proration(), pending.kind(),
                new PlatformStatement.AppliedPolicy(version.getPolicyId(), version.getId(), version.getPolicyVersion()),
                new PlatformStatement.Quantities(c.totals().requests(), c.billable(), c.creditors(), c.tokens()),
                c.total(), json.toJson(c.lines()), json.toJson(c.breakdown()), c.usageHash(), Instant.now(clock),
                requestedBy, reason));
        String period = cycle.start() + "/" + cycle.end();
        auditTrail.record(AuditEntry.of(revision == 1 ? "platform_statement.closed" : "platform_statement.reclosed",
                        "platform_statement", statement.getId()).reason(statement.getReason())
                .created("period", period).created("revision", revision)
                .created("totalAmount", statement.getTotalAmount()).created("usageHash", c.usageHash())
                .meta("policyVersion", version.getPolicyVersion()).meta("trigger", trigger.name())
                .meta("previousStatementId", previous == null ? null : previous.getId())
                .meta("requestedByPlatformUserId", requestedBy));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.PLATFORM_BILLING_STATEMENT_CLOSED)
                .reference("platform_statement", statement.getId()).amount(statement.getTotalAmount())
                .attr("periodStart", cycle.start().toString()).attr("periodEnd", cycle.end().toString())
                .attr("closingDay", cycle.closingDay()).attr("revision", revision).attr("trigger", trigger.name())
                .attr("cycleKind", pending.kind()).attr("cycleDays", c.proration().cycleDays())
                .attr("referenceCycleDays", c.proration().referenceDays())
                .attr("policyVersion", version.getPolicyVersion()).attr("requests", c.totals().requests())
                .attr("billableRequests", c.billable().requests()).attr("clientErrors", c.totals().clientErrors())
                .attr("throttled", c.totals().throttled()).attr("serverErrors", c.totals().serverErrors())
                .attr("billableMbIn", c.billable().mbIn()).attr("billableMbOut", c.billable().mbOut())
                .attr("records", c.billable().records()).attr("creditors", c.creditors()).attr("tokens", c.tokens()));
        meterRegistry.counter("publicapi.platform_statements.closed", "trigger",
                trigger.name().toLowerCase(Locale.ROOT)).increment();
        return view(statement);
    }

    StatementView statement(UUID id) {
        return view(statements.findById(id).orElseThrow(() -> ResourceNotFoundException.of("platform_statement", id)));
    }

    PageResponse<StatementView> statements(YearMonth month, PageQuery page) {
        return PageResponse.of(statements.search(month == null ? null : month.atDay(1),
                month == null ? null : month.atEndOfMonth(),
                page.toPageable(Sort.by(Sort.Direction.DESC, "periodEnd").and(Sort.by(Sort.Direction.DESC, "revision")))),
                this::view);
    }

    /** Extrato em CSV (cabeçalho, linhas, faixas e detalhamento), com células neutralizadas contra fórmulas. */
    StatementView export(UUID id, OutputStream output) {
        StatementView s = statement(id);
        try (CsvWriter.Sheet sheet = csv.open(output, List.of("secao", "item", "descricao", "quantidade", "franquia",
                "cobravel", "preco_unitario", "valor"))) {
            sheet.row(List.of("extrato", "assessoria", s.tenantId(), "", "", "", "", ""));
            sheet.row(List.of("extrato", "ciclo", s.periodStart() + " a " + s.periodEnd(), "", "", "", "", ""));
            sheet.row(List.of("extrato", "dia_fechamento", s.closingDay(), "", "", "", "", ""));
            sheet.row(List.of("extrato", "dias", s.cycleDays() + " de " + s.referenceCycleDays(), "", "", "", "", ""));
            sheet.row(List.of("extrato", "revisao", s.revision(), "", "", "", "", ""));
            sheet.row(List.of("extrato", "politica", "política da plataforma v" + s.policyVersion(), "", "", "", "", ""));
            sheet.row(List.of("extrato", "base", "consumo de todos os tokens da assessoria; só chamadas 2xx/3xx",
                    "", "", "", "", ""));
            sheet.row(List.of("extrato", "unidade_trafego", "MB = 1.048.576 bytes, arredondado para cima por token",
                    "", "", "", "", ""));
            sheet.row(List.of("extrato", "hash_consumo", s.usageHash(), "", "", "", "", ""));
            for (ChargeLineView l : s.lines()) {
                sheet.row(List.of("linha", l.metric(), l.tiers().isEmpty() ? "" : "preço por faixa de volume",
                        l.quantity(), l.included(), l.billable(), l.unitPrice() == null ? "" : l.unitPrice(),
                        l.amount()));
                for (TierChargeView t : l.tiers()) {
                    sheet.row(List.of("faixa", l.metric(), t.from() + " a " + (t.to() == null ? "∞" : t.to()), "", "",
                            t.units(), t.unitPrice(), t.amount()));
                }
            }
            sheet.row(List.of("total", "", "", "", "", "", "", s.totalAmount()));
            for (BreakdownView b : s.breakdown()) {
                Map<String, Long> t = b.totals();
                boolean traffic = !"ROUTE".equals(b.kind());
                sheet.row(List.of("detalhe_" + b.kind().toLowerCase(Locale.ROOT), b.key(),
                        b.label() == null ? "" : b.label(), t.getOrDefault("requests", 0L), "",
                        t.getOrDefault("billableRequests", 0L),
                        traffic ? t.getOrDefault("billableMbIn", 0L) + " MB / " + t.getOrDefault("billableMbOut", 0L)
                                + " MB" : "",
                        t.getOrDefault("records", 0L)));
            }
        }
        return s;
    }

    private StatementView view(PlatformStatement s) {
        Cycle cycle = new Cycle(s.getPeriodStart(), s.getPeriodEnd(), s.getClosingDay());
        return new StatementView(s.getId(), s.getTenantId(), s.getPeriodStart(), s.getPeriodEnd(),
                cycle.endedAt(Instant.now(clock), ZONE), s.getRevision(), s.getClosingDay(), s.getCycleDays(),
                s.getReferenceCycleDays(), s.getCycleKind(), s.getPolicyVersion(), s.getRequests(),
                s.getBillableRequests(), s.getBillableMbIn(), s.getBillableMbOut(), s.getRecords(), s.getCreditors(),
                s.getTokens(), s.getTotalAmount(), s.getCurrency(), json.fromJson(s.getLines(), LINES),
                json.fromJson(s.getBreakdown(), BREAKDOWN), s.getUsageHash(), s.getClosedAt(), s.getClosedBy(),
                s.getReason());
    }

    private static StatementRef ref(PlatformStatement s) {
        return new StatementRef(s.getId(), s.getPeriodStart(), s.getPeriodEnd(), s.getRevision(), s.getTotalAmount());
    }
}
