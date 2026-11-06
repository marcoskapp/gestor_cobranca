/**
 * Parâmetros da aplicação citados no manual, nos tours, nas novidades, no "i" dos indicadores e em textos de tela
 * (prazos, limites e quantidades de `app.*` no backend). O texto nunca traz o número escrito à mão: usa um
 * marcador `{{<modulo>.<frase>}}`, resolvido em tempo de execução com o valor que o servidor aplica
 * (`GET /api/v1/helpdesk/manual-parameters`) — contrato em docs/tecnico/desenho/ux.md §8.7.
 *
 * Cada marcador é uma **frase** (não só o número), para o texto continuar correto no singular, no zero e enquanto o
 * valor não chega: `nos últimos 30 dias` · `no último dia` · `no período configurado`. Só dados e funções puras
 * (sem Angular): o mesmo catálogo vai para a base de conhecimento do chat de suporte (`npm run knowledge`), e o
 * backend resolve os marcadores dela na subida com as mesmas regras (`helpdesk.domain.service.ManualText`).
 */

/**
 * Como o número aparece: inteiro (dias, segundos, MB), decimal com as casas que o valor tem, de 2 a 4 e sem
 * arredondar (0,60 · 0,625), ou reais com duas casas (R$ 1.000,00).
 */
export type ManualParameterFormat = 'integer' | 'decimal' | 'money';

/**
 * Parâmetros publicados pelo backend (`ManualParameterSource` do módulo dono), com o formato de exibição.
 * A chave é contrato com o backend: não renomear.
 */
export const MANUAL_PARAMETERS = {
  'debt.cumulativeWindowDays': 'integer',
  'debt.proposalValidityDays': 'integer',
  'negotiation.breakSuspensionMaxDays': 'integer',
  'negotiation.breakSuspensionGraceDays': 'integer',
  'payment.rejectedReviewWindowDays': 'integer',
  'compliance.dataSubjectRequestDueDays': 'integer',
  'pendingwork.dueSoonDays': 'integer',
  'telephony.presenceTimeoutSeconds': 'integer',
  'ingestion.pdfMaxFileSizeMb': 'integer',
  'scoring.outcomeDays': 'integer',
  'scoring.outcomeGraceDays': 'integer',
  'scoring.minAuc': 'decimal',
  'scoring.psiWarning': 'decimal',
  'scoring.psiAlert': 'decimal',
  'settlement.adjustmentDualApprovalThreshold': 'money',
} as const satisfies Record<string, ManualParameterFormat>;

export type ManualParameterKey = keyof typeof MANUAL_PARAMETERS;

/**
 * Frase de um marcador. `other` leva `{n}` no lugar do número; `zero` e `one` (só inteiros) são opcionais;
 * `neutral` é o texto sem número exibido enquanto o valor carrega ou se a leitura falhar — nunca um número chutado.
 * As quatro formas precisam encaixar na mesma frase do conteúdo.
 */
export interface ManualPhrase {
  parameter: ManualParameterKey;
  zero?: string;
  one?: string;
  other: string;
  neutral: string;
}

export const MANUAL_PHRASES = {
  'debt.cumulativeWindow': {
    parameter: 'debt.cumulativeWindowDays',
    one: 'no último dia',
    other: 'nos últimos {n} dias',
    neutral: 'no período configurado',
  },
  'debt.proposalValidity': {
    parameter: 'debt.proposalValidityDays',
    one: 'em 1 dia',
    other: 'em {n} dias',
    neutral: 'no prazo configurado',
  },
  'negotiation.breakSuspensionMax': {
    parameter: 'negotiation.breakSuspensionMaxDays',
    one: 'por no máximo 1 dia',
    other: 'por no máximo {n} dias',
    neutral: 'pelo prazo máximo configurado',
  },
  'negotiation.breakSuspensionGrace': {
    parameter: 'negotiation.breakSuspensionGraceDays',
    zero: 'no mesmo dia',
    one: 'depois de 1 dia',
    other: 'depois de {n} dias',
    neutral: 'depois da carência configurada',
  },
  'payment.rejectedReviewWindow': {
    parameter: 'payment.rejectedReviewWindowDays',
    one: 'no último dia',
    other: 'nos últimos {n} dias',
    neutral: 'no período recente',
  },
  'compliance.dataSubjectRequestDue': {
    parameter: 'compliance.dataSubjectRequestDueDays',
    one: 'padrão de 1 dia',
    other: 'padrão de {n} dias',
    neutral: 'prazo padrão configurado',
  },
  'pendingwork.dueSoon': {
    parameter: 'pendingwork.dueSoonDays',
    zero: 'no dia do vencimento',
    one: 'a partir de 1 dia do vencimento',
    other: 'a partir de {n} dias do vencimento',
    neutral: 'quando o vencimento se aproxima',
  },
  'telephony.presenceTimeout': {
    parameter: 'telephony.presenceTimeoutSeconds',
    one: 'por 1 segundo',
    other: 'por {n} segundos',
    neutral: 'pelo tempo configurado',
  },
  'ingestion.pdfMaxFileSize': {
    parameter: 'ingestion.pdfMaxFileSizeMb',
    one: 'até 1 MB',
    other: 'até {n} MB',
    neutral: 'dentro do tamanho máximo aceito',
  },
  'scoring.outcomeWindow': {
    parameter: 'scoring.outcomeDays',
    one: 'padrão 1 dia',
    other: 'padrão {n} dias',
    neutral: 'definida nos parâmetros',
  },
  'scoring.outcomeGrace': {
    parameter: 'scoring.outcomeGraceDays',
    zero: 'logo no fim da janela',
    one: '1 dia depois',
    other: '{n} dias depois',
    neutral: 'depois de uma carência',
  },
  'scoring.outcomeGraceExtra': {
    parameter: 'scoring.outcomeGraceDays',
    zero: 'sem carência',
    one: 'mais 1 dia de carência',
    other: 'mais {n} dias de carência',
    neutral: 'mais a carência da apuração',
  },
  'scoring.minAuc': {
    parameter: 'scoring.minAuc',
    other: 'padrão {n}',
    neutral: 'definido nos parâmetros',
  },
  'scoring.psiWarning': {
    parameter: 'scoring.psiWarning',
    other: 'padrão {n}',
    neutral: 'definido nos parâmetros',
  },
  'scoring.psiAlert': {
    parameter: 'scoring.psiAlert',
    other: 'padrão {n}',
    neutral: 'definido nos parâmetros',
  },
  'settlement.adjustmentDualApproval': {
    parameter: 'settlement.adjustmentDualApprovalThreshold',
    other: 'acima de {n}',
    neutral: 'acima do valor configurado',
  },
} as const satisfies Record<string, ManualPhrase>;

export type ManualPhraseKey = keyof typeof MANUAL_PHRASES;

/** Valores efetivos vindos do servidor; `null` enquanto carrega ou se a leitura falhou. */
export type ManualParameterValues = Readonly<Record<string, number>>;

/** Marcador no texto: `{{<modulo>.<frase>}}`. */
const MARKER = /\{\{([a-z]+\.[a-zA-Z0-9]+)\}\}/g;

function isPhraseKey(key: string): key is ManualPhraseKey {
  return Object.prototype.hasOwnProperty.call(MANUAL_PHRASES, key);
}

/** Milhar com ponto (1.000), sem depender do locale. */
function thousands(integer: string): string {
  return integer.replace(/\B(?=(\d{3})+(?!\d))/g, '.');
}

/** Reais com duas casas (R$ 1.000,00), igual ao backend. */
function money(value: number): string {
  const [integer, cents] = Math.abs(value).toFixed(2).split('.');
  return 'R$ ' + (value < 0 ? '-' : '') + thousands(integer) + ',' + cents;
}

/** Casas do formato `decimal`: as que o valor tem, de 2 a 4. */
const DECIMAL_MIN = 2;
const DECIMAL_MAX = 4;

/**
 * Decimal com vírgula, com as casas que o valor tem (mínimo 2, máximo 4) e **sem arredondar**, igual ao backend:
 * 0,6 → "0,60", 0,615 → "0,615", 0,625 → "0,625". Mais de 4 casas → `null` (texto neutro, nunca número cortado).
 */
function decimal(value: number): string | null {
  // representação mais curta que volta ao mesmo número (0.615 → "0.615"); notação científica só em valores
  // fora da escala dos parâmetros (abaixo de 1e-6), recusados por terem mais de 4 casas
  const text = Math.abs(value).toString();
  if (text.includes('e')) {
    return null;
  }
  const [integer, fraction = ''] = text.split('.');
  if (fraction.length > DECIMAL_MAX) {
    return null;
  }
  return (value < 0 ? '-' : '') + thousands(integer) + ',' + fraction.padEnd(DECIMAL_MIN, '0');
}

/**
 * Valor de um parâmetro, se veio do servidor e serve ao formato (finito, não negativo; inteiro quando o formato é
 * `integer`); senão `null`. Regras de tela que dependem da configuração (ex.: limite do PDF) usam isto e, sem valor,
 * deixam a decisão com o servidor.
 */
export function parameterValue(key: ManualParameterKey, values: ManualParameterValues | null): number | null {
  const value = values?.[key];
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    return null;
  }
  return MANUAL_PARAMETERS[key] === 'integer' && !Number.isInteger(value) ? null : value;
}

/**
 * Texto de uma frase com o valor, ou o texto neutro se o valor não chegou (ou não serve ao formato: um
 * inteiro com casas decimais nunca vira número arredondado na tela).
 */
export function renderPhrase(key: ManualPhraseKey, values: ManualParameterValues | null): string {
  const phrase: ManualPhrase = MANUAL_PHRASES[key];
  const value = parameterValue(phrase.parameter, values);
  if (value === null) {
    return phrase.neutral;
  }
  const format: ManualParameterFormat = MANUAL_PARAMETERS[phrase.parameter];
  if (format === 'integer') {
    if (value === 0 && phrase.zero) {
      return phrase.zero;
    }
    if (value === 1 && phrase.one) {
      return phrase.one;
    }
    return phrase.other.replace('{n}', String(value));
  }
  const number = format === 'money' ? money(value) : decimal(value);
  return number === null ? phrase.neutral : phrase.other.replace('{n}', number);
}

/**
 * Troca os marcadores do texto pelas frases. Marcador desconhecido fica como está (os testes de conteúdo e o
 * `knowledge:check` recusam marcador desconhecido antes do build, então isso não chega ao usuário).
 */
export function resolveManualText(text: string, values: ManualParameterValues | null): string {
  if (!text.includes('{{')) {
    return text;
  }
  return text.replace(MARKER, (marker, key: string) => (isPhraseKey(key) ? renderPhrase(key, values) : marker));
}

/** Marcadores do texto que não estão no catálogo, ou mal formados (para os testes e a exportação do chat). */
export function unknownMarkers(text: string): string[] {
  const unknown = [...text.matchAll(MARKER)].map((m) => m[1]).filter((key) => !isPhraseKey(key)).map((key) => `{{${key}}}`);
  const malformed = text.replace(MARKER, '').includes('{{') || text.replace(MARKER, '').includes('}}');
  return malformed ? [...unknown, `marcador mal formado em "${text}"`] : unknown;
}
