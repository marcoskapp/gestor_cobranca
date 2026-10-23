/** Conversões entre `Date` (datepicker) e os tipos de data do backend. */

/** `LocalDate` (yyyy-MM-dd) a partir da data local escolhida, sem conversão de fuso. */
export function toLocalDate(value: Date | null | undefined): string | null {
  if (!value) {
    return null;
  }
  const y = value.getFullYear();
  const m = String(value.getMonth() + 1).padStart(2, '0');
  const d = String(value.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/** `Date` local (meia-noite) a partir de `LocalDate`. */
export function fromLocalDate(value: string | null | undefined): Date | null {
  if (!value) {
    return null;
  }
  const [y, m, d] = value.split('-').map(Number);
  return new Date(y, m - 1, d);
}

/** Início do dia local como `Instant` ISO (filtros "de"). */
export function startOfDayInstant(value: Date | null | undefined): string | null {
  if (!value) {
    return null;
  }
  return new Date(value.getFullYear(), value.getMonth(), value.getDate()).toISOString();
}

/** Fim do dia local como `Instant` ISO (filtros "até", inclusivo). */
export function endOfDayInstant(value: Date | null | undefined): string | null {
  if (!value) {
    return null;
  }
  return new Date(value.getFullYear(), value.getMonth(), value.getDate(), 23, 59, 59, 999).toISOString();
}

/** `LocalTime` "HH:mm[:ss]" → "HH:mm". */
export function toHourMinute(value: string | null | undefined): string {
  return value ? value.slice(0, 5) : '';
}
