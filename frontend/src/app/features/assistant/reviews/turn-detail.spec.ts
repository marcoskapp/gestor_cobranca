import { button, render, setup, text } from '../../../../testing/component-harness';
import { TurnDetail } from '../data/assistant.models';
import { TurnDetailPage } from './turn-detail';

const detail: TurnDetail = {
  turn: {
    id: 't-1', conversationId: 'cv-1', policyId: 'pol-1', policyVersion: 2, promptCode: 'negociacao', promptVersion: 1, mode: 'AUTONOMOUS', episode: 1, turnNumber: 3, outcome: 'HANDED_OFF',
    handoffReason: 'GUARDRAIL', triggerSource: 'GUARDRAIL', guardrails: ['VALUE_NOT_FROM_ENGINE'], intent: 'NEGOTIATE', confidence: 0.82, provider: 'SANDBOX', model: 'sandbox-1', modelCalls: 2, toolCalls: 1,
    inputTokens: 900, outputTokens: 120, cost: 0.01, costCurrency: 'USD', modelLatencyMs: 800, totalLatencyMs: 1200, outboundMessageId: null, draftId: null, proposalId: null, agreementId: null, errorCode: null,
    contextSha256: 'ab'.repeat(32), contextVersion: 1, startedAt: '2026-10-24T12:00:00Z', finishedAt: '2026-10-24T12:00:01Z', contentPurged: false,
  },
  inputText: 'Consigo pagar à vista?',
  context: '{"saldo":"1.200,00"}',
  outputText: null,
  toolCalls: [{ sequence: 1, tool: 'simulate_options', arguments: '{"forms":[1]}', result: '{"offers":["O1"]}', outcome: 'OK', errorCode: null, referenceType: null, referenceId: null, durationMs: 40 }],
};

const respond = (publishedBy: string) => (url: string) => {
  if (url.endsWith('/assistant/turns/t-1')) return detail;
  if (url.endsWith('/assistant/policies')) return [{ id: 'pol-1', name: 'Varejo', policyVersion: 2, publishedBy }];
  return undefined;
};

describe('TurnDetailPage (turno do assistente)', () => {
  it('mostra o aviso de acesso registrado, guardrails e ferramentas; avalia com nota e veredito', async () => {
    const { http } = setup(TurnDetailPage, ['ASSISTANT_REVIEW'], 'u-1');
    const fixture = await render(TurnDetailPage, http, respond('u-9'), { id: 't-1', revisao: 'rv-1' });
    const t = text(fixture);
    expect(t).toContain('Este acesso foi registrado');
    expect(t).toContain('Valor fora do motor');
    expect(t).toContain('Simular opções');
    button(fixture, 'Registrar avaliação')!.click();
    fixture.detectChanges();
    expect(text(fixture)).toContain('Dê uma nota de 1 a 5');
    http.expectNone((r) => r.method === 'POST');
  });

  it('quem publicou a política do turno não avalia', async () => {
    const { http } = setup(TurnDetailPage, ['ASSISTANT_REVIEW'], 'u-1');
    const fixture = await render(TurnDetailPage, http, respond('u-1'), { id: 't-1', revisao: 'rv-1' });
    expect(text(fixture)).toContain('Você publicou a política deste turno');
    expect(button(fixture, 'Registrar avaliação')).toBeUndefined();
  });

  it('mostra os critérios do Pix avaliados pelo servidor', async () => {
    const { http } = setup(TurnDetailPage, ['ASSISTANT_REVIEW'], 'u-1');
    const withPix: TurnDetail = {
      ...detail,
      pixRequests: [{
        id: 'px-1', decision: 'DENIED', denialCode: 'AMOUNT_ABOVE_LIMIT', agreementId: 'ag-1', installmentNumber: 2, amount: 6200,
        instructionId: null, reusedInstruction: false, expiresAt: null, deliveryStatus: null, deliveryCode: null, deliveredAt: null, paidAt: null,
        requestedAt: '2026-10-24T12:00:00Z',
        criteria: [
          { criterion: 'IDENTITY_VERIFIED', passed: true, detail: 'VERIFIED' },
          { criterion: 'AMOUNT_WITHIN_LIMIT', passed: false, detail: 'limite 5000.00' },
        ],
      }],
    };
    const fixture = await render(TurnDetailPage, http, (url: string) => (url.endsWith('/assistant/turns/t-1') ? withPix : respond('u-9')(url)),
      { id: 't-1', revisao: 'rv-1' });
    const t = text(fixture);
    expect(t).toContain('Pix na conversa');
    expect(t).toContain('Valor acima do limite');
    expect(t).toContain('Identidade confirmada na sessão');
    expect(t).toContain('Não atendido:');
    expect(t).toContain('limite 5000.00');
  });
});
