import { HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { CadenceStep, CadenceView, CampaignPanel, CampaignView, ExperimentView, SegmentView } from '../app/features/campaign/data/campaign.models';
import { emptyCriteria } from '../app/features/campaign/data/campaign.rules';

export const tick = (ms = 0) => new Promise((r) => setTimeout(r, ms));

export type Handler = (req: TestRequest) => unknown;

/**
 * Responde às requisições pendentes pelo par "MÉTODO url" (ou só a url, para GET). Requisição sem
 * resposta prevista falha o teste: nenhuma chamada inesperada passa despercebida.
 */
export function flushAll(http: HttpTestingController, handlers: Record<string, Handler | unknown>): TestRequest[] {
  const handled: TestRequest[] = [];
  for (const req of http.match(() => true)) {
    const key = `${req.request.method} ${req.request.url}`;
    const entry = key in handlers ? handlers[key] : req.request.method === 'GET' && req.request.url in handlers ? handlers[req.request.url] : undefined;
    if (entry === undefined) {
      throw new Error(`requisição inesperada ${key}`);
    }
    const body = typeof entry === 'function' ? (entry as Handler)(req) : entry;
    if (!req.cancelled) {
      req.flush(body as never);
    }
    handled.push(req);
  }
  return handled;
}

export const page = <T>(content: T[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 });

export function segment(overrides: Partial<SegmentView> = {}): SegmentView {
  return {
    id: 's-1',
    creditorId: 'c-1',
    code: 'ATRASO_30_90',
    name: 'Atraso 30 a 90',
    description: null,
    criteria: { ...emptyCriteria(), minDaysOverdue: 30, maxDaysOverdue: 90 },
    active: true,
    createdAt: '2026-10-20T12:00:00Z',
    updatedAt: null,
    version: 1,
    ...overrides,
  };
}

export function campaign(overrides: Partial<CampaignView> = {}): CampaignView {
  return {
    id: 'cp-1',
    code: 'REGUA_ATRASO',
    name: 'Régua de atraso',
    description: null,
    creditorId: 'c-1',
    portfolioId: null,
    segmentId: 's-1',
    purpose: 'EXTRAJUDICIAL_COLLECTION',
    status: 'DRAFT',
    priority: 100,
    exclusivityGroup: 'COBRANCA',
    reenrollAfterDays: null,
    attributionWindowDays: 7,
    paymentPauseDays: null,
    maxDeferralHours: 24,
    exitWhenOutOfSegment: true,
    publishedCadenceId: 'cd-1',
    publishedCadenceVersion: 1,
    hasDraft: false,
    activatedAt: null,
    pausedAt: null,
    pauseNote: null,
    finishedAt: null,
    createdAt: '2026-10-20T12:00:00Z',
    version: 1,
    ...overrides,
  };
}

export function messageStep(overrides: Partial<CadenceStep> = {}): CadenceStep {
  return {
    code: 'D0',
    name: 'Primeiro contato',
    waitMinutes: 0,
    window: { start: '09:00:00', end: '12:00:00', days: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'] },
    condition: null,
    action: { type: 'SEND_MESSAGE', channel: 'WHATSAPP', templateCode: 'COBRANCA', variables: { nome: 'DEBTOR_FIRST_NAME', valor: 'OPEN_BALANCE' }, taskType: null, taskDueInMinutes: null, taskNote: null, teamId: null, offer: null },
    onReply: null,
    onReplyStepCode: null,
    ...overrides,
  };
}

export function cadence(overrides: Partial<CadenceView> = {}): CadenceView {
  return {
    id: 'cd-1',
    campaignId: 'cp-1',
    versionNumber: 1,
    status: 'PUBLISHED',
    definition: { defaultOnReply: 'STOP', defaultOnReplyStepCode: null, replyTeamId: null, steps: [messageStep()] },
    publishedAt: '2026-10-21T12:00:00Z',
    publishedBy: null,
    retiredAt: null,
    createdAt: '2026-10-21T11:00:00Z',
    version: 1,
    ...overrides,
  };
}

export const templateView = (overrides: Record<string, unknown> = {}) => ({
  id: 't-1',
  creditorId: null,
  code: 'COBRANCA',
  templateVersion: 2,
  channel: 'WHATSAPP',
  name: 'Cobrança amigável',
  purpose: 'COLLECTION',
  subject: null,
  body: 'Olá {{nome}}, seu saldo é {{valor}}.',
  variables: [
    { name: 'nome', type: 'FIRST_NAME', required: true, maxLength: null },
    { name: 'valor', type: 'MONEY', required: true, maxLength: null },
  ],
  language: 'pt_BR',
  status: 'PUBLISHED',
  providerTemplateName: null,
  providerCategory: 'UTILITY',
  approvalStatus: 'APPROVED',
  approvalReference: null,
  approvalNote: null,
  approvalDecidedAt: null,
  approvalRegisteredBy: null,
  publishedAt: '2026-10-01T12:00:00Z',
  publishedBy: null,
  retiredAt: null,
  createdAt: '2026-10-01T12:00:00Z',
  version: 1,
  ...overrides,
});

export function panel(overrides: Partial<CampaignPanel> = {}): CampaignPanel {
  return {
    campaignId: 'cp-1',
    computedAt: '2026-10-23T12:00:00Z',
    enrollments: 340,
    enrollmentsByStatus: { ACTIVE: 210, PAUSED: 30, FAILED: 12, HOLDOUT: 34, ENDED: 54 },
    pausedByReason: { AGREEMENT_FORMALIZED: 20, DISPUTE_OPENED: 10 },
    endedByReason: { REPLIED: 30, CADENCE_EXHAUSTED: 24 },
    suppressedByConcurrency: 18,
    steps: { executed: 820, skipped: 95, failed: 12 },
    byStep: [{ stepCode: 'D0', actionType: 'SEND_MESSAGE', channel: 'WHATSAPP', executed: 300, skipped: 40, failed: 12 }],
    skippedByReason: { CONTACT_DENIED: 60, NO_CONTACT: 35 },
    failedByReason: { 'message.template_not_found': 12 },
    messages: { sent: 700, delivered: 650, read: 400, failed: 20, blocked: 60 },
    outcomes: { repliedEnrollments: 90, effectiveContacts: 120, agreements: 25, agreementAmount: 48210.5, payingEnrollments: 18, recoveredAmount: 12890.4 },
    ...overrides,
  };
}

export function experiment(overrides: Partial<ExperimentView> = {}): ExperimentView {
  return {
    id: 'x-1',
    campaignId: 'cp-1',
    name: 'Horário do lembrete',
    hypothesis: null,
    primaryMetric: 'PAYMENT',
    status: 'RUNNING',
    controlShare: 10,
    variants: [
      { code: 'A', name: 'Régua atual', weight: 1, overrides: [] },
      { code: 'B', name: 'Manhã', weight: 1, overrides: [{ stepCode: 'D0', channel: null, templateCode: null, window: { start: '08:00:00', end: '10:00:00', days: [] }, offer: null }] },
    ],
    observationDays: 30,
    minSamplePerArm: 100,
    startedAt: '2026-09-01T12:00:00Z',
    stoppedAt: null,
    stopReason: null,
    createdAt: '2026-08-30T12:00:00Z',
    version: 1,
    ...overrides,
  };
}
