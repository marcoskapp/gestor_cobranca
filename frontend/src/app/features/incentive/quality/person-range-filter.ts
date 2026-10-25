import { Signal, computed } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder } from '@angular/forms';
import { debounceTime, map, startWith } from 'rxjs';
import { sameValue } from '../../../shared/data/same-value';
import { PersonRangeFilter } from '../data/incentive.models';

/** Filtro de pessoa + intervalo (avaliações e ocorrências); o servidor assume os últimos 90 dias sem datas. */
export function personRangeFilter(fb: FormBuilder) {
  const form = fb.nonNullable.group({
    user: fb.nonNullable.control<string[]>([]),
    from: fb.control<string | null>(null),
    to: fb.control<string | null>(null),
  });
  const current = (): PersonRangeFilter => {
    const v = form.getRawValue();
    return { userId: v.user[0] ?? null, from: v.from || null, to: v.to || null };
  };
  const filter: Signal<PersonRangeFilter> = toSignal(form.valueChanges.pipe(startWith(null), debounceTime(250), map(current)), {
    initialValue: current(),
    equal: sameValue,
  });
  const invalid = computed(() => {
    const f = filter();
    return !!f.from && !!f.to && f.to < f.from;
  });
  return { form, filter, invalid };
}
