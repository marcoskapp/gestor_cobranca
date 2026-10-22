import { AbstractControl, FormGroup } from '@angular/forms';
import { ApiError } from '../../core/http/problem';

/**
 * Aplica os erros de validação do servidor (`errors[].field`) aos controles correspondentes do
 * formulário. Suporta caminhos aninhados (`terms.startDate`). Retorna os erros sem campo
 * correspondente, para exibição geral.
 */
export function applyServerErrors(form: FormGroup, error: ApiError, aliases: Record<string, string> = {}): string[] {
  const unmatched: string[] = [];
  for (const fieldError of error.fieldErrors) {
    const path = aliases[fieldError.field] ?? fieldError.field;
    const control: AbstractControl | null = form.get(path);
    if (control) {
      control.setErrors({ ...(control.errors ?? {}), server: fieldError.message });
      control.markAsTouched();
    } else {
      unmatched.push(`${fieldError.field}: ${fieldError.message}`);
    }
  }
  return unmatched;
}
