package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.RecordResult;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactDecision;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ChannelContact;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ContactFingerprint;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.idempotency.IdempotencyService;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.storage.FileStorage;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.HangupReason;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyProvider.HangupRequest;
import br.com.gestorcobranca.telephony.domain.model.Agent;
import br.com.gestorcobranca.telephony.domain.model.Call;
import br.com.gestorcobranca.telephony.domain.model.CallOrigin;
import br.com.gestorcobranca.telephony.domain.model.CallRecording;
import br.com.gestorcobranca.telephony.domain.model.CallResult;
import br.com.gestorcobranca.telephony.domain.model.CallStatus;
import br.com.gestorcobranca.telephony.domain.model.HangupSide;
import br.com.gestorcobranca.telephony.domain.model.RecordingPurpose;
import br.com.gestorcobranca.telephony.domain.model.RecordingStatus;
import br.com.gestorcobranca.telephony.domain.model.TelephonyAccount;
import br.com.gestorcobranca.telephony.domain.repository.AgentRepository;
import br.com.gestorcobranca.telephony.domain.repository.CallRecordingRepository;
import br.com.gestorcobranca.telephony.domain.repository.CallRepository;
import br.com.gestorcobranca.telephony.domain.repository.CallSearchCriteria;
import br.com.gestorcobranca.telephony.domain.repository.TelephonyAccountRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class CallService implements CallUseCases {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(CallService.class);

    private final CallRepository calls;
    private final CallRecordingRepository recordings;
    private final AgentRepository agents;
    private final TelephonyAccountRepository accounts;
    private final CaseUseCases cases;
    private final DebtorContactUseCases debtorContacts;
    private final ContactGate gate;
    private final ProviderResolver resolver;
    private final AccountRouter router;
    private final CallLifecycle lifecycle;
    private final CaseInteractions caseInteractions;
    private final PresenceRecorder presence;
    private final CallAccess callAccess;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final IdempotencyService idempotency;
    private final JobQueue jobQueue;
    private final FileStorage storage;
    private final AccessLogger accessLogger;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final TelephonyMetrics metrics;
    private final Clock clock;

    CallService(CallRepository calls, CallRecordingRepository recordings, AgentRepository agents,
                TelephonyAccountRepository accounts, CaseUseCases cases, DebtorContactUseCases debtorContacts,
                ContactGate gate, ProviderResolver resolver, AccountRouter router, CallLifecycle lifecycle,
                CaseInteractions caseInteractions,
                PresenceRecorder presence, CallAccess callAccess, AccessScope accessScope, CurrentActor currentActor,
                IdempotencyService idempotency, JobQueue jobQueue, FileStorage storage, AccessLogger accessLogger,
                AuditTrail auditTrail, BusinessEventLogger businessEvents, TelephonyMetrics metrics, Clock clock) {
        this.calls = calls;
        this.recordings = recordings;
        this.agents = agents;
        this.accounts = accounts;
        this.cases = cases;
        this.debtorContacts = debtorContacts;
        this.gate = gate;
        this.resolver = resolver;
        this.router = router;
        this.lifecycle = lifecycle;
        this.caseInteractions = caseInteractions;
        this.presence = presence;
        this.callAccess = callAccess;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.idempotency = idempotency;
        this.jobQueue = jobQueue;
        this.storage = storage;
        this.accessLogger = accessLogger;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public CallView clickToCall(ClickToCallCommand command, String idempotencyKey) {
        Guard.require(command != null && command.caseId() != null, "call.case_required", "Caso obrigatório");
        Guard.require(currentActor.get().isUser(), "call.user_required", "Clique para ligar é exclusivo de operadores");
        Holder holder = new Holder();
        UUID callId = idempotency.execute("telephony.click_to_call", idempotencyKey,
                command.caseId() + "|" + command.contactId(), () -> {
                    holder.view = startClickToCall(command);
                    return holder.view.id();
                });
        return holder.view != null ? holder.view : get(callId);
    }

    private CallView startClickToCall(ClickToCallCommand command) {
        CaseView kase = cases.get(command.caseId());
        accessScope.requireCreditor(kase.creditorId());
        Guard.requireState(!"CLOSED".equals(kase.status()), "call.case_closed", "Caso encerrado");
        UUID operatorId = currentActor.actorId();
        Agent agent = agents.findByUserIdForUpdate(operatorId).filter(Agent::isActive)
                .orElseThrow(() -> new ConflictException("telephony.agent_not_configured",
                        "Operador sem ramal habilitado na telefonia"));
        ChannelContact phone = phone(kase.debtorId(), command.contactId());
        Optional<ContactFingerprint> fingerprint = debtorContacts.tryFingerprint("PHONE", phone.value());
        TelephonyAccount account = router.forClickToCall(agent, kase.creditorId(), kase.portfolioId()).primary();
        Instant now = Instant.now(clock);
        Call call = Call.outbound(CallOrigin.CLICK_TO_CALL, account,
                new Call.Target(kase.creditorId(), kase.portfolioId(), kase.debtorId(), kase.id(), phone.contactId(),
                        phone.value(), fingerprint.map(ContactFingerprint::hash).orElse(null),
                        fingerprint.map(ContactFingerprint::maskedValue).orElse(null)),
                operatorId, null, null, 1, now);
        ContactDecision precheck = gate.evaluate(new ContactGate.ContactRequest(kase.debtorId(), kase.creditorId(),
                "PHONE", phone.value(), null, CallOrigin.CLICK_TO_CALL.gateOrigin(), "call", call.getId()));
        if (!precheck.allowed()) {
            List<String> reasons = precheck.reasons().stream().map(Enum::name).toList();
            call.block(String.join(",", reasons), now);
            calls.saveAndFlush(call);
            lifecycle.blocked(call, reasons, precheck.nextAllowedAt(), "precheck");
            return CallViews.call(call, null, precheck.nextAllowedAt());
        }
        calls.saveAndFlush(call);
        presence.record(agent.reserve(call.getId(), now), "call");
        agents.save(agent);
        jobQueue.enqueue(DialJob.TYPE, new DialJob.Payload(call.getId()), null, "dial:" + call.getId());
        return CallViews.call(call, null, null);
    }

    private ChannelContact phone(UUID debtorId, UUID contactId) {
        List<ChannelContact> phones = debtorContacts.contactsForChannel(debtorId, "PHONE");
        Optional<ChannelContact> chosen = contactId == null ? phones.stream().findFirst()
                : phones.stream().filter(p -> p.contactId().equals(contactId)).findFirst();
        return chosen.orElseThrow(() -> new BusinessRuleException("call.contact_not_found",
                contactId == null ? "Devedor sem telefone válido" : "Telefone inexistente ou inválido para o devedor"));
    }

    @Override
    @Transactional(readOnly = true)
    public CallView get(UUID callId) {
        Call call = calls.findById(callId).orElseThrow(() -> ResourceNotFoundException.of("call", callId));
        callAccess.requireVisible(call);
        return CallViews.call(call, recordings.findByCallId(callId).orElse(null), null);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CallView> search(CallFilter filter, PageQuery page) {
        CallFilter f = filter == null ? new CallFilter(null, null, null, null, null, null, null, null, null, null) : filter;
        UUID operatorId = f.operatorId();
        if (!callAccess.supervises() && f.caseId() == null) {
            // sem supervisão, somente as próprias chamadas ou as de um caso visível
            operatorId = callAccess.actorId();
        }
        if (f.caseId() != null) {
            cases.get(f.caseId());
        }
        if (f.creditorId() != null) {
            accessScope.requireVisible("creditor", f.creditorId(), f.creditorId());
        }
        Set<UUID> allowed = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(Set.of());
        boolean withoutCreditor = accessScope.hasAllCreditors() || callAccess.actorId().equals(operatorId);
        CallSearchCriteria criteria = new CallSearchCriteria(allowed, withoutCreditor, f.creditorId(),
                f.caseId(), f.debtorId(), operatorId, f.campaignId(),
                Optional.ofNullable(TelephonyEnums.parseOptional(CallStatus.class, f.status(), "call.status_invalid",
                        "Estado")).map(Enum::name).orElse(null),
                Optional.ofNullable(TelephonyEnums.parseOptional(CallResult.class, f.result(), "call.result_invalid",
                        "Resultado")).map(Enum::name).orElse(null),
                Optional.ofNullable(TelephonyEnums.parseOptional(br.com.gestorcobranca.telephony.domain.model.CallDirection.class,
                        f.direction(), "call.direction_invalid", "Direção")).map(Enum::name).orElse(null),
                f.from(), f.to());
        return PageResponse.of(calls.search(criteria, page.toPageable(Sort.by(Sort.Direction.DESC, "queuedAt"))),
                c -> CallViews.call(c, null, null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CallView> awaitingTabulation() {
        return calls.findAwaitingTabulation(currentActor.actorId()).stream()
                .map(c -> CallViews.call(c, null, null)).toList();
    }

    @Override
    public CallView hangup(UUID callId) {
        Call call = calls.findByIdForUpdate(callId).orElseThrow(() -> ResourceNotFoundException.of("call", callId));
        callAccess.requireOperatorOrSupervisor(call);
        Guard.requireState(call.getStatus().isActive(), "call.not_active", "A chamada já terminou");
        Instant now = Instant.now(clock);
        if (call.getStatus() != CallStatus.QUEUED) {
            TelephonyAccount account = accounts.findById(call.getAccountId()).orElseThrow();
            resolver.provider(account.getProvider()).ifPresent(p -> {
                try {
                    p.hangup(new HangupRequest(resolver.context(account), call.getId(), call.getProviderCallId(),
                            HangupReason.OPERATOR, call.getProviderLegId()));
                } catch (TelephonyProvider.ProviderRejectedException e) {
                    // a chamada já terminou no provedor: o encerramento local segue
                    LOG.atWarn().addKeyValue("callId", call.getId())
                            .addKeyValue("code", e.code()).log("Provedor recusou o encerramento da chamada");
                }
            });
        }
        HangupSide side = callAccess.actorId().equals(call.getOperatorId()) ? HangupSide.OPERATOR : HangupSide.SYSTEM;
        call.end(call.getStatus() == CallStatus.ANSWERED ? CallStatus.COMPLETED : CallStatus.CANCELLED, now, side,
                call.getStatus() == CallStatus.ANSWERED ? null : "HANGUP_REQUESTED");
        jobQueue.cancelPending(DialJob.TYPE, "dial:" + call.getId());
        lifecycle.ended(call);
        if (side == HangupSide.SYSTEM) {
            auditTrail.record(AuditEntry.of("call.hung_up_by_supervisor", "call", call.getId())
                    .creditor(call.getCreditorId()).meta("operatorId", call.getOperatorId()));
        }
        return CallViews.call(call, null, null);
    }

    @Override
    public CallView tabulate(UUID callId, TabulateCommand command) {
        Guard.require(command != null, "call.tabulation_required", "Tabulação obrigatória");
        Call call = calls.findByIdForUpdate(callId).orElseThrow(() -> ResourceNotFoundException.of("call", callId));
        callAccess.requireOperator(call);
        if (call.getCaseId() == null) {
            Guard.require(command.caseId() != null, "call.case_required", "Informe o caso da chamada recebida");
            CaseView kase = cases.get(command.caseId());
            accessScope.requireCreditor(kase.creditorId());
            call.identify(kase.debtorId(), kase.creditorId(), kase.portfolioId(), kase.id(), null);
            recordings.findByCallId(callId).ifPresent(r -> {
                r.linkDebtor(kase.debtorId());
                recordings.save(r);
            });
        } else if (call.getCreditorId() != null) {
            accessScope.requireCreditor(call.getCreditorId());
        }
        Instant now = Instant.now(clock);
        call.tabulate(command.tabulationCode() == null ? null : command.tabulationCode().trim(), callAccess.actorId(), now);
        RecordResult result = caseInteractions.recordByOperator(call,
                command.note(),
                command.promise() == null ? null
                        : new InteractionUseCases.PromiseInput(command.promise().amount(), command.promise().dueDate()),
                command.callback() == null ? null : new InteractionUseCases.CallbackInput(command.callback().dueAt(),
                        command.callback().assigneeUserId(), command.callback().note()));
        call.interactionRecorded(result.interaction().id());
        calls.save(call);
        jobQueue.cancelPending(TabulationDeadlineJob.TYPE, TabulationDeadlineJob.key(call.getId()));
        metrics.tabulation("operator");
        if (call.getWrapUpSeconds() != null) {
            metrics.wrapUpTime(call.getOrigin().name(), call.getWrapUpSeconds());
        }
        agents.findByUserIdForUpdate(call.getOperatorId()).ifPresent(agent -> {
            presence.record(agent.wrapUpDone(call.getId(), now), "tabulation");
            agents.save(agent);
        });
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.CALL_TABULATED)
                .creditor(call.getCreditorId()).portfolio(call.getPortfolioId()).debtor(call.getDebtorId())
                .collectionCase(call.getCaseId()).reference("call", call.getId()).channel("PHONE")
                .durationMs(call.getWrapUpSeconds() == null ? 0 : call.getWrapUpSeconds() * 1000L)
                .attr("mode", "operator").attr("tabulationCode", call.getTabulationCode())
                .attr("result", call.getResult()).attr("origin", call.getOrigin())
                .attr("effectiveContact", result.interaction().effectiveContact())
                .attr("promise", result.promise() != null).attr("callback", result.callback() != null)
                .attr("operatorId", call.getOperatorId()).attr("wrapUpSeconds", call.getWrapUpSeconds()));
        return CallViews.call(call, recordings.findByCallId(callId).orElse(null), null);
    }

    @Override
    public RecordingFile openRecording(UUID callId, String purpose) {
        accessScope.requirePermission(Permission.CALL_RECORDING_LISTEN);
        RecordingPurpose why = TelephonyEnums.parse(RecordingPurpose.class, purpose, "recording.purpose_invalid",
                "Finalidade");
        Call call = calls.findById(callId).orElseThrow(() -> ResourceNotFoundException.of("call", callId));
        callAccess.requireVisible(call);
        CallRecording recording = recordings.findByCallId(callId)
                .orElseThrow(() -> ResourceNotFoundException.of("call_recording", callId));
        Guard.requireState(recording.getStatus() == RecordingStatus.STORED, "recording.unavailable",
                recording.getStatus() == RecordingStatus.PURGED ? "Gravação descartada pela política de retenção"
                        : "Gravação ainda não disponível");
        recording.accessed(Instant.now(clock));
        recordings.save(recording);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("resource", "call_recording");
        details.put("recordingId", recording.getId().toString());
        details.put("callId", call.getId().toString());
        details.put("category", "CALL_RECORDING");
        details.put("purpose", why.name());
        if (call.getCreditorId() != null) {
            details.put("creditorId", call.getCreditorId().toString());
        }
        accessLogger.log(AccessEvent.of(AccessEventType.FILE_DOWNLOADED, currentActor.get(), details));
        metrics.recordingAccess(why.name());
        return new RecordingFile("gravacao-" + call.getId() + extension(recording.getContentType()),
                recording.getContentType(), recording.getSizeBytes() == null ? -1 : recording.getSizeBytes(),
                recording.getSha256(), storage.open(recording.getFileKey()));
    }

    private static String extension(String contentType) {
        if (contentType == null) {
            return ".wav";
        }
        return switch (contentType) {
            case "audio/mpeg", "audio/mp3" -> ".mp3";
            case "audio/ogg", "audio/opus" -> ".ogg";
            case "audio/webm" -> ".webm";
            default -> ".wav";
        };
    }

    private static final class Holder {
        private CallView view;
    }
}
