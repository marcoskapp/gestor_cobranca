import { Signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormGroup } from '@angular/forms';
import { map, startWith } from 'rxjs';
import { sameValue } from './same-value';
import { urlFormDefaults } from './url-state';

/**
 * Contagem de filtros aplicados de uma lista, para o "Filtros (n)" do `app-filter-bar` (ux.md §12.3): quantos campos
 * do formulário diferem do padrão da tela. O padrão é o valor de antes de aplicar a URL (`syncFormWithUrl`) ou, sem
 * URL, o valor no momento da chamada. Contexto de injeção (campo do componente, depois do formulário).
 *
 * `ignore`: campos que não são filtros por si (ex.: o operador escolhido, que só vale com "Responsável: escolher").
 * `defaults`: padrão de campos que a tela preenche a partir da URL sem `syncFormWithUrl` (ex.: token vindo de outra
 * tela), para que o valor vindo de fora conte como filtro.
 */
export function activeFilterCount(
  form: FormGroup,
  options: { ignore?: readonly string[]; defaults?: Record<string, unknown> } = {},
): Signal<number> {
  const defaults = {
    ...(urlFormDefaults<Record<string, unknown>>(form) ?? (form.getRawValue() as Record<string, unknown>)),
    ...options.defaults,
  };
  const count = () => countActiveFilters(form.getRawValue() as Record<string, unknown>, defaults, options.ignore ?? []);
  return toSignal(form.valueChanges.pipe(startWith(null), map(count)), { initialValue: count() });
}

/** Quantos campos de `value` diferem de `defaults` (comparação estrutural), sem os de `ignore`. Função pura. */
export function countActiveFilters<T extends object>(value: T, defaults: Partial<T>, ignore: readonly string[] = []): number {
  return (Object.keys(value) as (keyof T & string)[]).filter((key) => !ignore.includes(key) && !sameValue(value[key], defaults[key])).length;
}
