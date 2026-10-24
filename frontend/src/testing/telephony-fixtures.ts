import { AgentView, CallView } from '../app/features/telephony/data/telephony.models';

export function agentView(o: Partial<AgentView> = {}): AgentView {
  return { id: 'ag-1', userId: 'u-1', extension: '2001', active: true, status: 'OFFLINE', pauseReason: null, campaignId: null, currentCallId: null, statusSince: '2026-10-24T12:00:00Z', lastAvailableAt: null, lastHeartbeatAt: null, loggedInAt: null, version: 1, ...o };
}

export function callView(o: Partial<CallView> = {}): CallView {
  return {
    id: 'call-1', direction: 'OUTBOUND', origin: 'CLICK_TO_CALL', status: 'QUEUED', result: null, creditorId: 'c-1', portfolioId: 'p-1', debtorId: 'd-1', caseId: 'k-1', contactId: 'ct-1', operatorId: 'u-1',
    campaignId: null, numberMasked: '(11) 9****-1234', attemptNumber: 1, queuedAt: '2026-10-24T12:00:00Z', dialStartedAt: null, ringingAt: null, answeredAt: null, connectedAt: null, endedAt: null, tabulatedAt: null,
    ringSeconds: null, talkSeconds: null, wrapUpSeconds: null, amdResult: null, abandoned: false, hangupSide: null, failureCode: null, denialReasons: [], nextAllowedAt: null, recordingAnnounced: false,
    tabulationCode: null, suggestedTabulation: 'CALL_ANSWERED', interactionState: 'NONE', interactionId: null, recording: null, version: 1, ...o,
  };
}
