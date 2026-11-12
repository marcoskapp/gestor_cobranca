import { TestBed } from '@angular/core/testing';
import { render, setup, text, tick } from '../../../../testing/component-harness';
import { ContactNoveltySettings as ContactNoveltySettingsView } from '../data/debt.models';
import { ContactNoveltySettings } from './contact-novelty-settings';

const settings = (overrides: Partial<ContactNoveltySettingsView> = {}): ContactNoveltySettingsView => ({
  windowDays: 30,
  defaultWindowDays: 30,
  minWindowDays: 1,
  maxWindowDays: 180,
  customized: false,
  updatedAt: null,
  ...overrides,
});

type Form = { controls: { windowDays: { setValue(v: number): void; markAsDirty(): void } }; invalid: boolean };

describe('ContactNoveltySettings', () => {
  it('mostra o padrão do sistema e salva a nova janela', async () => {
    const { http, notifications } = setup(ContactNoveltySettings, ['DEBTOR_MANAGE']);
    const fixture = await render(ContactNoveltySettings, http, () => settings());
    expect(text(fixture)).toContain('Vale o padrão do sistema (30 dias)');
    const form = (fixture.componentInstance as unknown as { form: Form }).form;

    form.controls.windowDays.setValue(45);
    form.controls.windowDays.markAsDirty();
    fixture.detectChanges();
    (fixture.nativeElement as HTMLElement).querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    const req = http.expectOne((r) => r.method === 'PUT' && r.url.endsWith('/debtors/contact-novelty-settings'));
    expect(req.request.body).toEqual({ windowDays: 45 });
    req.flush(settings({ windowDays: 45, customized: true, updatedAt: '2026-11-11T12:00:00Z' }));
    await tick();
    fixture.detectChanges();
    expect(notifications.success).toEqual(['Janela de novidade salva.']);
    expect(text(fixture)).toContain('Personalizado em');
  });

  it('não envia fora da faixa e mostra o erro de regra do servidor', async () => {
    const { http } = setup(ContactNoveltySettings, ['DEBTOR_MANAGE']);
    const fixture = await render(ContactNoveltySettings, http, () => settings());
    const form = (fixture.componentInstance as unknown as { form: Form }).form;
    form.controls.windowDays.setValue(181);
    form.controls.windowDays.markAsDirty();
    fixture.detectChanges();
    expect(form.invalid).toBe(true);
    (fixture.nativeElement as HTMLElement).querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    http.expectNone((r) => r.method === 'PUT');

    form.controls.windowDays.setValue(90);
    fixture.detectChanges();
    (fixture.nativeElement as HTMLElement).querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    http
      .expectOne((r) => r.method === 'PUT')
      .flush(
        { title: 'Regra de negócio', status: 422, code: 'contact_novelty.window_invalid', detail: 'Janela entre 1 e 180 dias' },
        { status: 422, statusText: 'Unprocessable Entity' },
      );
    await tick();
    fixture.detectChanges();
    expect(text(fixture)).toContain('Janela entre 1 e 180 dias');
  });

  it('carregamento com falha oferece tentar de novo', async () => {
    const { http } = setup(ContactNoveltySettings, ['DEBTOR_MANAGE']);
    const fixture = TestBed.createComponent(ContactNoveltySettings);
    fixture.detectChanges();
    await tick();
    http.expectOne((r) => r.method === 'GET').flush({ title: 'Erro' }, { status: 500, statusText: 'Erro' });
    await tick();
    fixture.detectChanges();
    expect(text(fixture)).toContain('Não foi possível carregar a configuração.');
  });
});
