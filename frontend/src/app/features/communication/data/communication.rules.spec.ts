import { applicableTemplates } from '../inbox/composer';
import { HandoffView, MessageView, TemplateView, VariableInput } from './communication.models';
import {
  StationContact,
  blockText,
  channelContacts,
  firstName,
  ownerText,
  prefillVariables,
  stationChannels,
  categoryProblems,
  charCount,
  composerMode,
  mergeTimeline,
  newInbound,
  senderText,
  shortDuration,
  slaHint,
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

  it('SLA de primeira resposta segue a classificação do servidor (metas da assessoria)', () => {
    expect(slaTone({ slaStatus: 'OK' })).toBe('ok');
    expect(slaTone({ slaStatus: 'WARNING' })).toBe('warning');
    expect(slaTone({ slaStatus: 'BREACHED' })).toBe('late');
    expect(slaTone({ slaStatus: null })).toBeNull();
    expect(slaHint({ slaStatus: 'WARNING', slaBreachAt: '2026-10-23T17:35:00Z' }, () => '14:35')).toBe('Meta de primeira resposta estoura às 14:35');
    expect(slaHint({ slaStatus: 'BREACHED', slaBreachAt: '2026-10-23T17:35:00Z' }, () => '14:35')).toContain('estourada');
    expect(slaHint({ slaStatus: null, slaBreachAt: null }, () => '')).toBeNull();
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

  it('template de autenticação da Meta declara só o código', () => {
    const code = { name: 'codigo', type: 'CODE' as const, required: true };
    expect(categoryProblems('WHATSAPP', 'AUTHENTICATION', [code])).toEqual([]);
    expect(categoryProblems('WHATSAPP', 'AUTHENTICATION', [code, { name: 'minutos', type: 'INTEGER', required: true }])[0]).toContain('única variável');
    expect(categoryProblems('WHATSAPP', 'AUTHENTICATION', [{ ...code, type: 'TEXT' }])).toHaveLength(1);
    expect(categoryProblems('WHATSAPP', 'UTILITY', [code, { name: 'minutos', type: 'INTEGER', required: true }])).toEqual([]);
    expect(categoryProblems('SMS', null, [code, { name: 'minutos', type: 'INTEGER', required: true }])).toEqual([]);
  });

  it('template do credor tem precedência sobre o da assessoria; de outro credor não aparece', () => {
    const list = [template('1', 'LEMBRETE', null, 'Lembrete'), template('2', 'LEMBRETE', 'c-1', 'Lembrete Horizonte'), template('3', 'BOAS_VINDAS', null, 'Boas-vindas'), template('4', 'OUTRO', 'c-2')];
    expect(applicableTemplates(list, 'c-1').map((t) => t.id)).toEqual(['3', '2']);
    expect(applicableTemplates(list, null).map((t) => t.id)).toEqual(['3', '1']);
  });

  describe('contato ativo pela estação', () => {
    const phone = (id: string, o: Partial<StationContact> = {}): StationContact => ({ contactId: id, type: 'PHONE', label: 'MOBILE', whatsapp: false, status: 'VALID', priority: 1, ...o });
    const contacts: StationContact[] = [
      phone('wa-2', { whatsapp: true, priority: 2 }),
      phone('wa-1', { whatsapp: true, priority: 1, status: 'UNVERIFIED' }),
      phone('home', { label: 'HOME', priority: 3 }),
      phone('dead', { whatsapp: true, status: 'INVALID', priority: 0 }),
      { contactId: 'mail', type: 'EMAIL', label: 'OTHER', whatsapp: false, status: 'VALID', priority: 1 },
      { contactId: 'addr', type: 'ADDRESS', label: 'HOME', whatsapp: false, status: 'VALID', priority: 1 },
    ];

    it('contatos por canal seguem a regra do servidor, por prioridade e sem contato inválido', () => {
      expect(channelContacts(contacts, 'WHATSAPP').map((c) => c.contactId)).toEqual(['wa-1', 'wa-2']);
      // SMS: celular ou WhatsApp; telefone fixo não
      expect(channelContacts(contacts, 'SMS').map((c) => c.contactId)).toEqual(['wa-1', 'wa-2']);
      expect(channelContacts(contacts, 'EMAIL').map((c) => c.contactId)).toEqual(['mail']);
    });

    it('oferece só os canais com contato utilizável, na ordem WhatsApp, SMS, e-mail', () => {
      expect(stationChannels(contacts)).toEqual(['WHATSAPP', 'SMS', 'EMAIL']);
      expect(stationChannels([phone('fixo', { label: 'HOME' })])).toEqual([]);
      expect(stationChannels([contacts[4]])).toEqual(['EMAIL']);
    });

    it('primeiro nome como no envio das campanhas', () => {
      expect(firstName('  Ana Paula Ribeiro')).toBe('Ana');
      expect(firstName("D'Ávila Souza")).toBe("D'Ávila");
      expect(firstName('-João')).toBe('João');
      expect(firstName('123')).toBeNull();
      expect(firstName(null)).toBeNull();
    });

    it('pré-preenche só o primeiro nome; valores, datas e links ficam com o operador', () => {
      const vars: VariableInput[] = [
        { name: 'nome', type: 'FIRST_NAME', required: true, maxLength: null },
        { name: 'curto', type: 'FIRST_NAME', required: false, maxLength: 2 },
        { name: 'valor', type: 'MONEY', required: true, maxLength: null },
      ];
      expect(prefillVariables(vars, 'Maria Clara')).toEqual({ nome: 'Maria', curto: 'Ma' });
      expect(prefillVariables(vars, '')).toEqual({});
    });

    it('quem atende a conversa', () => {
      const name = (id: string) => (id === 'u-2' ? 'Bruno' : '…');
      const base = { status: 'OPEN' as const, ownerType: 'USER' as const, ownerUserId: 'u-1', requiredProfile: null };
      expect(ownerText(base, 'u-1', name)).toBe('Com você');
      expect(ownerText({ ...base, ownerUserId: 'u-2' }, 'u-1', name)).toBe('Com Bruno');
      expect(ownerText({ ...base, ownerType: 'QUEUE', requiredProfile: 'SUPERVISOR' }, 'u-1', name)).toBe('Na fila (exige SUPERVISOR)');
      expect(ownerText({ ...base, ownerType: 'ASSISTANT' }, 'u-1', name)).toBe('Com o assistente');
      expect(ownerText({ ...base, status: 'CLOSED' }, 'u-1', name)).toBe('Encerrada');
    });
  });
});
