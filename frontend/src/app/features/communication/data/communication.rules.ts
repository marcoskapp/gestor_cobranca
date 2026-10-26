/**
 * Regras de tela da caixa de entrada (funções puras). Orientam o operador; o servidor valida tudo
 * de novo (janela do WhatsApp, porteiro de contato, dono da conversa, escopo).
 */
import { CHANNEL_LABELS, DENIAL_REASON_LABELS, FAILURE_LABELS, SENDER_LABELS } from './communication.labels';
import { ChannelType, ConversationView, HandoffView, InboxFilter, MessageView, ProviderCategory, SlaStatus, TemplateView, VariableInput } from './communication.models';

export type InboxTab = 'mine' | 'queue' | 'assistant' | 'all';

/** Filtro de dono da aba (minhas, fila, assistente, todas). */
export function tabFilter(tab: InboxTab): Pick<InboxFilter, 'owner' | 'assignee'> {
  switch (tab) {
    case 'mine':
      return { owner: 'USER', assignee: 'me' };
    case 'queue':
      return { owner: 'QUEUE,NONE', assignee: null };
    case 'assistant':
      return { owner: 'ASSISTANT', assignee: null };
    default:
      return { owner: null, assignee: null };
  }
}

export type SlaTone = 'ok' | 'warning' | 'late';

const SLA_TONES: Record<SlaStatus, SlaTone> = { OK: 'ok', WARNING: 'warning', BREACHED: 'late' };

/**
 * Tom do SLA de primeira resposta. A classificação vem do servidor, pelas metas da assessoria para
 * o canal (`/communication/response-targets`; padrões WhatsApp/SMS 15/60 min, e-mail 4/24 h).
 */
export function slaTone(c: Pick<ConversationView, 'slaStatus'>): SlaTone | null {
  return c.slaStatus ? SLA_TONES[c.slaStatus] : null;
}

/** Explicação do prazo (para tooltip/leitor de tela), ex.: "Meta estoura às 14:35". */
export function slaHint(c: Pick<ConversationView, 'slaStatus' | 'slaBreachAt'>, format: (iso: string) => string): string | null {
  if (!c.slaStatus || !c.slaBreachAt) {
    return null;
  }
  return c.slaStatus === 'BREACHED' ? `Meta de primeira resposta estourada às ${format(c.slaBreachAt)}` : `Meta de primeira resposta estoura às ${format(c.slaBreachAt)}`;
}

/** Duração curta em pt-BR ("8 min", "2 h 5 min", "3 d"). */
export function shortDuration(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  if (s < 60) {
    return 'menos de 1 min';
  }
  const m = Math.floor(s / 60);
  if (m < 60) {
    return `${m} min`;
  }
  const h = Math.floor(m / 60);
  if (h < 24) {
    return m % 60 ? `${h} h ${m % 60} min` : `${h} h`;
  }
  return `${Math.floor(h / 24)} d`;
}

export function waitingText(c: Pick<ConversationView, 'waitingSeconds'>): string | null {
  return c.waitingSeconds === null || c.waitingSeconds === undefined ? null : `aguardando há ${shortDuration(c.waitingSeconds)}`;
}

export type ComposerMode = 'free-text' | 'template';

/** Texto livre só no WhatsApp, dentro da janela de 24 h desde a última mensagem do devedor. */
export function composerMode(c: Pick<ConversationView, 'channel' | 'sessionWindowOpen' | 'sessionWindowEndsAt'>, now: Date = new Date()): ComposerMode {
  if (c.channel !== 'WHATSAPP' || !c.sessionWindowOpen) {
    return 'template';
  }
  return !c.sessionWindowEndsAt || new Date(c.sessionWindowEndsAt).getTime() > now.getTime() ? 'free-text' : 'template';
}

/** Explicação da janela do WhatsApp (ou da regra dos demais canais). */
export function windowText(c: Pick<ConversationView, 'channel' | 'sessionWindowOpen' | 'sessionWindowEndsAt'>, now: Date = new Date()): string {
  if (c.channel !== 'WHATSAPP') {
    return `${CHANNEL_LABELS[c.channel]}: envio somente por template aprovado.`;
  }
  if (composerMode(c, now) === 'free-text') {
    if (!c.sessionWindowEndsAt) {
      return 'Janela de 24 h do WhatsApp aberta: você pode escrever livremente.';
    }
    const left = (new Date(c.sessionWindowEndsAt).getTime() - now.getTime()) / 1000;
    return `Janela de 24 h do WhatsApp aberta por mais ${shortDuration(left)}: você pode escrever livremente.`;
  }
  return 'Janela de 24 h do WhatsApp fechada: para iniciar o contato, use um template aprovado.';
}

/** Motivo do bloqueio em linguagem clara (a partir de `failureCode` e `failureDetail`). */
export function blockText(m: Pick<MessageView, 'status' | 'failureCode' | 'failureDetail'>): string | null {
  if (m.status !== 'FAILED') {
    return null;
  }
  if (m.failureCode === 'CONTACT_DENIED') {
    const reasons = (m.failureDetail ?? '')
      .split(',')
      .map((r) => r.trim())
      .filter(Boolean)
      .map((r) => DENIAL_REASON_LABELS[r] ?? r.toLowerCase().replace(/_/g, ' '));
    return reasons.length ? `Não enviada: ${reasons.join('; ')}.` : 'Não enviada: bloqueada pela política de contato.';
  }
  const known = m.failureCode ? FAILURE_LABELS[m.failureCode] : null;
  if (known) {
    return `Não enviada: ${known.charAt(0).toLowerCase()}${known.slice(1)}.`;
  }
  return m.failureCode?.startsWith('WHATSAPP_') ? `Não enviada: o WhatsApp recusou a mensagem (código ${m.failureCode.slice(9)}).` : 'Não enviada.';
}

/** Quem enviou, sempre por texto (o assistente é identificado como "Assistente"). */
export function senderText(m: Pick<MessageView, 'senderType' | 'senderId'>, userId: string | null, userName: (id: string) => string): string {
  if (m.senderType === 'USER') {
    return m.senderId && m.senderId === userId ? 'Você' : m.senderId ? userName(m.senderId) : 'Operador';
  }
  return SENDER_LABELS[m.senderType] ?? m.senderType;
}

export type TimelineItem =
  | { kind: 'message'; at: string; message: MessageView }
  | { kind: 'event'; at: string; handoff: HandoffView };

/**
 * Linha do tempo única do devedor: mensagens de todas as conversas (todos os canais) e mudanças de
 * dono, em ordem cronológica, sem duplicar mensagens repetidas entre páginas.
 */
export function mergeTimeline(messages: readonly MessageView[], handoffs: readonly HandoffView[]): TimelineItem[] {
  const seen = new Set<string>();
  const items: TimelineItem[] = [];
  for (const m of messages) {
    if (!seen.has(m.id)) {
      seen.add(m.id);
      items.push({ kind: 'message', at: m.receivedAt ?? m.createdAt, message: m });
    }
  }
  for (const h of handoffs) {
    if (h.kind !== 'CASE_SYNC') {
      items.push({ kind: 'event', at: h.occurredAt, handoff: h });
    }
  }
  return items.sort((a, b) => a.at.localeCompare(b.at));
}

/** Mensagens recebidas que não estavam na lista anterior (para o anúncio discreto). */
export function newInbound(previous: readonly MessageView[] | null, current: readonly MessageView[]): MessageView[] {
  if (!previous) {
    return [];
  }
  const known = new Set(previous.map((m) => m.id));
  return current.filter((m) => m.direction === 'INBOUND' && !known.has(m.id));
}

/** Contagem de caracteres e segmentos (SMS: 160 por segmento, 153 quando concatenado). */
export function charCount(channel: ChannelType, text: string): { chars: number; limit: number; segments: number | null } {
  const chars = [...text].length;
  if (channel === 'SMS') {
    return { chars, limit: 4096, segments: chars <= 160 ? 1 : Math.ceil(chars / 153) };
  }
  return { chars, limit: 4096, segments: null };
}

/** Variáveis a preencher no envio (ordem declarada = ordem dos parâmetros do WhatsApp). */
export function templateVariables(t: Pick<TemplateView, 'variables'> | null | undefined): VariableInput[] {
  return t ? [...t.variables] : [];
}

/** Variáveis usadas no corpo/assunto (`{{nome}}`), na ordem em que aparecem. */
export function usedVariables(text: string): string[] {
  const names: string[] = [];
  for (const match of text.matchAll(/\{\{\s*([A-Za-z0-9_]+)\s*\}\}/g)) {
    if (!names.includes(match[1])) {
      names.push(match[1]);
    }
  }
  return names;
}

/** Coerência entre variáveis declaradas e usadas (o servidor exige as duas direções). */
export function variableProblems(body: string, subject: string | null, declared: readonly Pick<VariableInput, 'name'>[]): string[] {
  const used = usedVariables(`${subject ?? ''} ${body}`);
  const names = declared.map((v) => v.name);
  const problems: string[] = [];
  for (const u of used.filter((n) => !names.includes(n))) {
    problems.push(`A variável {{${u}}} é usada mas não foi declarada.`);
  }
  for (const n of names.filter((d) => !used.includes(d))) {
    problems.push(`A variável ${n} foi declarada mas não aparece no texto.`);
  }
  const dup = names.filter((n, i) => names.indexOf(n) !== i);
  for (const d of [...new Set(dup)]) {
    problems.push(`A variável ${d} foi declarada mais de uma vez.`);
  }
  return problems;
}

/** Tamanho máximo do código num template de autenticação da Meta (`ProviderTemplateRules.OTP_MAX_LENGTH`). */
export const OTP_MAX_LENGTH = 15;

/**
 * Formato da categoria no provedor (mesmas regras de `ProviderTemplateRules`): o template de
 * autenticação tem uma única variável, o código (tipo código, obrigatória); a validade é configuração
 * do template na Meta, não variável.
 */
export function categoryProblems(
  channel: ChannelType,
  category: ProviderCategory | null,
  declared: readonly Pick<VariableInput, 'name' | 'type' | 'required'>[],
): string[] {
  if (channel !== 'WHATSAPP' || category !== 'AUTHENTICATION') {
    return [];
  }
  if (declared.length !== 1 || declared[0].type !== 'CODE' || !declared[0].required) {
    return ['Template de autenticação tem uma única variável: o código (tipo código, obrigatória). A validade é configurada no próprio template na Meta.'];
  }
  return [];
}

/** Chave de idempotência do envio (repetir o mesmo envio não duplica a mensagem). */
export function idempotencyKey(): string {
  const c = globalThis.crypto;
  return c?.randomUUID ? c.randomUUID() : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}
