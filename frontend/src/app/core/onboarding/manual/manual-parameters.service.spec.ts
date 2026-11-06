import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthenticatedUser } from '../../auth/auth.models';
import { AuthService } from '../../auth/auth.service';
import { ManualParameters, manualPhrase } from './manual-parameters.service';
import { ManualTextPipe } from './manual-text.pipe';

const URL = '/api/v1/helpdesk/manual-parameters';

@Component({
  imports: [ManualTextPipe],
  template: `<p id="texto">{{ text | manualText }}</p><p id="frase">{{ phrase() }}</p>`,
})
class Host {
  text = 'Somando as reduções do mesmo título {{debt.cumulativeWindow}}; a proposta vence {{debt.proposalValidity}}.';
  protected readonly phrase = manualPhrase('payment.rejectedReviewWindow');
}

describe('ManualParameters e pipe manualText', () => {
  let http: HttpTestingController;
  const user = signal<AuthenticatedUser | null>(null);

  beforeEach(() => {
    user.set(fakeUser());
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: AuthService, useValue: { user } }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function render() {
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const text = (id: string) => (fixture.nativeElement as HTMLElement).querySelector('#' + id)!.textContent!.trim();
    return { fixture, text };
  }

  it('mostra o texto neutro enquanto carrega e o número do servidor quando chega (uma leitura só)', async () => {
    const { fixture, text } = render();
    expect(text('texto')).toBe('Somando as reduções do mesmo título no período configurado; a proposta vence no prazo configurado.');
    expect(text('frase')).toBe('no período recente');

    http.expectOne(URL).flush({ values: { 'debt.cumulativeWindowDays': 45, 'debt.proposalValidityDays': 1, 'payment.rejectedReviewWindowDays': 30 } });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(text('texto')).toBe('Somando as reduções do mesmo título nos últimos 45 dias; a proposta vence em 1 dia.');
    expect(text('frase')).toBe('nos últimos 30 dias');

    // outra tela com texto do manual reaproveita os valores (sem nova leitura)
    render();
    http.expectNone(URL);
  });

  it('falha na leitura: continua no texto neutro e só tenta de novo depois de um intervalo', () => {
    const { text } = render();
    http.expectOne(URL).flush({ code: 'x' }, { status: 503, statusText: 'Indisponível' });
    expect(text('texto')).not.toMatch(/\d/);
    TestBed.inject(ManualParameters).load();
    http.expectNone(URL);
  });

  it('valor inesperado do servidor vira texto neutro, nunca "NaN dias"', async () => {
    const { fixture, text } = render();
    http.expectOne(URL).flush({ values: { 'debt.cumulativeWindowDays': 'trinta', 'debt.proposalValidityDays': 15 } });
    await fixture.whenStable();
    fixture.detectChanges();
    expect(text('texto')).toBe('Somando as reduções do mesmo título no período configurado; a proposta vence em 15 dias.');
  });

  it('sem sessão não chama o servidor (o manual é só para quem entrou)', () => {
    user.set(null);
    const { text } = render();
    http.expectNone(URL);
    expect(text('texto')).toContain('no período configurado');
  });
});
