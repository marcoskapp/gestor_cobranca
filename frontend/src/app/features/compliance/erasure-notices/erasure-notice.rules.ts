/** Folga para o relógio do navegador adiantado (decisão 1485; o servidor confere de novo). */
const FUTURE_TOLERANCE_MS = 60_000;

/**
 * Valida quando o credor foi avisado (`datetime-local`, fuso do navegador): obrigatório, não futuro e não anterior à
 * anonimização mais recente dos avisos registrados. Devolve a mensagem do problema, ou `null`.
 */
export function sentAtProblem(value: string, latestErasedAt: string | null, now: Date = new Date()): string | null {
  if (!value) {
    return 'Informe quando o credor foi avisado.';
  }
  const at = new Date(value);
  if (Number.isNaN(at.getTime())) {
    return 'Data e hora inválidas.';
  }
  if (at.getTime() > now.getTime() + FUTURE_TOLERANCE_MS) {
    return 'A data do aviso não pode ser futura.';
  }
  // o campo não tem segundos: no mesmo minuto da eliminação vale (o envio usa o instante da eliminação)
  if (latestErasedAt && at.getTime() < floorToMinute(new Date(latestErasedAt))) {
    return 'O aviso não pode ser anterior à eliminação dos dados.';
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
