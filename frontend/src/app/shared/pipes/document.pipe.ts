import { Pipe, PipeTransform } from '@angular/core';
import { formatDocument, maskDocument, onlyDigits } from '../validation/document';

/** Formata CPF/CNPJ. `| document:'masked'` exibe a forma mascarada usada em listagens. */
@Pipe({ name: 'document' })
export class DocumentPipe implements PipeTransform {
  transform(value: string | null | undefined, mode: 'full' | 'masked' = 'full'): string {
    if (!value) {
      return '—';
    }
    if (mode === 'masked') {
      return maskDocument(value);
    }
    const digits = onlyDigits(value);
    return digits.length === 11 || digits.length === 14 ? formatDocument(digits) : value;
  }
}
