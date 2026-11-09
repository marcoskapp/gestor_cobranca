import { TestBed } from '@angular/core/testing';
import { FormBuilder } from '@angular/forms';
import { clearOnChange } from './dependent-filter';

describe('filtro dependente (carteira do credor)', () => {
  it('trocar o credor limpa a carteira; valor inicial e mesmo credor não limpam', () => {
    const form = new FormBuilder().nonNullable.group({ creditor: [['c-1']], portfolio: [['p-1']] });
    TestBed.runInInjectionContext(() => clearOnChange(form.controls.creditor, form.controls.portfolio, [] as string[]));
    expect(form.controls.portfolio.value).toEqual(['p-1']);
    form.controls.creditor.setValue(['c-1']);
    expect(form.controls.portfolio.value).toEqual(['p-1']);
    form.controls.creditor.setValue(['c-2']);
    expect(form.controls.portfolio.value).toEqual([]);
  });

  it('link com credor e carteira novos: o credor é aplicado antes e a carteira do link prevalece', () => {
    const form = new FormBuilder().nonNullable.group({ creditor: [['c-1']], portfolio: [['p-1']] });
    TestBed.runInInjectionContext(() => clearOnChange(form.controls.creditor, form.controls.portfolio, [] as string[]));
    form.patchValue({ creditor: ['c-2'], portfolio: ['p-2'] });
    expect(form.getRawValue()).toEqual({ creditor: ['c-2'], portfolio: ['p-2'] });
  });
});
