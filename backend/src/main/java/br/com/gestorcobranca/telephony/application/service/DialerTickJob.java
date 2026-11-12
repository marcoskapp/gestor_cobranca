package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactDecision;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ChannelContact;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ContactFingerprint;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.out.CallStatistics;
import br.com.gestorcobranca.telephony.application.port.out.CallStatistics.Counters;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.DialRequest;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.DialResult;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.HangupReason;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.ProviderContext;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.TransientProviderException;
import br.com.gestorcobranca.telephony.domain.model.Call;
import br.com.gestorcobranca.telephony.domain.model.CallOrigin;
import br.com.gestorcobranca.telephony.domain.model.DialerCampaign;
import br.com.gestorcobranca.telephony.domain.model.DialerEntry;
import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import br.com.gestorcobranca.telephony.domain.repository.AgentRepository;
import br.com.gestorcobranca.telephony.domain.repository.CallRepository;
import br.com.gestorcobranca.telephony.domain.repository.DialerCampaignRepository;
import br.com.gestorcobranca.telephony.domain.repository.DialerEntryRepository;
import br.com.gestorcobranca.telephony.domain.service.DialerPacing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Um ciclo do discador para uma campanha ativa ({@code telephony.dialer_tick}), em fases (handler sem
 * transação, decisão 852; TEL-01 e TEL-02). <b>Preparo</b>, numa transação curta sob lock da campanha:
 * <ol>
 *   <li>fora da janela própria da campanha → nada a fazer;</li>
 *   <li>ritmo ({@link DialerPacing}): operadores disponíveis na campanha, chamadas ainda sem
 *       operador e os contadores do dia (atendidas por pessoa, abandonadas, discadas);</li>
 *   <li>itens vencidos por prioridade ({@code SKIP LOCKED}): caso encerrado → excluído;
 *       inelegível ou atribuído a um operador (sem {@code includeAssigned}) → adiado/excluído;
 *       sem telefone → excluído; telefone escolhido por {@link #nextPhone}: número novo pendente primeiro,
 *       depois o seguinte ao último discado na ordem de prioridade dos contatos;</li>
 *   <li>{@code ContactGate#authorizeAndRecord} (origem {@code CAMPAIGN}) para cada item: negação
 *       com próximo instante permitido → adiado sem consumir tentativa; sem fim previsto → excluído;</li>
 *   <li>conta pelo roteamento ({@link AccountRouter}: campanha → carteira → credor → padrão);</li>
 *   <li>pedido de discagem gravado ({@code QUEUED → DIALING}, sem id do provedor) e confirmado antes do
 *       HTTP: os eventos e pedidos de instrução do provedor já encontram as chamadas.</li>
 * </ol>
 * <b>Provedor</b>, sem transação e sem lock: discagem em lote por conta ({@code dialBatch}). Falha
 * transitória que comprovadamente não criou as chamadas → o lote vai, uma vez, para a conta de
 * contingência da regra (desvio gravado em transação curta antes do novo pedido). <b>Resultado</b>, numa
 * transação curta por lote: aceite → id do provedor gravado; recusa → {@code FAILED} como não discada
 * (retentativa pela política). Nada desfaz o ciclo: lote que não pôde ser discado por falha transitória
 * (sem contingência ou com ela também indisponível) fica recusado com {@code PROVIDER_UNAVAILABLE}, e as
 * chamadas já aceitas dos outros lotes seguem registradas, com as tentativas no compliance.
 */
@Component
class DialerTickJob implements JobHandler<DialerTickJob.Payload> {

    static final String TYPE = "telephony.dialer_tick";
    private static final Logger LOG = LoggerFactory.getLogger(DialerTickJob.class);
    private static final List<String> DIALABLE_STATUSES = List.of("OPEN", "IN_NEGOTIATION");

    private final DialerCampaignRepository campaigns;
    private final DialerEntryRepository entries;
    private final CallRepository calls;
    private final AgentRepository agents;
    private final CallStatistics statistics;
    private final CaseUseCases cases;
    private final DebtorContactUseCases debtorContacts;
    private final ContactGate gate;
    private final ProviderResolver resolver;
    private final AccountRouter router;
    private final CallLifecycle lifecycle;
    private final ProviderCalls providerCalls;
    private final TenantExecution tenantExecution;
    private final TelephonyMetrics metrics;
    private final TelephonyProperties properties;
    private final Clock clock;

    DialerTickJob(DialerCampaignRepository campaigns, DialerEntryRepository entries, CallRepository calls,
                  AgentRepository agents, CallStatistics statistics, CaseUseCases cases,
                  DebtorContactUseCases debtorContacts, ContactGate gate, ProviderResolver resolver,
                  AccountRouter router, CallLifecycle lifecycle, ProviderCalls providerCalls,
                  TenantExecution tenantExecution, TelephonyMetrics metrics, TelephonyProperties properties, Clock clock) {
        this.campaigns = campaigns;
        this.entries = entries;
        this.calls = calls;
        this.agents = agents;
        this.statistics = statistics;
        this.cases = cases;
        this.debtorContacts = debtorContacts;
        this.gate = gate;
        this.resolver = resolver;
        this.router = router;
        this.lifecycle = lifecycle;
        this.providerCalls = providerCalls;
        this.tenantExecution = tenantExecution;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    record Payload(UUID campaignId) {
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public int maxAttempts() {
        // o agendamento periódico pede novos ciclos; não há o que acumular
        return 2;
    }

    @Override
    public boolean transactional() {
        return false;
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        UUID tenantId = context.tenantId();
        Optional<Cycle> cycle = tenantExecution.callInTenant(tenantId, "telephony.dialer_tick.prepare",
                () -> prepareCycle(payload.campaignId()));
        if (cycle.isEmpty()) {
            return;
        }
        RuntimeException failure = null;
        for (Batch batch : cycle.get().batches()) {
            try {
                dial(tenantId, cycle.get(), batch);
            } catch (RuntimeException e) {
                // os outros lotes já têm o pedido confirmado: seguem; o deste fica para a conciliação
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    // ------------------------------------------------------------------ fase 1: preparo (lock da campanha)

    private Optional<Cycle> prepareCycle(UUID campaignId) {
        Optional<DialerCampaign> found = campaigns.findByIdForUpdate(campaignId);
        if (found.isEmpty() || !found.get().isActive()) {
            return Optional.empty();
        }
        DialerCampaign campaign = found.get();
        Instant now = Instant.now(clock);
        String mode = campaign.getMode().name();
        if (!campaign.withinWindow(ZonedDateTime.ofInstant(now, TimeConfig.BUSINESS_ZONE).toLocalTime())) {
            metrics.pacing(mode, "outside_window", 0);
            return Optional.empty();
        }
        DialerPacing.Decision decision = pacing(campaign, now);
        if (decision.callsToPlace() == 0) {
            metrics.pacing(mode, decision.reason(), 0);
            return Optional.empty();
        }
        Map<String, Batch> batches = new LinkedHashMap<>();
        Map<String, Optional<AccountRouter.Route>> routeCache = new java.util.HashMap<>();
        int prepared = 0;
        List<UUID> due = entries.lockDueIds(campaign.getId(), now, Math.min(decision.callsToPlace() * 3, 500));
        for (DialerEntry entry : sorted(entries.findAllByIdIn(due), due)) {
            if (prepared >= decision.callsToPlace()) {
                break;
            }
            Optional<Prepared> call = prepare(campaign, entry, now, routeCache);
            if (call.isPresent()) {
                AccountRouter.Route route = call.get().route();
                String key = route.primary().getId() + "|" + route.fallbackAccount().map(a -> a.getId().toString()).orElse("");
                batches.computeIfAbsent(key, k -> new Batch(line(route.primary()), route.fallbackAccount().map(this::line),
                        new ArrayList<>())).calls().add(call.get().call());
                prepared++;
            }
        }
        metrics.pacing(mode, decision.reason(), prepared);
        return batches.isEmpty() ? Optional.empty()
                : Optional.of(new Cycle(campaign.getId(), campaign.getRingTimeoutSeconds(), List.copyOf(batches.values())));
    }

    /** Conta resolvida na transação: adaptador e contexto (credenciais) para discar fora dela. */
    private Line line(TelephonyAccount account) {
        return new Line(account, resolver.provider(account.getProvider()).orElseThrow(), resolver.context(account));
    }

    private DialerPacing.Decision pacing(DialerCampaign campaign, Instant now) {
        Instant startOfDay = ZonedDateTime.ofInstant(now, TimeConfig.BUSINESS_ZONE).toLocalDate()
                .atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        Counters counters = statistics.campaignCounters(campaign.getId(), startOfDay);
        int available = (int) agents.countAvailableInCampaign(campaign.getId());
        int inProgress = (int) calls.countUnconnectedActiveByCampaign(campaign.getId());
        return DialerPacing.decide(new DialerPacing.Input(campaign.getMode(), available, inProgress,
                campaign.getMaxDialRatio(), campaign.getMaxAbandonRate(), counters.live(), counters.abandoned(),
                counters.dialed(), properties.dialer().maxPerTick()));
    }

    private Optional<Prepared> prepare(DialerCampaign campaign, DialerEntry entry, Instant now,
                                       Map<String, Optional<AccountRouter.Route>> routeCache) {
        CaseView kase = cases.get(entry.getCaseId());
        if ("CLOSED".equals(kase.status())) {
            return exclude(entry, "CASE_CLOSED", now);
        }
        if (!campaign.isIncludeAssigned() && "USER".equals(kase.ownerType())) {
            return exclude(entry, "CASE_ASSIGNED", now);
        }
        if (!kase.eligible() || !DIALABLE_STATUSES.contains(kase.status())) {
            entry.defer("CASE_INELIGIBLE", now.plus(Duration.ofMinutes(campaign.getRetryIntervalMinutes())));
            entries.save(entry);
            metrics.entry("deferred");
            return Optional.empty();
        }
        List<ChannelContact> phones = debtorContacts.contactsForChannel(entry.getDebtorId(), "PHONE");
        if (phones.isEmpty()) {
            return exclude(entry, "NO_PHONE", now);
        }
        Optional<AccountRouter.Route> route = routeCache.computeIfAbsent(entry.getCreditorId() + "|" + entry.getPortfolioId(),
                k -> router.forCampaign(campaign.getId(), entry.getCreditorId(), entry.getPortfolioId()));
        Optional<TelephonyAccount> account = route.map(AccountRouter.Route::primary);
        if (account.isEmpty()) {
            LOG.atWarn().addKeyValue("campaignId", campaign.getId()).addKeyValue("creditorId", entry.getCreditorId())
                    .log("Campanha de discagem sem conta de telefonia ativa para o credor");
            metrics.entry("no_account");
            return Optional.empty();
        }
        ChannelContact phone = nextPhone(phones, lastDialedContactId(entry));
        Optional<ContactFingerprint> fingerprint = debtorContacts.tryFingerprint("PHONE", phone.value());
        Call call = calls.saveAndFlush(Call.outbound(CallOrigin.DIALER, account.get(),
                new Call.Target(entry.getCreditorId(), entry.getPortfolioId(), entry.getDebtorId(), entry.getCaseId(),
                        phone.contactId(), phone.value(), fingerprint.map(ContactFingerprint::hash).orElse(null),
                        fingerprint.map(ContactFingerprint::maskedValue).orElse(null)),
                null, campaign.getId(), entry.getId(), entry.getAttempts() + 1, now));
        ContactDecision decision = gate.authorizeAndRecord(new ContactGate.ContactRequest(entry.getDebtorId(),
                entry.getCreditorId(), "PHONE", phone.value(), null, CallOrigin.DIALER.gateOrigin(), "call", call.getId()));
        if (!decision.allowed()) {
            List<String> reasons = decision.reasons().stream().map(Enum::name).toList();
            call.block(String.join(",", reasons), now);
            calls.save(call);
            lifecycle.blocked(call, reasons, decision.nextAllowedAt(), "dial");
            if (decision.nextAllowedAt() != null) {
                entry.defer(String.join(",", reasons), decision.nextAllowedAt());
                metrics.entry("deferred");
            } else {
                entry.exclude("CONTACT_RESTRICTED", now);
                metrics.entry("excluded_contact_restricted");
            }
            entries.save(entry);
            return Optional.empty();
        }
        entry.attemptStarted(call.getId(), now);
        entries.save(entry);
        call.dialRequested(decision.attemptId(), now);
        calls.save(call);
        return Optional.of(new Prepared(route.get(), new Pending(call.getId(), phone.value(), call.isRecordingRequested(),
                call.isRecordingAnnounced())));
    }

    /**
     * Telefone da próxima tentativa (contatos-recebidos.md §4 e §9). Primeiro o número novo recebido na carga ainda sem
     * tentativa, na ordem de prioridade, sem repetir o último discado; senão, o seguinte ao último discado na ordem de
     * prioridade (rodízio estável: o fim da novidade não desloca a lista nem pula o número de maior prioridade). Último
     * discado ausente ou fora da lista (contato invalidado ou removido) → o primeiro da ordem. É só escolha: o porteiro
     * de contato continua decidindo cada discagem.
     *
     * @param phones              telefones utilizáveis do devedor, na ordem de prioridade
     * @param lastDialedContactId contato da última chamada do item (nulo = nenhuma)
     */
    static ChannelContact nextPhone(List<ChannelContact> phones, UUID lastDialedContactId) {
        for (ChannelContact phone : phones) {
            if (phone.newPending() && !phone.contactId().equals(lastDialedContactId)) {
                return phone;
            }
        }
        for (int i = 0; i < phones.size(); i++) {
            if (phones.get(i).contactId().equals(lastDialedContactId)) {
                return phones.get((i + 1) % phones.size());
            }
        }
        return phones.getFirst();
    }

    /** Contato da última chamada do item (nulo = primeira tentativa ou chamada sem contato identificado). */
    private UUID lastDialedContactId(DialerEntry entry) {
        return entry.getLastCallId() == null ? null
                : calls.findById(entry.getLastCallId()).map(Call::getContactId).orElse(null);
    }

    // ------------------------------------------------------------------ fase 2: provedor (sem transação)

    private void dial(UUID tenantId, Cycle cycle, Batch batch) {
        Line line = batch.primary();
        List<Pending> pending = batch.calls();
        List<DialResult> results;
        try {
            results = dialBatch(cycle, line, pending);
        } catch (TransientProviderException e) {
            metrics.dial(CallOrigin.DIALER.name(), "transient");
            metrics.providerDial(name(line.account()), CallOrigin.DIALER.name(),
                    e.notProcessed() ? "transient_not_processed" : "transient");
            Optional<Line> fallback = e.notProcessed() && properties.routing().fallbackEnabled()
                    ? batch.fallback() : Optional.empty();
            if (fallback.isEmpty()) {
                // nada desfaz o ciclo: o lote não discado fica recusado, com retentativa pela política
                results = unavailable(pending);
            } else {
                Line from = line;
                Line to = fallback.get();
                line = to;
                pending = tenantExecution.callInTenant(tenantId, "telephony.dialer_tick.fallback",
                        () -> switchToFallback(batch.calls(), to.account()));
                metrics.fallback(name(from.account()), name(to.account()), CallOrigin.DIALER.name(), pending.size());
                LOG.atWarn().addKeyValue("campaignId", cycle.campaignId()).addKeyValue("fromAccountId", from.account().getId())
                        .addKeyValue("toAccountId", to.account().getId()).addKeyValue("calls", pending.size())
                        .log("Lote do discador desviado para a conta de contingência");
                try {
                    results = pending.isEmpty() ? List.of() : dialBatch(cycle, to, pending);
                } catch (TransientProviderException again) {
                    metrics.providerDial(name(to.account()), CallOrigin.DIALER.name(), "transient");
                    results = unavailable(pending);
                } catch (RuntimeException unexpected) {
                    results = providerError(cycle, to, pending, unexpected);
                }
            }
        } catch (RuntimeException unexpected) {
            results = providerError(cycle, line, pending, unexpected);
        }
        List<Pending> dialed = pending;
        List<DialResult> outcome = results;
        Line used = line;
        tenantExecution.inTenant(tenantId, "telephony.dialer_tick.result", () -> record(used, dialed, outcome));
    }

    /** Desvio do lote para a contingência, gravado antes do novo pedido; só as chamadas ainda sem resposta. */
    private List<Pending> switchToFallback(List<Pending> pending, TelephonyAccount fallback) {
        List<Pending> switched = new ArrayList<>();
        for (Pending p : pending) {
            Optional<Call> call = calls.findByIdForUpdate(p.callId()).filter(Call::isAwaitingProvider);
            if (call.isPresent()) {
                call.get().switchToFallback(fallback);
                calls.save(call.get());
                switched.add(new Pending(p.callId(), p.number(), call.get().isRecordingRequested(),
                        call.get().isRecordingAnnounced()));
            }
        }
        return switched;
    }

    private List<DialResult> dialBatch(Cycle cycle, Line line, List<Pending> pending) {
        List<DialRequest> requests = pending.stream()
                .map(p -> new DialRequest(p.callId(), p.number(), cycle.ringTimeoutSeconds(), true,
                        p.recordingRequested(), p.recordingAnnounced()))
                .toList();
        return line.provider().dialBatch(line.context(), requests);
    }

    /**
     * Falha inesperada do adaptador: não se sabe se o lote foi criado. A chamada local fica final (recusa com
     * retentativa) e as instruções do provedor encerram a que existir lá (chamada final → {@code Hangup}).
     */
    private List<DialResult> providerError(Cycle cycle, Line line, List<Pending> pending, RuntimeException e) {
        metrics.providerDial(name(line.account()), CallOrigin.DIALER.name(), "error");
        LOG.atError().addKeyValue("campaignId", cycle.campaignId()).addKeyValue("accountId", line.account().getId())
                .addKeyValue("calls", pending.size()).setCause(e).log("Falha inesperada na discagem em lote");
        return pending.stream()
                .map(p -> DialResult.rejected(p.callId(), "PROVIDER_ERROR", "Falha inesperada do provedor neste ciclo"))
                .toList();
    }

    /** Lote não discado por falha transitória: recusa com retentativa pela política. */
    private static List<DialResult> unavailable(List<Pending> pending) {
        return pending.stream()
                .map(p -> DialResult.rejected(p.callId(), "PROVIDER_UNAVAILABLE", "Provedor indisponível neste ciclo"))
                .toList();
    }

    // ------------------------------------------------------------------ fase 3: resultado (transação por lote)

    private void record(Line line, List<Pending> pending, List<DialResult> results) {
        Instant now = Instant.now(clock);
        Map<UUID, DialResult> byCall = new LinkedHashMap<>();
        results.forEach(r -> byCall.put(r.callId(), r));
        for (Pending p : pending) {
            Call call = calls.findByIdForUpdate(p.callId()).orElseThrow();
            DialResult result = byCall.get(call.getId());
            if (result != null && result.accepted()) {
                DialJob.accepted(line.account(), call, result.providerCallId(), calls, lifecycle, providerCalls,
                        HangupReason.CANCELLED);
            } else if (call.isAwaitingProvider()) {
                metrics.dial(CallOrigin.DIALER.name(), "rejected");
                metrics.providerDial(name(call), CallOrigin.DIALER.name(), "rejected");
                call.providerRejected(result == null ? "NO_RESULT" : result.errorCode(),
                        result == null ? "Provedor não devolveu resultado" : result.errorDetail(), now);
                calls.save(call);
                lifecycle.ended(call);
            }
        }
    }

    private static String name(TelephonyAccount account) {
        return account.getProvider().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String name(Call call) {
        return call.getProvider().name().toLowerCase(java.util.Locale.ROOT);
    }

    private Optional<Prepared> exclude(DialerEntry entry, String reason, Instant now) {
        entry.exclude(reason, now);
        entries.save(entry);
        metrics.entry("excluded_" + reason.toLowerCase(java.util.Locale.ROOT));
        return Optional.empty();
    }

    private static List<DialerEntry> sorted(List<DialerEntry> loaded, List<UUID> order) {
        Map<UUID, DialerEntry> byId = new LinkedHashMap<>();
        loaded.forEach(e -> byId.put(e.getId(), e));
        return order.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    /** Chamada pedida no preparo. O número em claro vai ao provedor e nunca a log: {@link #toString()} não o inclui. */
    private record Pending(UUID callId, String number, boolean recordingRequested, boolean recordingAnnounced) {

        @Override
        public String toString() {
            return "Pending[callId=" + callId + "]";
        }
    }

    private record Prepared(AccountRouter.Route route, Pending call) {
    }

    private record Line(TelephonyAccount account, TelephonyProvider provider, ProviderContext context) {
    }

    private record Batch(Line primary, Optional<Line> fallback, List<Pending> calls) {
    }

    private record Cycle(UUID campaignId, int ringTimeoutSeconds, List<Batch> batches) {
    }
}
