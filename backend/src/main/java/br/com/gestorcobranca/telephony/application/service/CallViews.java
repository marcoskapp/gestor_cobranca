package br.com.gestorcobranca.telephony.application.service;

import br.com.gestorcobranca.telephony.application.port.in.AgentUseCases.AgentView;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.CallView;
import br.com.gestorcobranca.telephony.application.port.in.CallUseCases.RecordingInfo;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.CampaignView;
import br.com.gestorcobranca.telephony.application.port.in.DialerCampaignUseCases.EntryView;
import br.com.gestorcobranca.telephony.domain.model.Agent;
import br.com.gestorcobranca.telephony.domain.model.Call;
import br.com.gestorcobranca.telephony.domain.model.CallRecording;
import br.com.gestorcobranca.telephony.domain.model.DialerCampaign;
import br.com.gestorcobranca.telephony.domain.model.DialerEntry;
import br.com.gestorcobranca.telephony.domain.model.InteractionState;
import br.com.gestorcobranca.telephony.domain.service.CallTabulations;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Conversão de agregados em views (nunca o número em claro nem credenciais). */
final class CallViews {

    private CallViews() {
    }

    static CallView call(Call c, CallRecording recording, Instant nextAllowedAt) {
        String suggested = c.getInteractionState() == InteractionState.AWAITING_TABULATION && c.getResult() != null
                ? CallTabulations.automatic(c.getResult()) : null;
        return new CallView(c.getId(), c.getDirection().name(), c.getOrigin().name(), c.getStatus().name(),
                name(c.getResult()), c.getCreditorId(), c.getPortfolioId(), c.getDebtorId(), c.getCaseId(),
                c.getContactId(), c.getOperatorId(), c.getCampaignId(), c.getNumberMasked(), c.getAttemptNumber(),
                c.getQueuedAt(), c.getDialStartedAt(), c.getRingingAt(), c.getAnsweredAt(), c.getConnectedAt(),
                c.getEndedAt(), c.getTabulatedAt(), c.getRingSeconds(), c.getTalkSeconds(), c.getWrapUpSeconds(),
                name(c.getAmdResult()), c.isAbandoned(), name(c.getHangupSide()), c.getFailureCode(),
                split(c.getDenialReasons()), nextAllowedAt, c.isRecordingAnnounced(), c.getTabulationCode(), suggested,
                c.getInteractionState().name(), c.getInteractionId(), recording == null ? null : recording(recording),
                c.getVersion() == null ? 0 : c.getVersion(), c.getAccountId(), c.getProvider().name(),
                c.getFallbackFromAccountId());
    }

    static RecordingInfo recording(CallRecording r) {
        return new RecordingInfo(r.getId(), r.getStatus().name(), r.getDurationSeconds(), r.isAnnounced(),
                r.getStoredAt(), r.getPurgedAt(), r.getPurgeReason());
    }

    static AgentView agent(Agent a) {
        return new AgentView(a.getId(), a.getUserId(), a.getExtension(), a.isActive(), a.getStatus().name(),
                name(a.getPauseReason()), a.getCampaignId(), a.getCurrentCallId(), a.getStatusSince(),
                a.getLastAvailableAt(), a.getLastHeartbeatAt(), a.getLoggedInAt(),
                a.getVersion() == null ? 0 : a.getVersion());
    }

    static CampaignView campaign(DialerCampaign c) {
        return new CampaignView(c.getId(), c.getCode(), c.getName(), c.getCreditorId(), c.getPortfolioId(),
                c.getQueueId(), c.isIncludeAssigned(), c.getMode().name(), c.getStatus().name(), c.getMaxDialRatio(),
                c.getMaxAbandonRate().value(), c.getMaxAttempts(), c.getRetryIntervalMinutes(),
                c.getRingTimeoutSeconds(), c.getWindowStart(), c.getWindowEnd(), c.getActivatedAt(), c.getPausedAt(),
                c.getFinishedAt(), c.getLastLoadedAt(), c.getEntriesLoaded(), c.getVersion() == null ? 0 : c.getVersion());
    }

    static EntryView entry(DialerEntry e) {
        return new EntryView(e.getId(), e.getCaseId(), e.getDebtorId(), e.getCreditorId(), e.getPortfolioId(),
                e.getStatus().name(), e.getPriority(), e.getAttempts(), e.getNextAttemptAt(), e.getLastCallId(),
                name(e.getLastResult()), e.getLastAttemptAt(), split(e.getLastDenial()), e.getExcludedReason(),
                e.getFinishedAt());
    }

    static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of() : Arrays.stream(csv.split(",")).map(String::trim).toList();
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }
}
