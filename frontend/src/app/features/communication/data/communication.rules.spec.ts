import { applicableTemplates } from '../inbox/composer';
import { HandoffView, MessageView, TemplateView } from './communication.models';
import {
  blockText,
  charCount,
  composerMode,
  mergeTimeline,
  newInbound,
  senderText,
  shortDuration,
  slaTone,
  tabFilter,
  usedVariables,
  variableProblems,
  waitingText,
  windowText,
} from './communication.rules';

function message(overrides: Partial<MessageView> = {}): MessageView {
  return {
    id: 'm-1',
    conversationId: 'cv-1',
    channel: 'WHATSAPP',
    direction: 'INBOUND',
    senderType: 'DEBTOR',
    senderId: null,
    templateId: null,
    subject: null,
    content: 'Oi',
    contentPurged: false,
    status: 'RECEIVED',
    statusAt: null,
    failureCode: null,
    failureDetail: null,
    sendAttempts: 0,
    campaignExecutionId: null,
    queuedAt: null,
    sentAt: null,
    deliveredAt: null,
    readAt: null,
    failedAt: null,
    receivedAt: '2026-10-23T12:00:00Z',
    createdAt: '2026-10-23T12:00:00Z',
    ...overrides,
  };
}

const template = (id: string, code: string, creditorId: string | null, name = code): TemplateView =>
  ({ id, code, creditorId, name, channel: 'WHATSAPP', variables: [] }) as unknown as TemplateView;

describe('regras da caixa de entrada', () => {
  it('abas filtram por dono', () => {
    expect(tabFilter('mine')).toEqual({ owner: 'USER', assignee: 'me' });
    expect(tabFilter('queue')).toEqual({ owner: 'QUEUE,NONE', assignee: null });
    expect(tabFilter('assistant').owner).toBe('ASSISTANT');
    expect(tabFilter('all')).toEqual({ owner: null, assignee: null });
  });

  it('SLA de primeira resposta por canal', () => {
    expect(slaTone('WHATSAPP', 60)).toBe('ok');
    expect(slaTone('WHATSAPP', 20 * 60)).toBe('warning');
    expect(slaTone('SMS', 2 * 3600)).toBe('late');
    expect(slaTone('EMAIL', 2 * 3600)).toBe('ok');
    expect(slaTone('EMAIL', 25 * 3600)).toBe('late');
    expect(slaTone('WHATSAPP', null)).toBeNull();
    expect(waitingText({ waitingSeconds: 3900 })).toBe('aguardando há 1 h 5 min');
    expect(shortDuration(30)).toBe('menos de 1 min');
    expect(shortDuration(3 * 86400)).toBe('3 d');
  });

  it('texto livre só no WhatsApp com a janela de 24 h aberta', () => {
    const now = new Date('2026-10-23T12:00:00Z');
    const open = { channel: 'WHATSAPP' as const, sessionWindowOpen: true, sessionWindowEndsAt: '2026-10-23T20:00:00Z' };
    expect(composerMode(open, now)).toBe('free-text');
    expect(windowText(open, now)).toContain('mais 8 h');
    expect(composerMode({ ...open, sessionWindowEndsAt: '2026-10-23T11:00:00Z' }, now)).toBe('template');
    expect(composerMode({ ...open, sessionWindowOpen: false }, now)).toBe('template');
    expect(composerMode({ ...open, channel: 'EMAIL' }, now)).toBe('template');
    expect(windowText({ ...open, channel: 'EMAIL' }, now)).toBe('E-mail: envio somente por template aprovado.');
    expect(windowText({ ...open, sessionWindowOpen: false }, now)).toContain('fechada');
  });

  it('bloqueio do porteiro explicado em linguagem clara, sem códigos', () => {
    expect(blockText(message({ status: 'FAILED', failureCode: 'CONTACT_DENIED', failureDetail: 'OUTSIDE_WINDOW,DAILY_LIMIT' }))).toBe(
      'Não enviada: está fora do horário permitido para contato; o limite de contatos do dia já foi atingido.',
    );
    expect(blockText(message({ status: 'FAILED', failureCode: 'WHATSAPP_WINDOW_CLOSED' }))).toBe('Não enviada: janela de 24 h do WhatsApp fechada: use um template.');
    expect(blockText(message({ status: 'FAILED', failureCode: 'WHATSAPP_131047' }))).toContain('código 131047');
    expect(blockText(message({ status: 'SENT' }))).toBeNull();
  });

  it('remetente sempre por texto, com o assistente identificado', () => {
    const name = (id: string) => `Usuário ${id}`;
    expect(senderText({ senderType: 'ASSISTANT', senderId: 'ap-1' }, 'u-1', name)).toBe('Assistente');
    expect(senderText({ senderType: 'USER', senderId: 'u-1' }, 'u-1', name)).toBe('Você');
    expect(senderText({ senderType: 'USER', senderId: 'u-2' }, 'u-1', name)).toBe('Usuário u-2');
    expect(senderText({ senderType: 'CAMPAIGN', senderId: null }, 'u-1', name)).toBe('Campanha');
  });

  it('linha do tempo única: mensagens de várias conversas e transferências, sem duplicar', () => {
    const a = message({ id: 'a', receivedAt: '2026-10-23T10:00:00Z' });
    const b = message({ id: 'b', channel: 'EMAIL', direction: 'OUTBOUND', receivedAt: null, createdAt: '2026-10-23T11:00:00Z' });
    const h = { id: 'h', kind: 'TAKE_OVER', occurredAt: '2026-10-23T10:30:00Z' } as HandoffView;
    const sync = { id: 's', kind: 'CASE_SYNC', occurredAt: '2026-10-23T10:31:00Z' } as HandoffView;
    const items = mergeTimeline([b, a, a], [h, sync]);
    expect(items.map((i) => (i.kind === 'message' ? i.message.id : i.handoff.id))).toEqual(['a', 'h', 'b']);
  });

  it('anúncio só para mensagens recebidas novas (nunca na primeira carga)', () => {
    const a = message({ id: 'a' });
    const out = message({ id: 'o', direction: 'OUTBOUND' });
    const fresh = message({ id: 'n' });
    expect(newInbound(null, [a])).toEqual([]);
    expect(newInbound([a], [a, out, fresh]).map((m) => m.id)).toEqual(['n']);
  });

  it('contagem de caracteres e segmentos de SMS', () => {
    expect(charCount('SMS', 'x'.repeat(160)).segments).toBe(1);
    expect(charCount('SMS', 'x'.repeat(161)).segments).toBe(2);
    expect(charCount('WHATSAPP', 'olá').segments).toBeNull();
  });

  it('variáveis declaradas e usadas precisam coincidir', () => {
    expect(usedVariables('Olá {{nome}}, pague {{ valor }} até {{data}}. {{nome}}')).toEqual(['nome', 'valor', 'data']);
    expect(variableProblems('Olá {{nome}}', null, [{ name: 'nome' }])).toEqual([]);
    expect(variableProblems('Olá {{nome}} {{valor}}', null, [{ name: 'nome' }, { name: 'extra' }])).toEqual([
      'A variável {{valor}} é usada mas não foi declarada.',
      'A variável extra foi declarada mas não aparece no texto.',
    ]);
    expect(variableProblems('{{a}}', 'Assunto {{b}}', [{ name: 'a' }, { name: 'b' }, { name: 'a' }])).toContain('A variável a foi declarada mais de uma vez.');
  });

  it('template do credor tem precedência sobre o da assessoria; de outro credor não aparece', () => {
    const list = [template('1', 'LEMBRETE', null, 'Lembrete'), template('2', 'LEMBRETE', 'c-1', 'Lembrete Horizonte'), template('3', 'BOAS_VINDAS', null, 'Boas-vindas'), template('4', 'OUTRO', 'c-2')];
    expect(applicableTemplates(list, 'c-1').map((t) => t.id)).toEqual(['3', '2']);
    expect(applicableTemplates(list, null).map((t) => t.id)).toEqual(['3', '1']);
  });
});
