import { DatePipe } from '@angular/common';
import { LOCALE_ID, Pipe, PipeTransform, inject } from '@angular/core';

/**
 * Datas no padrão brasileiro. `Instant` (UTC) é exibido no fuso do navegador; `LocalDate`
 * (yyyy-MM-dd) é exibido sem conversão de fuso, para não "voltar um dia".
 */
@Pipe({ name: 'appDate' })
export class AppDatePipe implements PipeTransform {
  private readonly date = new DatePipe(inject(LOCALE_ID));

  transform(value: string | Date | null | undefined, format: 'date' | 'datetime' | 'time' = 'datetime'): string {
    if (!value) {
      return '—';
    }
    if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)) {
      const [y, m, d] = value.split('-');
      return `${d}/${m}/${y}`;
    }
    const pattern = format === 'date' ? 'dd/MM/yyyy' : format === 'time' ? 'HH:mm' : 'dd/MM/yyyy HH:mm';
    return this.date.transform(value, pattern) ?? '—';
  }
}
