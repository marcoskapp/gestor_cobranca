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
import br.com.gestorcobranca.telephony.application.TelephonyProperties;
import br.com.gestorcobranca.telephony.application.port.out.CallStatistics;
import br.com.gestorcobranca.telephony.application.port.out.CallStatistics.Counters;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.DialRequest;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.DialResult;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Um ciclo do discador para uma campanha ativa ({@code telephony.dialer_tick}), sob lock da
 * campanha:
 * <ol>
 *   <li>fora da janela própria da campanha → nada a fazer;</li>
 *   <li>ritmo ({@link DialerPacing}): operadores disponíveis na campanha, chamadas ainda sem
 *       operador e os contadores do dia (atendidas por pessoa, abandonadas, discadas);</li>
 *   <li>itens vencidos por prioridade ({@code SKIP LOCKED}): caso encerrado → excluído;
 *       inelegível ou atribuído a um operador (sem {@code includeAssigned}) → adiado/excluído;
 *       sem telefone → excluído; telefone escolhido em rodízio pela prioridade dos contatos;</li>
 *   <li>{@code ContactGate#authorizeAndRecord} (origem {@code CAMPAIGN}) para cada item: negação
 *       com próximo instante permitido → adiado sem consumir tentativa; sem fim previsto → excluído;</li>
 *   <li>discagem em lote por conta ({@code dialBatch}); recusa do provedor → tentativa com resultado
 *       {@code FAILED} (retentativa pela política). Falha transitória desfaz o ciclo inteiro,
 *       inclusive as tentativas registradas no compliance.</li>
 * </ol>
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
    private final CallLifecycle lifecycle;
    private final TelephonyMetrics metrics;
    private final TelephonyProperties properties;
    private final Clock clock;

    DialerTickJob(DialerCampaignRepository campaigns, DialerEntryRepository entries, CallRepository calls,
                  AgentRepository agents, CallStatistics statistics, CaseUseCases cases,
                  DebtorContactUseCases debtorContacts, ContactGate gate, ProviderResolver resolver,
                  CallLifecycle lifecycle, TelephonyMetrics metrics, TelephonyProperties properties, Clock clock) {
        this.campaigns = campaigns;
        this.entries = entries;
        this.calls = calls;
        this.agents = agents;
        this.statistics = statistics;
        this.cases = cases;
        this.debtorContacts = debtorContacts;
        this.gate = gate;
        this.resolver = resolver;
        this.lifecycle = lifecycle;
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
    public void handle(JobContext context, Payload payload) {
        Optional<DialerCampaign> found = campaigns.findByIdForUpdate(payload.campaignId());
        if (found.isEmpty() || !found.get().isActive()) {
            return;
        }
        DialerCampaign campaign = found.get();
        Instant now = Instant.now(clock);
        String mode = campaign.getMode().name();
        if (!campaign.withinWindow(ZonedDateTime.ofInstant(now, TimeConfig.BUSINESS_ZONE).toLocalTime())) {
            metrics.pacing(mode, "outside_window", 0);
            return;
        }
        DialerPacing.Decision decision = pacing(campaign, now);
        if (decision.callsToPlace() == 0) {
            metrics.pacing(mode, decision.reason(), 0);
            return;
        }
        Map<UUID, Batch> batches = new LinkedHashMap<>();
        int prepared = 0;
        List<UUID> due = entries.lockDueIds(campaign.getId(), now, Math.min(decision.callsToPlace() * 3, 500));
        for (DialerEntry entry : sorted(entries.findAllByIdIn(due), due)) {
            if (prepared >= decision.callsToPlace()) {
                break;
            }
            Optional<Prepared> call = prepare(campaign, entry, now);
            if (call.isPresent()) {
                batches.computeIfAbsent(call.get().account().getId(), id -> new Batch(call.get().account(), new ArrayList<>()))
                        .calls().add(call.get().call());
                prepared++;
            }
        }
        for (Batch batch : batches.values()) {
            dial(campaign, batch);
        }
        metrics.pacing(mode, decision.reason(), prepared);
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

    private Optional<Prepared> prepare(DialerCampaign campaign, DialerEntry entry, Instant now) {
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
        Optional<TelephonyAccount> account = resolver.findAccountFor(entry.getCreditorId())
                .filter(a -> resolver.provider(a.getProvider()).isPresent());
        if (account.isEmpty()) {
            LOG.atWarn().addKeyValue("campaignId", campaign.getId()).addKeyValue("creditorId", entry.getCreditorId())
                    .log("Campanha de discagem sem conta de telefonia ativa para o credor");
            metrics.entry("no_account");
            return Optional.empty();
        }
        ChannelContact phone = phones.get(entry.getAttempts() % phones.size());
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
        return Optional.of(new Prepared(account.get(), new Pending(call, decision.attemptId())));
    }

    private void dial(DialerCampaign campaign, Batch batch) {
        TelephonyProvider provider = resolver.provider(batch.account().getProvider()).orElseThrow();
        List<DialRequest> requests = batch.calls().stream()
                .map(p -> new DialRequest(p.call().getId(), p.call().getNumber(), campaign.getRingTimeoutSeconds(), true,
                        p.call().isRecordingRequested(), p.call().isRecordingAnnounced()))
                .toList();
        List<DialResult> results;
        try {
            results = provider.dialBatch(resolver.context(batch.account()), requests);
        } catch (TransientProviderException e) {
            metrics.dial(CallOrigin.DIALER.name(), "transient");
            throw e;
        }
        Instant now = Instant.now(clock);
        Map<UUID, DialResult> byCall = new LinkedHashMap<>();
        results.forEach(r -> byCall.put(r.callId(), r));
        for (Pending pending : batch.calls()) {
            Call call = pending.call();
            DialResult result = byCall.get(call.getId());
            if (result != null && result.accepted()) {
                call.dialing(result.providerCallId(), pending.attemptId(), now);
                calls.save(call);
                lifecycle.dialed(call);
            } else {
                metrics.dial(CallOrigin.DIALER.name(), "rejected");
                call.failBeforeDialing(result == null ? "NO_RESULT" : result.errorCode(),
                        result == null ? "Provedor não devolveu resultado" : result.errorDetail(), now);
                calls.save(call);
                lifecycle.ended(call);
            }
        }
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
        return order.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    private record Pending(Call call, UUID attemptId) {
    }

    private record Prepared(TelephonyAccount account, Pending call) {
    }

    private record Batch(TelephonyAccount account, List<Pending> calls) {
    }
}
