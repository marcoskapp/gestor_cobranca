import { toAmount } from '../../../shared/components/amount/amount';
import type { IconName } from '../../../shared/components/icon/icon-names';
import type { TargetSelection } from '../components/payment-target';
import { RECONCILIATION_MODE_LABELS, REVERSAL_REASON_LABELS, REVIEW_MODE_LABELS, STRATEGY_LABELS } from './payment.labels';
import { AllocationView, InstructionView, PaymentView, PixAccountView, RequestedTargetView, StatementImportView, TargetRequest } from './payment.models';

// --------------------------------------------------------------------------- arquivos

/** Comprovante de pagamento externo: PDF, PNG ou JPEG até 10 MB (`app.payment.evidence.max-size-bytes`). */
export const EVIDENCE_TYPES: readonly string[] = ['application/pdf', 'image/png', 'image/jpeg'];
export const EVIDENCE_ACCEPT = '.pdf,.png,.jpg,.jpeg,' + EVIDENCE_TYPES.join(',');
export const EVIDENCE_MAX_BYTES = 10 * 1024 * 1024;

/** Valida o comprovante antes do envio (o servidor confere também a assinatura do conteúdo). */
export function evidenceProblem(file: Pick<File, 'type' | 'size' | 'name'>): string | null {
  if (file.size <= 0) {
    return 'O arquivo está vazio.';
  }
  if (file.size > EVIDENCE_MAX_BYTES) {
    return 'O comprovante passa do limite de 10 MB.';
  }
  if (!EVIDENCE_TYPES.includes(file.type) || !/\.(pdf|png|jpe?g)$/i.test(file.name)) {
    return 'Tipo de arquivo não aceito. Envie o comprovante em PDF, PNG ou JPEG.';
  }
  return null;
}

/** Extrato CSV genérico até 10 MB e 50.000 linhas (`app.payment.statement.*`). */
export const STATEMENT_ACCEPT = '.csv,text/csv';
export const STATEMENT_MAX_BYTES = 10 * 1024 * 1024;
export const STATEMENT_MAX_LINES = 50_000;

export function statementFileProblem(file: Pick<File, 'size' | 'name'>): string | null {
  if (file.size <= 0) {
    return 'O arquivo está vazio.';
  }
  if (file.size > STATEMENT_MAX_BYTES) {
    return 'O arquivo passa do limite de 10 MB.';
  }
  if (!/\.csv$/i.test(file.name)) {
    return 'Envie o extrato em CSV (layout genérico).';
  }
  return null;
}

/**
 * Certificado de cliente mTLS em PKCS#12 (.p12/.pfx). O servidor aceita até 22.000 caracteres em
 * Base64 (`CredentialsRequest.certificateBase64`), ou seja, cerca de 16 KB de arquivo.
 */
export const CERTIFICATE_ACCEPT = '.p12,.pfx,application/x-pkcs12';
export const CERTIFICATE_MAX_BASE64 = 22_000;
export const CERTIFICATE_MAX_BYTES = Math.floor(CERTIFICATE_MAX_BASE64 / 4) * 3;

export function certificateProblem(file: Pick<File, 'size' | 'name'>): string | null {
  if (file.size <= 0) {
    return 'O arquivo está vazio.';
  }
  if (!/\.(p12|pfx)$/i.test(file.name)) {
    return 'Envie o certificado de cliente em PKCS#12 (.p12 ou .pfx).';
  }
  if (file.size > CERTIFICATE_MAX_BYTES) {
    return 'O certificado passa do tamanho aceito (16 KB). Confira se o arquivo contém só o certificado e a chave.';
  }
  return null;
}

/** Conteúdo do arquivo em Base64 (sem o prefixo `data:`), lido só em memória. */
export function readAsBase64(file: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(reader.error ?? new Error('Falha ao ler o arquivo'));
    reader.onload = () => {
      const result = String(reader.result ?? '');
      resolve(result.slice(result.indexOf(',') + 1));
    };
    reader.readAsDataURL(file);
  });
}

/** Impressão digital SHA-256 (64 hexadecimais; aceita `:` e maiúsculas como o servidor). */
export function normalizeFingerprint(value: string): string {
  return value.replace(/:/g, '').trim().toLowerCase();
}

export function isFingerprint(value: string): boolean {
  return /^[0-9a-f]{64}$/.test(normalizeFingerprint(value));
}

/** "ab12 cd34 … 9f0e": grupos de 4 para conferência visual. */
export function groupedFingerprint(value: string | null | undefined): string {
  return value ? normalizeFingerprint(value).replace(/(.{4})/g, '$1 ').trim() : '—';
}

// --------------------------------------------------------------------------- valores

/** Converte o texto digitado ("1.234,56", "1234.56") em número com 2 casas; nulo se inválido. */
export function parseMoney(text: string | number | null | undefined): number | null {
  if (text === null || text === undefined) {
    return null;
  }
  if (typeof text === 'number') {
    return Number.isFinite(text) ? Math.round(text * 100) / 100 : null;
  }
  const raw = text.replace(/R\$|\s/g, '');
  if (!raw) {
    return null;
  }
  // vírgula decimal (pt-BR): pontos são separadores de milhar
  const normalized = raw.includes(',') ? raw.replace(/\./g, '').replace(',', '.') : raw;
  if (!/^\d+(\.\d{1,2})?$/.test(normalized)) {
    return null;
  }
  return Math.round(Number(normalized) * 100) / 100;
}

/** Valor acima do limite exige aprovação de outra pessoa (`externalReviewThreshold`). */
export function requiresSecondReview(amount: number | null, threshold: number | string | null | undefined): boolean {
  const limit = toAmount(threshold);
  return amount !== null && limit !== null && amount > limit;
}

// --------------------------------------------------------------------------- pagamento informado

/**
 * Instante local (data escolhida + hora "HH:mm") em ISO; nulo se incompleto. Sem hora, usa meio-dia
 * — ou o momento atual, se meio-dia de hoje ainda não chegou (o servidor recusa data futura).
 */
export function paidAtInstant(date: Date | null, time: string, now = new Date()): string | null {
  if (!date) {
    return null;
  }
  if (/^\d{2}:\d{2}$/.test(time)) {
    const [h, m] = time.split(':').map(Number);
    return new Date(date.getFullYear(), date.getMonth(), date.getDate(), h, m).toISOString();
  }
  const noon = new Date(date.getFullYear(), date.getMonth(), date.getDate(), 12, 0);
  return (noon > now ? now : noon).toISOString();
}

export function toTargetRequest(t: TargetSelection): TargetRequest | null {
  if (t.kind === 'AGREEMENT') {
    return { kind: 'AGREEMENT', agreementId: t.agreementId, itemIds: t.itemIds, strategy: null };
  }
  if (t.kind === 'TITLES') {
    return { kind: 'TITLES', agreementId: null, itemIds: t.itemIds, strategy: t.strategy };
  }
  return null;
}

// --------------------------------------------------------------------------- instruções

export function isOpenInstruction(i: Pick<InstructionView, 'status'>): boolean {
  return i.status === 'PENDING' || i.status === 'ACTIVE';
}

/** Tempo restante até a expiração ("expira em 3 h 20 min"), ou o estado final. */
export function expirationText(i: Pick<InstructionView, 'status' | 'expiresAt'>, now = Date.now()): string {
  if (!i.expiresAt) {
    return 'Sem prazo de expiração';
  }
  const ms = new Date(i.expiresAt).getTime() - now;
  if (!isOpenInstruction(i)) {
    return '';
  }
  if (ms <= 0) {
    return 'Expirando agora';
  }
  const minutes = Math.ceil(ms / 60_000);
  if (minutes < 60) {
    return `Expira em ${minutes} min`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 48) {
    const rest = minutes % 60;
    return `Expira em ${hours} h${rest ? ` ${rest} min` : ''}`;
  }
  return `Expira em ${Math.floor(hours / 24)} dias`;
}

/** "Parcela 2 do acordo" ou "Título avulso". */
export function instructionTarget(i: Pick<InstructionView, 'agreementId' | 'installmentNumber' | 'titleId'>): string {
  if (i.agreementId) {
    return i.installmentNumber ? `Parcela ${i.installmentNumber} do acordo` : 'Parcela do acordo';
  }
  return i.titleId ? 'Título avulso' : '—';
}

// --------------------------------------------------------------------------- pagamentos

export interface PaymentActionContext {
  userId: string | null;
  canReview: boolean;
  canReverse: boolean;
  canReallocate: boolean;
}

export interface PaymentActions {
  /** Aguarda aprovação e o usuário pode revisar (não foi quem informou). */
  review: boolean;
  /** Aguarda aprovação, mas foi o próprio usuário que informou: a tela explica. */
  ownPending: boolean;
  reverse: boolean;
  reallocate: boolean;
}

export function paymentActions(p: PaymentView, ctx: PaymentActionContext): PaymentActions {
  const pending = p.status === 'INFORMED';
  const own = pending && !!ctx.userId && p.informedBy === ctx.userId;
  const effective = p.status === 'CONFIRMED' || p.status === 'RECONCILED';
  return {
    review: pending && ctx.canReview && !own,
    ownPending: own,
    reverse: effective && ctx.canReverse,
    reallocate: effective && ctx.canReallocate && (toAmount(p.pendingReallocation) ?? 0) > 0,
  };
}

export interface TimelineEntry {
  at: string;
  icon: IconName;
  title: string;
  detail: string | null;
  /** Usuário a resolver o nome (quem informou, revisou ou estornou). */
  userId: string | null;
  tone: 'neutral' | 'success' | 'warning' | 'danger';
}

/**
 * Linha do tempo do pagamento montada a partir dos instantes da própria view (informado, revisado,
 * confirmado, conciliado, realocações e estorno), em ordem cronológica.
 */
export function paymentTimeline(p: PaymentView): TimelineEntry[] {
  const entries: TimelineEntry[] = [];
  if (p.source === 'EXTERNAL' && p.informedAt) {
    entries.push({
      at: p.informedAt,
      icon: 'pencil-square',
      title: 'Pagamento informado',
      detail: p.reviewThresholdExceeded ? 'Acima do limite: aguardando aprovação de outra pessoa.' : null,
      userId: p.informedBy,
      tone: 'neutral',
    });
  }
  if (p.reviewedAt) {
    const rejected = p.status === 'REJECTED';
    entries.push({
      at: p.reviewedAt,
      icon: rejected ? 'slash-circle' : 'person-check',
      title: rejected ? 'Recusado na revisão' : 'Aprovado na revisão',
      detail: p.reviewNote,
      userId: p.reviewedBy,
      tone: rejected ? 'warning' : 'success',
    });
  }
  if (p.confirmedAt) {
    entries.push({
      at: p.confirmedAt,
      icon: 'check-circle',
      title: 'Confirmado',
      detail: p.reviewMode ? REVIEW_MODE_LABELS[p.reviewMode] : null,
      userId: null,
      tone: 'success',
    });
  }
  for (const a of p.allocations.filter((x) => x.origin === 'REALLOCATION')) {
    entries.push({
      at: a.createdAt,
      icon: a.kind === 'REFUND' ? 'arrow-counterclockwise' : 'signpost-split',
      title: a.kind === 'REFUND' ? 'Devolução registrada' : 'Valor realocado',
      detail: a.kind === 'REFUND' && a.refundReference ? `Referência ${a.refundReference}` : null,
      userId: null,
      tone: 'neutral',
    });
  }
  if (p.reconciledAt) {
    entries.push({
      at: p.reconciledAt,
      icon: 'card-checklist',
      title: 'Conciliado',
      detail: p.reconciliationMode ? `Por ${RECONCILIATION_MODE_LABELS[p.reconciliationMode]}` : null,
      userId: null,
      tone: 'success',
    });
  }
  if (p.reversedAt) {
    entries.push({
      at: p.reversedAt,
      icon: 'arrow-counterclockwise',
      title: 'Estornado',
      detail: p.reversal ? [REVERSAL_REASON_LABELS[p.reversal.reason], p.reversal.note].filter(Boolean).join(' · ') : null,
      userId: p.reversal?.reversedBy ?? null,
      tone: 'danger',
    });
  }
  return entries.sort((a, b) => a.at.localeCompare(b.at));
}

export interface ReallocationSource {
  /** Nulo = saldo não alocado do pagamento. */
  allocationId: string | null;
  label: string;
  available: number;
}

/** De onde sai o valor a realocar: sobra do pagamento e/ou não aplicado de alocações a acordo. */
export function reallocationSources(p: PaymentView): ReallocationSource[] {
  const sources: ReallocationSource[] = [];
  const unallocated = toAmount(p.unallocatedAmount) ?? 0;
  if (unallocated > 0) {
    sources.push({ allocationId: null, label: 'Sobra do pagamento (sem destino)', available: unallocated });
  }
  for (const a of p.allocations) {
    const available = toAmount(a.availableForReallocation) ?? 0;
    if (a.kind === 'AGREEMENT' && a.status === 'ACTIVE' && available > 0) {
      sources.push({ allocationId: a.id, label: 'Não aplicado no acordo', available });
    }
  }
  return sources;
}

/** Descrição curta do destino de uma alocação. */
export function allocationTarget(a: Pick<AllocationView, 'kind' | 'lines' | 'refundReference'>): string {
  switch (a.kind) {
    case 'AGREEMENT':
      return `Acordo · ${a.lines.length} parcela(s)`;
    case 'TITLES':
      return `${a.lines.length} título(s) avulso(s)`;
    case 'REFUND':
      return `Devolução ao pagador${a.refundReference ? ` · ${a.refundReference}` : ''}`;
  }
}

/**
 * Destino solicitado em texto, para quem aprova: "Acordo de 20/09/2026 · 3ª e 4ª parcelas",
 * "Acordo · todas as parcelas em aberto" ou "Títulos CRED-1/4, CRED-1/5 · vencidos primeiro".
 * Sem acesso ao acordo/título, o texto fica genérico (quantidade de itens).
 */
export function requestedTargetText(
  target: Pick<RequestedTargetView, 'kind' | 'itemIds' | 'strategy'>,
  lookup: { agreementLabel?: string | null; installmentNumbers?: ReadonlyMap<string, number>; titleLabels?: ReadonlyMap<string, string> } = {},
): string {
  if (target.kind === 'AGREEMENT') {
    const head = lookup.agreementLabel ? `Acordo de ${lookup.agreementLabel}` : 'Acordo';
    if (!target.itemIds.length) {
      return `${head} · todas as parcelas em aberto, na ordem`;
    }
    const numbers = target.itemIds.map((id) => lookup.installmentNumbers?.get(id));
    if (numbers.every((n): n is number => n !== undefined)) {
      const list = numbers.map((n) => `${n}ª`);
      const text = list.length === 1 ? list[0] : `${list.slice(0, -1).join(', ')} e ${list.at(-1)}`;
      return `${head} · ${text} parcela${list.length > 1 ? 's' : ''}`;
    }
    return `${head} · ${target.itemIds.length} parcela(s) escolhida(s)`;
  }
  const strategy = target.strategy ? ` · ${STRATEGY_LABELS[target.strategy].toLowerCase()}` : '';
  const labels = target.itemIds.map((id) => lookup.titleLabels?.get(id));
  if (target.itemIds.length && labels.every((l): l is string => !!l)) {
    return `Título${labels.length > 1 ? 's' : ''} ${labels.join(', ')}${strategy}`;
  }
  return `${target.itemIds.length} título(s) avulso(s)${strategy}`;
}

// --------------------------------------------------------------------------- conciliação

export interface ImportIndicators {
  credits: number;
  reconciledShare: number | null;
  divergentShare: number | null;
}

/** Linhas de crédito tratadas e as taxas do lote (denominador: linhas lidas menos ignoradas). */
export function importIndicators(i: Pick<StatementImportView, 'totalLines' | 'reconciledLines' | 'createdPayments' | 'divergentLines' | 'ignoredLines'>): ImportIndicators {
  const credits = Math.max(0, i.totalLines - i.ignoredLines);
  return {
    credits,
    reconciledShare: credits ? (i.reconciledLines + i.createdPayments) / credits : null,
    divergentShare: credits ? i.divergentLines / credits : null,
  };
}

export function isImportRunning(i: Pick<StatementImportView, 'status'>): boolean {
  return i.status === 'RECEIVED' || i.status === 'PROCESSING';
}

// --------------------------------------------------------------------------- contas Pix

export interface CertificateState {
  tone: 'success' | 'warning' | 'danger' | 'neutral';
  text: string;
}

/** Validade do certificado mTLS com aviso a partir de 30 dias do vencimento. */
export function certificateState(a: Pick<PixAccountView, 'certificateSha256' | 'certificateExpiresAt'>, now = Date.now()): CertificateState {
  if (!a.certificateSha256 || !a.certificateExpiresAt) {
    return { tone: 'neutral', text: 'Sem certificado' };
  }
  const days = Math.floor((new Date(a.certificateExpiresAt).getTime() - now) / 86_400_000);
  if (days < 0) {
    return { tone: 'danger', text: 'Certificado vencido' };
  }
  if (days <= 30) {
    return { tone: 'warning', text: `Vence em ${days} dia(s)` };
  }
  return { tone: 'success', text: 'Certificado válido' };
}

/** Rótulos legíveis dos segredos gravados (a API devolve só os nomes). */
export const CREDENTIAL_KEY_LABELS: Record<string, string> = {
  clientId: 'client_id',
  clientSecret: 'client_secret',
  webhookSecret: 'segredo HMAC',
  certificatePassword: 'senha do certificado',
};
