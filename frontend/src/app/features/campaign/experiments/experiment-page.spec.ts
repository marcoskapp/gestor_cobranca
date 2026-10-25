import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { cadence, campaign, experiment, flushAll, page, templateView, tick } from '../../../../testing/campaign-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { ExperimentInput, ExperimentResults, ExperimentView } from '../data/campaign.models';
import { ExperimentPage } from './experiment-page';
import { ExperimentResultsView } from './experiment-results';

const RESULTS: ExperimentResults = {
  experimentId: 'x-1',
  status: 'RUNNING',
  primaryMetric: 'PAYMENT',
  observationDays: 30,
  matureOnly: true,
  computedAt: '2026-10-23T12:00:00Z',
  baselineArm: 'CONTROL',
  warnings: ['INSUFFICIENT_SAMPLE'],
  arms: [
    {
      arm: 'CONTROL',
      control: true,
      debtors: 80,
      metrics: [
        { metric: 'PAYMENT', successes: 4, denominator: 80, estimate: 0.05, lower: 0.0196, upper: 0.1216, difference: null, warnings: ['SAMPLE_BELOW_MINIMUM', 'FEW_EVENTS'] },
        { metric: 'RECOVERED_PER_DEBTOR', successes: null, denominator: 80, estimate: 21.4, lower: 5.2, upper: 37.6, difference: null, warnings: [] },
      ],
    },
    {
      arm: 'B',
      control: false,
      debtors: 360,
      metrics: [
        { metric: 'PAYMENT', successes: 45, denominator: 360, estimate: 0.125, lower: 0.0947, upper: 0.1633, difference: { estimate: 0.075, lower: 0.0101, upper: 0.1291, significant: true }, warnings: [] },
        { metric: 'RECOVERED_PER_DEBTOR', successes: null, denominator: 360, estimate: 30.1, lower: 22.3, upper: 37.9, difference: { estimate: 8.7, lower: -9.1, upper: 26.5, significant: false }, warnings: [] },
      ],
    },
  ],
};

const auth = (permissions: () => string[]) => ({
  provide: AuthService,
  useValue: {
    user: signal(fakeUser()),
    hasPermission: (p: string) => permissions().includes(p),
    hasAnyPermission: (ps: readonly string[]) => ps.some((p) => permissions().includes(p)),
  },
});

describe('ExperimentResultsView', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [ExperimentResultsView], providers: [provideAppLocale(), provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('intervalo de confiança descrito em texto, diferença com veredito e avisos de amostra', async () => {
    const fixture = TestBed.createComponent(ExperimentResultsView);
    fixture.componentRef.setInput('experiment', experiment());
    fixture.detectChanges();
    await tick();
    const req = http.expectOne((r) => r.url === '/api/v1/campaign/experiments/x-1/results');
    expect(req.request.params.get('mature')).toBe('true');
    req.flush(RESULTS);
    await tick();
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent!;
    expect(text).toContain('Amostra insuficiente na métrica principal');
    expect(text).toContain('12,5% (IC 95%: 9,5% a 16,3%) · 45 de 360 devedores');
    expect(text).toContain('+7,5 p.p. (IC 95%: +1,0 p.p. a +12,9 p.p.) — melhor que a referência, diferença significativa');
    expect(text).toContain('sem diferença significativa');
    expect(text).toContain('Amostra abaixo do mínimo planejado');
    expect(text).toContain('Grupo de controle');
    expect(text).toContain('Braço de referência das diferenças');
    // a métrica principal vem primeiro
    const titles = [...(fixture.nativeElement as HTMLElement).querySelectorAll('.xr-metric__title')].map((t) => t.textContent!.trim());
    expect(titles[0]).toContain('Pagamento confirmado');
    // barras só decorativas: o texto é a fonte da informação
    expect((fixture.nativeElement as HTMLElement).querySelector('.xr-track')?.getAttribute('aria-hidden')).toBe('true');

    (fixture.componentInstance as unknown as { mature: { set: (v: boolean) => void } }).mature.set(false);
    fixture.detectChanges();
    await tick();
    const immature = http.expectOne((r) => r.url === '/api/v1/campaign/experiments/x-1/results');
    expect(immature.request.params.get('mature')).toBe('false');
    immature.flush({ ...RESULTS, matureOnly: false });
  });
});

describe('ExperimentPage', () => {
  let http: HttpTestingController;
  const permissions = signal<string[]>([]);

  beforeEach(() => {
    permissions.set(['CAMPAIGN_VIEW', 'EXPERIMENT_MANAGE']);
    TestBed.configureTestingModule({
      imports: [ExperimentPage],
      providers: [provideAppLocale(), provideRouter([{ path: '**', children: [] }]), provideHttpClient(), provideHttpClientTesting(), auth(permissions)],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(list: ExperimentView[]): Promise<ComponentFixture<ExperimentPage>> {
    const fixture = TestBed.createComponent(ExperimentPage);
    fixture.componentRef.setInput('id', 'cp-1');
    for (let i = 0; i < 4; i++) {
      fixture.detectChanges();
      await tick();
      flushAll(http, {
        '/api/v1/campaign/campaigns/cp-1': campaign({ status: 'ACTIVE' }),
        '/api/v1/campaign/cadences/cd-1': cadence(),
        '/api/v1/campaign/campaigns/cp-1/experiments': list,
        '/api/v1/communication/templates': page([templateView()]),
        '/api/v1/campaign/experiments/x-1/results': RESULTS,
      });
    }
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const button = (f: ComponentFixture<unknown>, label: string) => [...el(f).querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes(label));

  it('novo teste: validação contínua, repartição dos braços e criação', async () => {
    const fixture = await render([]);
    expect(el(fixture).textContent).toContain('Nenhum teste A/B nesta campanha.');
    button(fixture, 'Novo teste')!.click();
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('10% Grupo de controle');
    expect(el(fixture).textContent).toContain('45% Régua atual');

    const c = fixture.componentInstance as unknown as { draft: () => ExperimentInput; edited: () => void };
    c.draft().controlShare = 60;
    c.edited();
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('Grupo de controle entre 0% e 50%.');
    expect(button(fixture, 'Salvar rascunho')?.disabled).toBe(true);

    c.draft().controlShare = 20;
    c.draft().variants[1].overrides.push({ stepCode: 'D0', channel: null, templateCode: null, window: { start: '08:00', end: '10:00', days: [] }, offer: null });
    c.edited();
    fixture.detectChanges();
    button(fixture, 'Salvar rascunho')!.click();
    const post = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/campaign/campaigns/cp-1/experiments');
    expect(post.request.body.controlShare).toBe(20);
    expect(post.request.body.variants[1].overrides[0].window).toEqual({ start: '08:00:00', end: '10:00:00', days: [] });
    post.flush(experiment({ status: 'DRAFT', startedAt: null, controlShare: 20 }));
    fixture.detectChanges();
    await tick();
    flushAll(http, { '/api/v1/campaign/campaigns/cp-1/experiments': [experiment({ status: 'DRAFT', startedAt: null, controlShare: 20 })] });
  });

  it('iniciar exige INICIAR e mostra a repartição; em execução exibe resultados e permite encerrar', async () => {
    const fixture = await render([experiment({ status: 'DRAFT', startedAt: null })]);
    const confirm = vi.spyOn(TestBed.inject(ConfirmService), 'confirm').mockReturnValue(of(''));
    button(fixture, 'Iniciar teste')!.click();
    const data = confirm.mock.calls[0][0];
    expect(data.confirmationText).toBe('INICIAR');
    expect(data.details?.find((d) => d.label === 'Repartição')?.value).toBe('Grupo de controle: 10% · Régua atual: 45% · Manhã: 45%');
    http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/campaign/experiments/x-1/start').flush(experiment());
    fixture.detectChanges();
    await tick();
    flushAll(http, { '/api/v1/campaign/campaigns/cp-1/experiments': [experiment()] });
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await tick();
      flushAll(http, { '/api/v1/campaign/experiments/x-1/results': RESULTS });
    }
    await tick();
    fixture.detectChanges();
    expect(el(fixture).querySelector('[data-tour="experiment-results"]')?.textContent).toContain('IC 95%');
    expect(el(fixture).querySelectorAll('app-variant-editor input:not([disabled])').length).toBe(0);

    confirm.mockReturnValue(of('Variante B adotada'));
    button(fixture, 'Encerrar teste')!.click();
    expect(confirm.mock.calls[1][0]).toMatchObject({ confirmationText: 'ENCERRAR', reason: 'required' });
    const stop = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/campaign/experiments/x-1/stop');
    expect(stop.request.body).toEqual({ reason: 'Variante B adotada' });
    stop.flush(experiment({ status: 'STOPPED' }));
    fixture.detectChanges();
    await tick();
    flushAll(http, { '/api/v1/campaign/campaigns/cp-1/experiments': [experiment({ status: 'STOPPED' })], '/api/v1/campaign/experiments/x-1/results': RESULTS });
  });
});
