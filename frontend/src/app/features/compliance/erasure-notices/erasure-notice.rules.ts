/** Folga para o relógio do navegador adiantado (decisão 1485; o servidor confere de novo). */
const FUTURE_TOLERANCE_MS = 60_000;

/**
 * Valida quando o credor foi avisado (`datetime-local`, fuso do navegador): obrigatório, não futuro e não anterior à
 * anonimização mais recente dos avisos registrados. Devolve a mensagem do problema, ou `null`.
 */
/** Textos das mensagens (o mesmo diálogo registra o aviso da eliminação e o pedido de novo arquivo). */
export interface SentAtTexts {
  /** "o aviso", "o pedido" */
  what: string;
  missing: string;
  /** "à eliminação dos dados", "ao bloqueio do arquivo" */
  floor: string;
}

const NOTICE_TEXTS: SentAtTexts = { what: 'o aviso', missing: 'Informe quando o credor foi avisado.', floor: 'à eliminação dos dados' };

export function sentAtProblem(value: string, latestErasedAt: string | null, now: Date = new Date(), texts: SentAtTexts = NOTICE_TEXTS): string | null {
  if (!value) {
    return texts.missing;
  }
  const at = new Date(value);
  if (Number.isNaN(at.getTime())) {
    return 'Data e hora inválidas.';
  }
  if (at.getTime() > now.getTime() + FUTURE_TOLERANCE_MS) {
    return `A data d${texts.what} não pode ser futura.`;
  }
  // o campo não tem segundos: no mesmo minuto da eliminação vale (o envio usa o instante da eliminação)
  if (latestErasedAt && at.getTime() < floorToMinute(new Date(latestErasedAt))) {
    return `${texts.what.charAt(0).toUpperCase()}${texts.what.slice(1)} não pode ser anterior ${texts.floor}.`;
  }
  return null;
}

/** Valor inicial do campo `datetime-local` (agora, no fuso do navegador, sem segundos). */
export function localNow(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T${pad(now.getHours())}:${pad(now.getMinutes())}`;
}

/**
 * Instante enviado ao servidor: o digitado ou, se cair no mesmo minuto da eliminação e antes dela (o campo não tem
 * segundos), o próprio instante da eliminação.
 */
export function sentAtInstant(value: string, latestErasedAt: string | null): string {
  const at = new Date(value);
  if (latestErasedAt) {
    const erased = new Date(latestErasedAt);
    if (at.getTime() < erased.getTime()) {
      return erased.toISOString();
    }
  }
  return at.toISOString();
}

function floorToMinute(d: Date): number {
  return Math.floor(d.getTime() / 60_000) * 60_000;
}

/** Anonimização mais recente entre os avisos (o aviso precisa ser depois de todas). */
export function latestErasure(erasedAt: readonly string[]): string | null {
  return erasedAt.reduce<string | null>((max, x) => (!max || new Date(x) > new Date(max) ? x : max), null);
}
