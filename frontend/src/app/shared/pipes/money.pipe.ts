import { Pipe, PipeTransform } from '@angular/core';

const BRL = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });

/** Valor monetário em reais (`BigDecimal` do backend chega como número ou texto). */
export function formatMoney(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') {
    return '—';
  }
  const amount = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(amount) ? BRL.format(amount).replace(/ /g, ' ') : '—';
}

@Pipe({ name: 'money' })
export class MoneyPipe implements PipeTransform {
  transform(value: number | string | null | undefined): string {
    return formatMoney(value);
  }
}
