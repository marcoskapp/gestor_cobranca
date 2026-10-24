import { PolicySettings, PolicyView, ReviewView, ServiceWindow } from './assistant.models';

/**
 * Regras de apresentação do assistente. A validação espelha `PolicySettings.normalized()` para
 * orientar durante a edição; o servidor repete tudo em toda escrita.
 */

export const SUPPORTED_CHANNELS: readonly string[] = ['WHATSAPP'];

const plain = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();

function between(value: number | null | undefined, min: number, max: number): boolean {
  return value === null || value === undefined || (Number.isFinite(value) && value >= min && value <= max);
}

/** Problemas dos parâmetros da política (vazio = pode salvar e publicar). */
export function settingsProblems(s: PolicySettings): string[] {
  const out: string[] = [];
  if (!s.mode) {
    out.push('Escolha o modo de operação.');
  }
  for (const c of s.channels ?? []) {
    if (!SUPPORTED_CHANNELS.includes(c)) {
      out.push(`Canal não suportado: ${c} (somente WhatsApp).`);
    }
  }
  for (const w of s.serviceHours ?? []) {
    if (!w.days?.length || !w.start || !w.end) {
      out.push('Cada janela de atendimento precisa de dias, início e fim.');
    } else if (w.start > w.end) {
      out.push('Janela de atendimento não pode atravessar a meia-noite (use duas janelas).');
    }
  }
  if (!between(s.maxTurns, 1, 50)) out.push('Turnos máximos entre 1 e 50.');
  if (!between(s.maxMisunderstandings, 1, 5)) out.push('Falhas de entendimento entre 1 e 5.');
  if (!between(s.minConfidence, 0, 1)) out.push('Confiança mínima entre 0 e 1.');
  if (!between(s.maxIdentityAttempts, 1, 5)) out.push('Tentativas de identificação entre 1 e 5.');
  if (!between(s.reviewSampleRate, 0, 1)) out.push('Taxa de amostragem entre 0% e 100%.');
  for (const [label, list] of [
    ['permitido', s.allowedTopics],
    ['proibido', s.forbiddenTopics],
  ] as const) {
    if ((list ?? []).length > 50) out.push(`No máximo 50 temas (${label}).`);
    if ((list ?? []).some((t) => !t.trim() || t.trim().length > 60)) out.push(`Tema ${label} com até 60 caracteres.`);
  }
  for (const t of s.extraTriggers ?? []) {
    const k = (t.keyword ?? '').trim();
    if (k.length < 3 || k.length > 60) {
      out.push('Gatilho adicional com expressão de 3 a 60 caracteres.');
      break;
    }
  }
  for (const [reason, profile] of Object.entries(s.requiredProfiles ?? {})) {
    if (profile && !/^[A-Za-z0-9_]{1,40}$/.test(profile)) {
      out.push(`Perfil exigido inválido para ${reason}.`);
    }
  }
  const identification = s.identificationMessage?.trim();
  if (identification) {
    const p = plain(identification);
    if (!p.includes('assistente virtual') || !(p.includes('humano') || p.includes('atendente'))) {
      out.push('A apresentação precisa dizer "assistente virtual" e como pedir atendimento humano (humano/atendente).');
    }
  }
  for (const [label, text] of [
    ['apresentação', s.identificationMessage],
    ['transferência', s.handoffMessage],
    ['falha', s.fallbackMessage],
    ['aprovação', s.approvalMessage],
  ] as const) {
    if ((text ?? '').length > 600) out.push(`Mensagem de ${label} com até 600 caracteres.`);
  }
  const l = s.limits;
  if (l) {
    if (!between(l.maxOutputTokens, 50, 4000)) out.push('Tokens de saída por chamada entre 50 e 4.000.');
    if (!between(l.maxTokensPerTurn, 500, 64000)) out.push('Tokens por turno entre 500 e 64.000.');
    if (!between(l.maxToolCallsPerTurn, 1, 12)) out.push('Ferramentas por turno entre 1 e 12.');
    if (!between(l.timeoutSeconds, 2, 60)) out.push('Tempo por chamada ao modelo entre 2 e 60 s.');
    if (l.maxCostPerConversation !== null && l.maxCostPerConversation !== undefined && !(l.maxCostPerConversation > 0)) out.push('Custo máximo por conversa deve ser positivo.');
  }
  if (s.promptCode && !/^[a-z0-9-]{2,40}$/.test(s.promptCode)) {
    out.push('Código de prompt inválido.');
  }
  return out;
}

/** Cópia profunda (o rascunho em edição não altera a versão do servidor até salvar). */
export function cloneSettings(s: PolicySettings): PolicySettings {
  return JSON.parse(JSON.stringify(s)) as PolicySettings;
}

/** Escopo da política: assessoria, credor ou carteira (a mais específica vence). */
export function scopeOf(p: Pick<PolicyView, 'creditorId' | 'portfolioId'>): 'TENANT' | 'CREDITOR' | 'PORTFOLIO' {
  return p.portfolioId ? 'PORTFOLIO' : p.creditorId ? 'CREDITOR' : 'TENANT';
}

/** Quem publicou a política do turno não o avalia (segregação; o servidor também recusa). */
export function reviewBlocked(review: Pick<ReviewView, 'policyId'>, policies: readonly Pick<PolicyView, 'id' | 'publishedBy'>[], userId: string | null): boolean {
  const policy = policies.find((p) => p.id === review.policyId);
  return !!userId && !!policy && policy.publishedBy === userId;
}

/** Comentário obrigatório para "fora da política". */
export function reviewProblems(r: { score: number | null; verdict: string | null; comment: string }): string[] {
  const out: string[] = [];
  if (!r.score || r.score < 1 || r.score > 5) out.push('Dê uma nota de 1 a 5.');
  if (!r.verdict) out.push('Escolha o veredito.');
  if (r.verdict === 'OUT_OF_POLICY' && !r.comment.trim()) out.push('Explique no comentário por que ficou fora da política.');
  if (r.comment.length > 2000) out.push('Comentário com até 2.000 caracteres.');
  return out;
}

export interface Bar {
  key: string;
  label: string;
  value: number;
  /** Fração do maior valor (0–1), para a largura da barra. */
  share: number;
  /** Fração do total (0–1). */
  ofTotal: number;
}

/** Série ordenada (maior primeiro) para gráficos de barras com o valor sempre em texto. */
export function bars(data: Record<string, number> | null | undefined, labels: Record<string, string> = {}, limit = 12): Bar[] {
  const entries = Object.entries(data ?? {}).filter(([, v]) => v > 0);
  const total = entries.reduce((s, [, v]) => s + v, 0);
  const max = Math.max(0, ...entries.map(([, v]) => v));
  return entries
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .slice(0, limit)
    .map(([key, value]) => ({ key, label: labels[key] ?? key, value, share: max ? value / max : 0, ofTotal: total ? value / total : 0 }));
}

/** Janela "seg a sex, 08:00–20:00". */
export function windowText(w: ServiceWindow, days: Record<string, string>): string {
  return `${w.days.map((d) => days[d] ?? d).join(', ')} · ${w.start.slice(0, 5)}–${w.end.slice(0, 5)}`;
}

/** Percentual "12,5%" a partir de fração 0–1 (nulo = "—"). */
export function fractionText(value: number | null | undefined, digits = 1): string {
  return value === null || value === undefined ? '—' : `${new Intl.NumberFormat('pt-BR', { minimumFractionDigits: digits, maximumFractionDigits: digits }).format(value * 100)}%`;
}

/** JSON legível (contexto e argumentos das ferramentas); texto inválido volta como está. */
export function prettyJson(text: string | null | undefined): string {
  if (!text) {
    return '';
  }
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}
