package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ContactFingerprint;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.telephony.application.port.in.ProviderEventUseCases;
import br.com.gestorcobranca.telephony.application.port.out.CallInstruction;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent;
import br.com.gestorcobranca.telephony.application.port.out.ProviderCallEvent.Type;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.BridgeRequest;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.HangupReason;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.HangupRequest;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.ProviderRejectedException;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.TransientProviderException;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyWebhookAdapter.InstructionRequest;
import br.com.gestorcobranca.telephony.application.port.out.WebhookReceiptLog;
import br.com.gestorcobranca.telephony.domain.model.Agent;
import br.com.gestorcobranca.telephony.domain.model.AmdResult;
import br.com.gestorcobranca.telephony.domain.model.Call;
import br.com.gestorcobranca.telephony.domain.model.CallOrigin;
import br.com.gestorcobranca.telephony.domain.model.CallRecording;
import br.com.gestorcobranca.telephony.domain.model.CallStatus;
import br.com.gestorcobranca.telephony.domain.model.DialerCampaign;
import br.com.gestorcobranca.telephony.domain.model.HangupSide;
import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import br.com.gestorcobranca.telephony.domain.repository.CallRecordingRepository;
import br.com.gestorcobranca.telephony.domain.repository.CallRepository;
import br.com.gestorcobranca.telephony.domain.repository.DialerCampaignRepository;
import br.com.gestorcobranca.telephony.domain.repository.TelephonyAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Aplica os eventos do provedor às chamadas (no tenant corrente, já autenticados pelo webhook ou
 * vindos do simulador):
 * <ul>
 *   <li>idempotência pelo id do evento ({@code telephony.provider_event}) e pelo estado da chamada
 *       (eventos repetidos ou fora de ordem são obsoletos);</li>
 *   <li>atendimento no discador: secretária eletrônica (AMD) → encerra; pessoa → operador livre
 *       da campanha ({@link CallDistributor}) e {@code bridge}; sem operador → abandono (mensagem
 *       informativa e encerramento);</li>
 *   <li>chamada recebida: identifica o devedor pelas chamadas anteriores ao mesmo número (blind
 *       index) e distribui (o responsável pelo caso primeiro); sem operador → perdida;</li>
 *   <li>gravação disponível → cópia para o armazenamento próprio (job).</li>
 * </ul>
 */
@Service
@Transactional
class CallEventProcessor implements ProviderEventUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(CallEventProcessor.class);

    private final TelephonyAccountRepository accounts;
    private final CallRepository calls;
    private final CallRecordingRepository recordings;
    private final DialerCampaignRepository campaigns;
    private final WebhookReceiptLog receipts;
    private final CallDistributor distributor;
    private final CallLifecycle lifecycle;
    private final ProviderResolver resolver;
    private final AgentEndpoints endpoints;
    private final CaseUseCases cases;
    private final DebtorContactUseCases debtorContacts;
    private final JobQueue jobQueue;
    private final TelephonyMetrics metrics;
    private final CallInstructions instructions;
    private final Clock clock;

    CallEventProcessor(TelephonyAccountRepository accounts, CallRepository calls, CallRecordingRepository recordings,
                       DialerCampaignRepository campaigns, WebhookReceiptLog receipts, CallDistributor distributor,
                       CallLifecycle lifecycle, ProviderResolver resolver, AgentEndpoints endpoints, CaseUseCases cases,
                       DebtorContactUseCases debtorContacts, JobQueue jobQueue, TelephonyMetrics metrics,
                       CallInstructions instructions, Clock clock) {
        this.accounts = accounts;
        this.calls = calls;
        this.recordings = recordings;
        this.campaigns = campaigns;
        this.receipts = receipts;
        this.distributor = distributor;
        this.lifecycle = lifecycle;
        this.resolver = resolver;
        this.endpoints = endpoints;
        this.cases = cases;
        this.debtorContacts = debtorContacts;
        this.jobQueue = jobQueue;
        this.metrics = metrics;
        this.instructions = instructions;
        this.clock = clock;
    }

    @Override
    public String apply(UUID accountId, ProviderCallEvent event) {
        Optional<TelephonyAccount> account = accounts.findById(accountId);
        if (account.isEmpty() || event == null || event.type() == null || event.eventId() == null
                || event.eventId().isBlank() || event.eventId().length() > 200) {
            return "ignored";
        }
        String provider = account.get().getProvider().name();
        String type = event.type().name();
        String outcome;
        if (!receipts.registerEventIfFirst(accountId, event.eventId(), type, Instant.now(clock))) {
            outcome = "duplicate";
        } else if (event.type() == Type.INBOUND) {
            outcome = inbound(account.get(), event);
        } else {
            outcome = onCall(account.get(), event);
        }
        metrics.webhookEvent(provider, type, outcome);
        return outcome;
    }

    @Override
    @Transactional(readOnly = true)
    public CallInstruction instruction(UUID accountId, InstructionRequest request) {
        return accounts.findById(accountId).map(account -> instructions.resolve(account, request))
                .orElse(new CallInstruction.Hangup(false));
    }

    private String onCall(TelephonyAccount account, ProviderCallEvent e) {
        Optional<Call> found = Optional.empty();
        if (e.providerCallId() != null && !e.providerCallId().isBlank()) {
            found = calls.findByProviderAndProviderCallId(account.getProvider(), e.providerCallId());
        }
        if (found.isEmpty() && e.callReference() != null) {
            found = calls.findById(e.callReference()).filter(c -> c.getAccountId().equals(account.getId()));
        }
        if (found.isEmpty()) {
            LOG.atInfo().addKeyValue("provider", account.getProvider()).addKeyValue("type", e.type())
                    .log("Evento de chamada desconhecida ignorado");
            return "unknown_call";
        }
        Call call = calls.findByIdForUpdate(found.get().getId()).orElseThrow();
        if (call.bindProviderLeg(e.providerLegId())) {
            calls.save(call);
        }
        Instant at = e.occurredAt() == null ? Instant.now(clock) : e.occurredAt();
        return switch (e.type()) {
            case RINGING -> save(call, call.ringing(at));
            case ANSWERED -> answered(account, call, at, amd(e.amd()), e.awaitingInstructions());
            case COMPLETED -> end(call, call.getStatus() == CallStatus.ANSWERED ? CallStatus.COMPLETED : CallStatus.NO_ANSWER,
                    at, side(e.hangupBy()), e.cause());
            case NO_ANSWER -> end(call, CallStatus.NO_ANSWER, at, HangupSide.PROVIDER, e.cause());
            case BUSY -> end(call, CallStatus.BUSY, at, HangupSide.PROVIDER, e.cause());
            case FAILED -> end(call, CallStatus.FAILED, at, HangupSide.PROVIDER, e.cause() == null ? "PROVIDER_FAILED" : e.cause());
            case CANCELLED -> end(call, CallStatus.CANCELLED, at, side(e.hangupBy()), e.cause());
            case RECORDING_AVAILABLE -> recording(call, e, at);
            case INBOUND -> "ignored";
        };
    }

    /**
     * @param awaiting o provedor espera, na resposta deste webhook, as instruções da chamada: conexão e
     *                 encerramento vão na resposta ({@link CallInstructions}), sem chamada à API dele
     */
    private String answered(TelephonyAccount account, Call call, Instant at, AmdResult amd, boolean awaiting) {
        if (!call.answered(at, amd)) {
            return "stale";
        }
        Instant now = Instant.now(clock);
        if (call.getOrigin() != CallOrigin.DIALER) {
            calls.save(call);
            return "applied";
        }
        String mode = campaigns.findById(call.getCampaignId()).map(DialerCampaign::getMode).map(Enum::name)
                .orElse("UNKNOWN");
        if (amd == AmdResult.MACHINE) {
            hangup(account, call, HangupReason.MACHINE, awaiting);
            call.end(CallStatus.COMPLETED, now, HangupSide.SYSTEM, null);
            lifecycle.ended(call);
            return "applied";
        }
        Optional<Agent> agent = distributor.forCampaign(call.getCampaignId(), account.getId(), call.getId(), now);
        if (agent.isPresent() && bridge(account, call, agent.get(), awaiting)) {
            call.connect(agent.get().getUserId(), now);
            calls.save(call);
            metrics.live(mode, "connected");
            metrics.distributionLatency(call.getOrigin().name(), Duration.between(at, now).isNegative()
                    ? Duration.ZERO : Duration.between(at, now));
            return "applied";
        }
        if (agent.isPresent()) {
            // a conexão falhou: o operador volta a ficar livre e a chamada é tratada como abandono
            lifecycle.releaseAfterFailedBridge(agent.get(), call.getId());
        }
        call.markAbandoned();
        hangup(account, call, HangupReason.ABANDONED, awaiting);
        call.end(CallStatus.COMPLETED, now, HangupSide.SYSTEM, null);
        metrics.live(mode, "abandoned");
        lifecycle.ended(call);
        return "applied";
    }

    private String end(Call call, CallStatus target, Instant at, HangupSide side, String cause) {
        if (!call.end(target, at, side, cause)) {
            return "stale";
        }
        lifecycle.ended(call);
        return "applied";
    }

    private String recording(Call call, ProviderCallEvent e, Instant at) {
        if (e.recordingRef() == null || e.recordingRef().isBlank()) {
            return "ignored";
        }
        if (recordings.findByCallId(call.getId()).isPresent()) {
            return "stale";
        }
        if (!call.isRecordingRequested()) {
            LOG.atWarn().addKeyValue("callId", call.getId())
                    .log("Gravação recebida para chamada sem gravação habilitada; descartada");
            return "ignored";
        }
        CallRecording recording = recordings.save(new CallRecording(call, e.recordingRef(), e.recordingDurationSeconds(), at));
        jobQueue.enqueue(RecordingFetchJob.TYPE, new RecordingFetchJob.Payload(recording.getId()), null,
                "recording:" + recording.getId());
        return "applied";
    }

    private String inbound(TelephonyAccount account, ProviderCallEvent e) {
        if (e.providerCallId() == null || e.providerCallId().isBlank()) {
            return "ignored";
        }
        if (calls.findByProviderAndProviderCallId(account.getProvider(), e.providerCallId()).isPresent()) {
            return "stale";
        }
        Instant at = e.occurredAt() == null ? Instant.now(clock) : e.occurredAt();
        Optional<ContactFingerprint> fingerprint = e.from() == null ? Optional.empty()
                : debtorContacts.tryFingerprint("PHONE", e.from());
        Call call = Call.inbound(account, e.providerCallId(), e.from(),
                fingerprint.map(ContactFingerprint::hash).orElse(null),
                fingerprint.map(ContactFingerprint::maskedValue).orElse("***"), at);
        call.bindProviderLeg(e.providerLegId());
        Optional<CaseView> kase = fingerprint.flatMap(f -> identify(f.hash()));
        kase.ifPresent(k -> call.identify(k.debtorId(), k.creditorId(), k.portfolioId(), k.id(), null));
        calls.saveAndFlush(call);
        Instant now = Instant.now(clock);
        Optional<Agent> agent = distributor.forInbound(kase.map(CaseView::assigneeUserId).orElse(null), account.getId(),
                call.getId(), now);
        boolean awaiting = e.awaitingInstructions();
        if (agent.isPresent() && bridge(account, call, agent.get(), awaiting)) {
            call.connect(agent.get().getUserId(), now);
            calls.save(call);
            return "applied";
        }
        if (agent.isPresent()) {
            lifecycle.releaseAfterFailedBridge(agent.get(), call.getId());
        }
        hangup(account, call, HangupReason.ABANDONED, awaiting);
        call.end(CallStatus.NO_ANSWER, now, HangupSide.SYSTEM, CallInstructions.NO_AGENT);
        lifecycle.ended(call);
        return "applied";
    }

    /**
     * Devedor do número: o das chamadas anteriores identificadas, se for um só; caso ativo do
     * devedor na mesma carteira (o da última chamada, se ainda ativo).
     */
    private Optional<CaseView> identify(String numberHash) {
        List<Call> previous = calls.findIdentifiedByNumberHash(numberHash, 20);
        if (previous.isEmpty() || previous.stream().map(Call::getDebtorId).distinct().count() != 1) {
            return Optional.empty();
        }
        Call last = previous.getFirst();
        CaseView kase = cases.get(last.getCaseId());
        if (!"CLOSED".equals(kase.status())) {
            return Optional.of(kase);
        }
        return last.getPortfolioId() == null ? Optional.empty() : cases.findActive(last.getDebtorId(), last.getPortfolioId());
    }

    private boolean bridge(TelephonyAccount account, Call call, Agent agent, boolean awaiting) {
        Optional<TelephonyProvider> provider = resolver.provider(account.getProvider());
        Optional<String> extension = endpoints.extensionFor(agent, account.getId());
        if (provider.isEmpty() || extension.isEmpty()) {
            return false;
        }
        if (awaiting) {
            // a conexão vai na resposta do próprio webhook (CallInstructions)
            return true;
        }
        try {
            provider.get().bridge(new BridgeRequest(resolver.context(account), call.getId(), call.getProviderCallId(),
                    extension.get(), call.getProviderLegId(), agent.getUserId()));
            return true;
        } catch (TransientProviderException | ProviderRejectedException e) {
            LOG.atWarn().addKeyValue("callId", call.getId()).addKeyValue("provider", account.getProvider())
                    .log("Falha ao conectar a chamada ao operador");
            return false;
        }
    }

    private void hangup(TelephonyAccount account, Call call, HangupReason reason, boolean awaiting) {
        if (awaiting) {
            // o encerramento (com a mensagem de abandono) vai na resposta do próprio webhook
            return;
        }
        resolver.provider(account.getProvider()).ifPresent(p -> {
            try {
                p.hangup(new HangupRequest(resolver.context(account), call.getId(), call.getProviderCallId(), reason,
                        call.getProviderLegId()));
            } catch (TransientProviderException | ProviderRejectedException e) {
                // o provedor encerra a chamada pelo próprio tempo limite (ou ela já terminou); o estado local já é final
                LOG.atWarn().addKeyValue("callId", call.getId()).addKeyValue("reason", reason)
                        .log("Falha ao pedir o encerramento da chamada ao provedor");
            }
        });
    }

    private String save(Call call, boolean applied) {
        if (!applied) {
            return "stale";
        }
        calls.save(call);
        return "applied";
    }

    private static AmdResult amd(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return AmdResult.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AmdResult.UNKNOWN;
        }
    }

    private static HangupSide side(String value) {
        if (value == null || value.isBlank()) {
            return HangupSide.PROVIDER;
        }
        try {
            return HangupSide.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return HangupSide.PROVIDER;
        }
    }
}
