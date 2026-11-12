import { ChartRow } from '../../analytics/components/chart.models';
import { CONTACT_LABEL_LABELS, CONTACT_TYPE_LABELS } from '../../debt/data/debt.labels';
import { ContactLabel, ContactType } from '../../debt/data/debt.models';
import {
  AgreementsByAttempts,
  AttemptsBucket,
  ChannelInsight,
  ContactChannel,
  ContactConfidence,
  ContactPointProfile,
  FirstSuccessPoint,
  HeatmapCell,
  InsightPersonType,
  KnownContact,
  SlotStartHour,
} from './contact-intelligence.models';
import { integer, percent } from './scoring.rules';

/** Rótulos e regras puras da inteligência de contato (testáveis sem Angular). */

export const CONTACT_CHANNEL_LABELS: Record<ContactChannel, string> = {
  PHONE: 'Ligação',
  WHATSAPP: 'WhatsApp',
  SMS: 'SMS',
  EMAIL: 'E-mail',
  OTHER: 'Outros',
};

/** Ordem fixa dos canais nos gráficos e tabelas. */
export const CONTACT_CHANNEL_ORDER: readonly ContactChannel[] = ['PHONE', 'WHATSAPP', 'SMS', 'EMAIL', 'OTHER'];

export const CONFIDENCE_LABELS: Record<ContactConfidence, string> = {
  LOW: 'Baixa',
  MEDIUM: 'Média',
  HIGH: 'Alta',
};

export const CONFIDENCE_HINTS: Record<ContactConfidence, string> = {
  LOW: 'Poucas tentativas neste contato: a taxa ainda não diz muito. Use como pista, não como certeza.',
  MEDIUM: 'Tentativas suficientes para uma tendência, que ainda pode mudar com os próximos contatos.',
  HIGH: 'Muitas tentativas neste contato: a taxa é um retrato confiável do histórico.',
};

/** Dias da semana ISO (1 = segunda … 7 = domingo). */
export const WEEKDAYS: readonly { weekday: number; short: string; long: string }[] = [
  { weekday: 1, short: 'Seg', long: 'Segunda-feira' },
  { weekday: 2, short: 'Ter', long: 'Terça-feira' },
  { weekday: 3, short: 'Qua', long: 'Quarta-feira' },
  { weekday: 4, short: 'Qui', long: 'Quinta-feira' },
  { weekday: 5, short: 'Sex', long: 'Sexta-feira' },
  { weekday: 6, short: 'Sáb', long: 'Sábado' },
  { weekday: 7, short: 'Dom', long: 'Domingo' },
];

export const SLOT_HOURS: readonly SlotStartHour[] = [6, 9, 12, 15, 18, 21];

export const ATTEMPT_BUCKETS: readonly AttemptsBucket[] = ['0-1', '2-3', '4-6', '7-10', '11-20', '21+'];

export const BUCKET_LABELS: Record<AttemptsBucket, string> = {
  '0-1': 'Até 1 tentativa',
  '2-3': '2 a 3 tentativas',
  '4-6': '4 a 6 tentativas',
  '7-10': '7 a 10 tentativas',
  '11-20': '11 a 20 tentativas',
  '21+': '21 ou mais',
};

export const INSIGHT_PERSON_TYPES: readonly InsightPersonType[] = ['INDIVIDUAL', 'COMPANY'];

const PERCENT0 = new Intl.NumberFormat('pt-BR', { style: 'percent', maximumFractionDigits: 0 });
const DEC1 = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 });

/** Faixa de 3 horas: "06h–09h" (fim padrão = início + 3). */
export function slotLabel(startHour: number, endHour: number = startHour + 3): string {
  const hh = (h: number) => String(h).padStart(2, '0') + 'h';
  return `${hh(startHour)}–${hh(endHour)}`;
}

/** Taxa do cartão em % inteiro ("58%"); ausente → "—". */
export function ratePercent(rate: number | null | undefined): string {
  return rate === null || rate === undefined || Number.isNaN(rate) ? '—' : PERCENT0.format(rate);
}

/** Taxa da tela com 1 casa; abaixo do mínimo de tentativas não afirma número ("—"). */
export function rateOrDash(rate: number | null | undefined, attempts: number, minAttempts: number): string {
  return attempts < minAttempts || rate === null || rate === undefined ? '—' : percent(rate);
}

/** Mediana ("3,5 tentativas"); ausente → "—". */
export function medianText(value: number | null | undefined, unit: string): string {
  return value === null || value === undefined ? '—' : `${DEC1.format(value)} ${unit}`;
}

/** Tentativas da curva (11 = "11 ou mais"). */
export function attemptsLabel(attempts: number): string {
  return attempts >= 11 ? '11 ou mais' : attempts === 1 ? '1 tentativa' : `${attempts} tentativas`;
}

// ------------------------------------------------------------------------------------- período

export const INSIGHTS_DEFAULT_DAYS = 90;
export const INSIGHTS_MAX_DAYS = 400;

/** Últimos 90 dias até hoje (datas locais `yyyy-MM-dd`). */
export function defaultInsightsRange(today: string): { from: string; to: string } {
  const [y, m, d] = today.split('-').map(Number);
  const from = new Date(Date.UTC(y, m - 1, d - INSIGHTS_DEFAULT_DAYS));
  return { from: from.toISOString().slice(0, 10), to: today };
}

/** Problema do período (vazio, invertido ou acima de 400 dias) ou nulo — o servidor valida de novo. */
export function insightsRangeProblem(from: string | null, to: string | null): string | null {
  if (!from || !to) {
    return 'Informe o início e o fim do período.';
  }
  if (to < from) {
    return 'O fim do período precisa ser igual ou posterior ao início.';
  }
  const days = (Date.parse(to + 'T00:00:00Z') - Date.parse(from + 'T00:00:00Z')) / 86_400_000;
  return days > INSIGHTS_MAX_DAYS ? `Período máximo de ${INSIGHTS_MAX_DAYS} dias.` : null;
}

// ------------------------------------------------------------------------------- cartão do devedor

export interface ContactRow {
  key: string;
  /** Valor mascarado (como a ficha já mostra) ou o aviso de contato não identificado/removido. */
  value: string;
  /** "Telefone · Celular · WhatsApp"; vazio quando o contato não é conhecido. */
  kind: string;
  known: boolean;
  profile: ContactPointProfile;
  /** Confiança baixa: a tela diz "dados insuficientes" em vez de afirmar a taxa. */
  insufficient: boolean;
}

/** Id do contato nas duas formas recebidas (`contactId` da ficha do caso, `id` da ficha do devedor). */
export function knownContactId(c: KnownContact): string | undefined {
  return c.contactId ?? c.id;
}

/**
 * Junta o perfil (ordem do servidor, pela taxa) com os contatos que a tela já exibe, pelo id: o valor
 * vem sempre mascarado da lista recebida. Sem id → "Contato não identificado"; id fora da lista → "Contato removido".
 */
export function contactRows(profiles: readonly ContactPointProfile[], contacts: readonly KnownContact[]): ContactRow[] {
  const byId = new Map(contacts.map((c) => [knownContactId(c), c]));
  return profiles.map((p, index) => {
    const c = p.contactId ? byId.get(p.contactId) : undefined;
    const value = c ? c.maskedValue : p.contactId ? 'Contato removido' : 'Contato não identificado';
    return {
      key: p.contactId ?? `sem-id-${index}`,
      value,
      kind: c ? contactKind(c) : '',
      known: !!c,
      profile: p,
      insufficient: p.confidence === 'LOW',
    };
  });
}

function contactKind(c: KnownContact): string {
  const parts = [CONTACT_TYPE_LABELS[c.type as ContactType] ?? 'Contato'];
  const label = CONTACT_LABEL_LABELS[c.label as ContactLabel];
  if (label) {
    parts.push(label);
  }
  if (c.whatsapp) {
    parts.push('WhatsApp');
  }
  return parts.join(' · ');
}

// ------------------------------------------------------------------------------------ mapa de calor

export interface HeatCell {
  weekday: number;
  startHour: SlotStartHour;
  attempts: number;
  successes: number;
  /** Nulo quando há menos tentativas que o mínimo (célula mostra "—"). */
  rate: number | null;
  text: string;
  /** 0–100: intensidade da cor (proporcional à maior taxa válida do mapa). */
  intensity: number;
  /** Descrição para leitor de tela e dica. */
  description: string;
}

export interface HeatRow {
  weekday: number;
  short: string;
  long: string;
  cells: HeatCell[];
}

/** Matriz completa dia × faixa (células sem tentativa entram zeradas). */
export function heatmapRows(cells: readonly HeatmapCell[], minAttempts: number): HeatRow[] {
  const map = new Map(cells.map((c) => [`${c.weekday}-${c.startHour}`, c]));
  const valid = cells.filter((c) => c.attempts >= minAttempts && c.rate !== null).map((c) => c.rate!);
  const max = valid.length ? Math.max(...valid) : 0;
  return WEEKDAYS.map((d) => ({
    ...d,
    cells: SLOT_HOURS.map((h): HeatCell => {
      const c = map.get(`${d.weekday}-${h}`);
      const attempts = c?.attempts ?? 0;
      const successes = c?.successes ?? 0;
      const rate = c && attempts >= minAttempts && c.rate !== null ? c.rate : null;
      const slot = slotLabel(h);
      return {
        weekday: d.weekday,
        startHour: h,
        attempts,
        successes,
        rate,
        text: rate === null ? '—' : percent(rate),
        intensity: rate === null || max <= 0 ? 0 : Math.round((rate / max) * 100),
        description:
          rate === null
            ? `${d.long}, ${slot}: poucas tentativas (${integer(attempts)}); a taxa aparece a partir de ${integer(minAttempts)}.`
            : `${d.long}, ${slot}: ${percent(rate)} — ${integer(successes)} sucessos em ${integer(attempts)} tentativas.`,
      };
    }),
  }));
}

/** Melhor célula com taxa (para o resumo do mapa), ou nula. */
export function bestHeatCell(rows: readonly HeatRow[]): (HeatCell & { day: string }) | null {
  let best: (HeatCell & { day: string }) | null = null;
  for (const r of rows) {
    for (const c of r.cells) {
      if (c.rate !== null && (!best || c.rate > best.rate!)) {
        best = { ...c, day: r.long };
      }
    }
  }
  return best;
}

// ----------------------------------------------------------------------------------------- gráficos

/** Curva: contatos com o 1º atendimento em cada número de tentativas, com o acumulado. */
export function firstSuccessRows(points: readonly FirstSuccessPoint[]): ChartRow[] {
  return [...points]
    .sort((a, b) => a.attempts - b.attempts)
    .map((p) => ({
      key: String(p.attempts),
      label: p.attempts >= 11 ? '11+' : String(p.attempts),
      values: [p.contacts],
      display: [integer(p.contacts)],
      note: `${attemptsLabel(p.attempts)} · ${percent(p.cumulativeShare)} acumulado`,
    }));
}

/** Taxa por canal (em pontos percentuais para a barra); abaixo do mínimo a barra fica zerada e o texto "—". */
export function channelRows(channels: readonly ChannelInsight[], minAttempts: number): ChartRow[] {
  return [...channels]
    .sort((a, b) => CONTACT_CHANNEL_ORDER.indexOf(a.channel) - CONTACT_CHANNEL_ORDER.indexOf(b.channel))
    .map((c) => {
      const enough = c.attempts >= minAttempts && c.rate !== null;
      return {
        key: c.channel,
        label: CONTACT_CHANNEL_LABELS[c.channel] ?? 'Outros',
        values: [enough ? c.rate! * 100 : 0],
        display: [enough ? percent(c.rate) : '—'],
        note: enough
          ? `${integer(c.successes)} de ${integer(c.attempts)} tentativas · ${integer(c.debtorInitiated)} iniciadas pelo devedor`
          : `poucas tentativas (${integer(c.attempts)})`,
      };
    });
}

/** Acordos por faixa de tentativas: cumpridos e quebrados lado a lado, com a taxa de cumprimento na observação. */
export function agreementRows(rows: readonly AgreementsByAttempts[]): ChartRow[] {
  const byBucket = new Map(rows.map((r) => [r.bucket, r]));
  return ATTEMPT_BUCKETS.filter((b) => byBucket.has(b)).map((b) => {
    const r = byBucket.get(b)!;
    return {
      key: b,
      label: BUCKET_LABELS[b],
      values: [r.completed, r.broken],
      display: [integer(r.completed), integer(r.broken)],
      note: `${integer(r.agreements)} acordos · cumprimento ${percent(r.fulfillmentRate)}`,
    };
  });
}
