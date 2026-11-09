import { DestroyRef, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl } from '@angular/forms';
import { distinctUntilChanged, skip, startWith } from 'rxjs';
import { sameValue } from './same-value';

/**
 * Filtro dependente: quando o usuário troca o `parent` (ex.: credor), o `child` (ex.: carteira daquele credor) volta
 * ao vazio, para a lista não ficar com uma combinação impossível. A entrada pela URL não dispara (o `syncFormWithUrl`
 * aplica os valores iniciais sem evento) e, num link para a mesma tela com credor e carteira novos, o credor é aplicado
 * antes da carteira — a carteira do link prevalece. Contexto de injeção (campo do componente).
 */
export function clearOnChange<T>(parent: AbstractControl, child: AbstractControl<T>, empty: T): void {
  parent.valueChanges
    .pipe(startWith(parent.value), distinctUntilChanged(sameValue), skip(1), takeUntilDestroyed(inject(DestroyRef)))
    .subscribe(() => {
      if (!sameValue(child.value, empty)) {
        child.setValue(empty);
      }
    });
}
