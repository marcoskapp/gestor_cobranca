import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { flushAll, page, segment, tick } from '../../../../testing/campaign-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { SegmentCriteria } from '../data/campaign.models';
import { SegmentEditor } from './segment-editor';

const COUNT = { cases: 1500, debtors: 1234, openBalance: 2845123.55, evaluatedAt: '2026-10-23T12:00:00Z' };

interface Exposed {
  criteria: () => SegmentCriteria;
  edited: () => void;
  setName: (v: string) => void;
  code: () => string;
}

describe('SegmentEditor', () => {
  let http: HttpTestingController;
  const permissions = signal<string[]>(['SEGMENT_MANAGE', 'CAMPAIGN_MANAGE']);

  beforeEach(() => {
    permissions.set(['SEGMENT_MANAGE', 'CAMPAIGN_MANAGE']);
    TestBed.configureTestingModule({
      imports: [SegmentEditor],
      providers: [
        provideAppLocale(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            user: signal(fakeUser({ allCreditors: true })),
            hasPermission: (p: string) => permissions().includes(p),
            hasAnyPermission: (ps: readonly string[]) => ps.some((p) => permissions().includes(p)),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const handlers = {
    '/api/v1/creditors': page([]),
    '/api/v1/creditors/c-1': { id: 'c-1', legalName: 'Banco Horizonte S.A.', tradeName: 'Horizonte' },
    '/api/v1/campaign/segments/s-1': segment(),
    'POST /api/v1/campaign/segments/preview': COUNT,
    '/api/v1/campaign/segments/s-1/count': COUNT,
  };

  async function settle(fixture: ComponentFixture<SegmentEditor>, extra: Record<string, unknown> = {}) {
    const seen = [];
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await tick(i === 0 ? 320 : 0);
      seen.push(...flushAll(http, { ...handlers, ...extra }));
    }
    await tick(550);
    seen.push(...flushAll(http, { ...handlers, ...extra }));
    fixture.detectChanges();
    await tick();
    fixture.detectChanges();
    return seen;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const button = (f: ComponentFixture<unknown>, label: string) => [...el(f).querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes(label));

  it('novo segmento: código sugerido, prévia a cada alteração válida e criação', async () => {
    const fixture = TestBed.createComponent(SegmentEditor);
    const c = fixture.componentInstance as unknown as Exposed;
    let requests = await settle(fixture);
    expect(requests.some((r) => r.request.url.endsWith('/preview'))).toBe(true);
    expect(el(fixture).querySelector('[data-tour="segment-preview"]')?.textContent).toContain('1.234');

    c.setName('Atraso longo');
    expect(c.code()).toBe('ATRASO_LONGO');

    c.criteria().minDaysOverdue = 90;
    c.edited();
    requests = await settle(fixture);
    const preview = requests.find((r) => r.request.url.endsWith('/preview'))!;
    expect(preview.request.body).toEqual(expect.objectContaining({ creditorId: null, portfolioId: null }));
    expect(preview.request.body.criteria.minDaysOverdue).toBe(90);
    expect(preview.request.body.criteria.eligibleOnly).toBe(true);

    // faixa inválida: sem prévia e sem salvar
    c.criteria().maxDaysOverdue = 30;
    c.edited();
    requests = await settle(fixture);
    expect(requests.some((r) => r.request.url.endsWith('/preview'))).toBe(false);
    expect(el(fixture).textContent).toContain('Atraso: o mínimo é maior que o máximo.');
    expect(button(fixture, 'Criar segmento')?.disabled).toBe(true);

    c.criteria().maxDaysOverdue = 180;
    c.edited();
    await settle(fixture);
    button(fixture, 'Criar segmento')!.click();
    const create = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/campaign/segments');
    expect(create.request.body).toEqual(expect.objectContaining({ creditorId: null, code: 'ATRASO_LONGO', name: 'Atraso longo' }));
    expect(create.request.body.criteria).toEqual(expect.objectContaining({ minDaysOverdue: 90, maxDaysOverdue: 180 }));
    create.flush(segment({ id: 's-9' }));
  }, 20_000);

  it('duplicar carrega os filtros do original como novo segmento', async () => {
    const fixture = TestBed.createComponent(SegmentEditor);
    fixture.componentRef.setInput('copiar', 's-1');
    await settle(fixture);
    const c = fixture.componentInstance as unknown as Exposed;
    expect(c.code()).toBe('ATRASO_30_90_COPIA');
    expect(el(fixture).querySelector('h1')?.textContent).toContain('Duplicar segmento');
    button(fixture, 'Criar segmento')!.click();
    const create = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/campaign/segments');
    expect(create.request.body).toEqual(expect.objectContaining({ creditorId: 'c-1', name: 'Cópia de Atraso 30 a 90' }));
    expect(create.request.body.criteria.minDaysOverdue).toBe(30);
    create.flush(segment({ id: 's-2' }));
    await settle(fixture);
  });

  it('sem SEGMENT_MANAGE: somente leitura com a contagem do segmento salvo', async () => {
    permissions.set(['CAMPAIGN_VIEW']);
    const fixture = TestBed.createComponent(SegmentEditor);
    fixture.componentRef.setInput('id', 's-1');
    const requests = await settle(fixture);
    expect(requests.some((r) => r.request.url.endsWith('/count'))).toBe(true);
    expect(requests.some((r) => r.request.url.endsWith('/preview'))).toBe(false);
    expect(button(fixture, 'Salvar')).toBeUndefined();
    expect(button(fixture, 'Adicionar filtro')).toBeUndefined();
    const inputs = [...el(fixture).querySelectorAll<HTMLInputElement>('app-criteria-editor input')];
    expect(inputs.length).toBeGreaterThan(0);
    expect(inputs.every((i) => i.disabled)).toBe(true);
    expect(el(fixture).textContent).toContain('Atraso de 30 dias a 90 dias');
  });
});
