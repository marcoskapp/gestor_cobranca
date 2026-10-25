import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { marketTemplate } from '../../../../testing/negotiation-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapComponentDefaults, provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { PolicyView } from '../data/negotiation.models';
import { PolicyDetail } from './policy-detail';

const tick = () => new Promise((r) => setTimeout(r, 0));

function policy(overrides: Partial<PolicyView> = {}): PolicyView {
  return {
    id: 'pol-1',
    creditorId: 'c-1',
    version: 2,
    name: 'Política Horizonte',
    description: null,
    status: 'DRAFT',
    effectiveFrom: null,
    effectiveTo: null,
    terms: marketTemplate(),
    termsSchemaVersion: 1,
    publishedAt: null,
    publishedBy: null,
    retiredAt: null,
    createdAt: '2026-10-20T12:00:00Z',
    ...overrides,
  };
}

describe('PolicyDetail', () => {
  let http: HttpTestingController;
  const permissions = signal<string[]>(['POLICY_VIEW', 'POLICY_MANAGE']);

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [PolicyDetail],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(), provideBootstrapComponentDefaults(),
        provideRouter([{ path: '**', children: [] }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            user: signal(fakeUser()),
            hasPermission: (p: string) => permissions().includes(p),
            hasAnyPermission: (ps: readonly string[]) => ps.some((p) => permissions().includes(p)),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(current: PolicyView): Promise<ComponentFixture<PolicyDetail>> {
    const fixture = TestBed.createComponent(PolicyDetail);
    fixture.componentRef.setInput('id', current.id);
    for (let i = 0; i < 4; i++) {
      fixture.detectChanges();
      await tick();
      for (const req of http.match(() => true)) {
        const url = req.request.url;
        if (url === `/api/v1/negotiation/policies/${current.id}`) req.flush(current);
        else if (url === '/api/v1/negotiation/policies') req.flush({ content: [policy(), policy({ id: 'pol-0', version: 1, status: 'PUBLISHED', effectiveFrom: '2026-01-01' })], page: 0, size: 100, totalElements: 2, totalPages: 1 });
        else if (url === '/api/v1/negotiation/indexes') req.flush([{ id: 'i-1', code: 'IPCA', name: 'IPCA', source: 'IBGE', lastCompetence: '2026-09', rates: [] }]);
        else if (url === '/api/v1/creditors/c-1') req.flush({ id: 'c-1', legalName: 'Banco Horizonte S.A.', tradeName: 'Horizonte' });
        else throw new Error(`requisição inesperada ${url}`);
      }
    }
    fixture.detectChanges();
    await tick();
    fixture.detectChanges();
    return fixture;
  }

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const button = (f: ComponentFixture<unknown>, label: string) =>
    [...el(f).querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes(label));

  it('rascunho: edição com validação contínua e salvamento dos termos', async () => {
    const fixture = await render(policy());
    expect(button(fixture, 'Publicar')?.disabled).toBe(false);
    expect(el(fixture).querySelector('[data-tour="policy-versions"]')?.textContent).toContain('v1');

    const component = fixture.componentInstance as unknown as { draft: () => { base: { charges: { finePercent: number } } }; edited: () => void };
    component.draft().base.charges.finePercent = 25;
    component.edited();
    fixture.detectChanges();
    expect(el(fixture).textContent).toContain('Multa entre 0 e 20%.');
    expect(button(fixture, 'Salvar rascunho')).toBeTruthy();
    expect(button(fixture, 'Publicar')).toBeUndefined();

    component.draft().base.charges.finePercent = 2;
    component.edited();
    fixture.detectChanges();
    button(fixture, 'Salvar rascunho')!.click();
    const req = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/v1/negotiation/policies/pol-1');
    expect(req.request.body.name).toBe('Política Horizonte');
    expect(req.request.body.terms.base.charges.finePercent).toBe(2);
    expect(req.request.body.terms.base.ladder.length).toBe(4);
    req.flush(policy());
  });

  it('versão publicada é somente leitura e oferece nova versão', async () => {
    const fixture = await render(policy({ id: 'pol-0', version: 1, status: 'PUBLISHED', effectiveFrom: '2026-01-01' }));
    const inputs = [...el(fixture).querySelectorAll<HTMLInputElement>('app-rules-editor input')];
    expect(inputs.length).toBeGreaterThan(0);
    expect(inputs.every((i) => i.disabled)).toBe(true);
    // já há um rascunho (pol-1) para o credor: nova versão fica indisponível
    expect(button(fixture, 'Nova versão')?.disabled).toBe(true);
    expect(button(fixture, 'Aposentar')).toBeTruthy();
  });

  it('sem POLICY_MANAGE não há ações de edição', async () => {
    permissions.set(['POLICY_VIEW']);
    const fixture = await render(policy());
    expect(button(fixture, 'Publicar')).toBeUndefined();
    expect(button(fixture, 'Modelo de mercado')).toBeUndefined();
    permissions.set(['POLICY_VIEW', 'POLICY_MANAGE']);
  });
});
