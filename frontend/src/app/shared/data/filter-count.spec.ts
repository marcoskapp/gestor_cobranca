import { Component, inject } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormBuilder } from '@angular/forms';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { activeFilterCount, countActiveFilters } from './filter-count';
import { syncFormWithUrl, urlParam } from './url-state';

@Component({ template: '' })
class Host {
  private readonly fb = inject(FormBuilder).nonNullable;
  readonly filters = syncFormWithUrl(this.fb.group({ status: ['ACTIVE'], type: [''], user: [''] }), { status: urlParam.text('situacao'), type: urlParam.text('tipo') }, 0);
  readonly active = activeFilterCount(this.filters, { ignore: ['user'] });
}

describe('contagem de filtros ativos', () => {
  it('conta só os campos diferentes do padrão, inclusive listas e vazio diferente do padrão', () => {
    const defaults = { creditor: [] as string[], status: 'ACTIVE', queue: '', user: [] as string[] };
    expect(countActiveFilters({ ...defaults }, defaults)).toBe(0);
    expect(countActiveFilters({ ...defaults, creditor: ['c-1'], status: '' }, defaults)).toBe(2);
    expect(countActiveFilters({ ...defaults, user: ['u-1'] }, defaults, ['user'])).toBe(0);
  });

  it('padrão informado pela tela: valor vindo da URL fora do syncFormWithUrl conta como filtro', () => {
    const form = new FormBuilder().nonNullable.group({ token: ['t-1'], month: ['2026-11'] });
    const count = TestBed.runInInjectionContext(() => activeFilterCount(form, { defaults: { token: '' } }));
    expect(count()).toBe(1);
    form.controls.token.setValue('');
    expect(count()).toBe(0);
  });

  it('usa o padrão da tela (antes da URL) e acompanha o formulário', async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([{ path: 'lista', component: Host }])] });
    const harness = await RouterTestingHarness.create();
    const host = await harness.navigateByUrl('/lista?situacao=OPEN', Host);
    expect(host.filters.controls.status.value).toBe('OPEN');
    expect(host.active()).toBe(1);
    host.filters.controls.type.setValue('X');
    host.filters.controls.user.setValue('u-1');
    expect(host.active()).toBe(2);
    host.filters.reset();
    expect(host.active()).toBe(0);
  });
});
