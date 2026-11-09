import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthenticatedUser } from '../../auth/auth.models';
import { AuthService } from '../../auth/auth.service';
import { DialogRef } from '../../../shared/dialog/dialog-ref';
import { CommandPalette } from './command-palette';
import { QuickSearchView, RecentRecords, isQuickSearchable } from './quick-search';

const CPF = '529.982.247-25';

const view: QuickSearchView = {
  criteria: ['DOCUMENT'],
  debtors: [
    {
      id: 'd-1',
      name: 'Ana Paula Ribeiro',
      maskedDocument: '***.982.247-**',
      personType: 'INDIVIDUAL',
      status: 'ACTIVE',
      matchedBy: 'DOCUMENT',
      creditorIds: ['c-1'],
    },
  ],
  cases: [{ id: 'k-1', debtorId: 'd-1', creditorId: 'c-1', portfolioId: 'p-1', status: 'IN_NEGOTIATION' }],
  creditorNames: { 'c-1': 'Horizonte' },
  portfolioNames: { 'p-1': 'Cartões setembro' },
  truncated: false,
};

describe('CommandPalette — busca de devedor e caso', () => {
  const permissions = signal<string[]>([]);
  const user = signal<AuthenticatedUser | null>(fakeUser());
  let http: HttpTestingController;
  let close: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    close = vi.fn();
    TestBed.configureTestingModule({
      imports: [CommandPalette],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: DialogRef, useValue: { close } },
        {
          provide: AuthService,
          useValue: {
            user,
            hasPermission: (p: string) => permissions().includes(p),
            hasAnyPermission: (ps: readonly string[]) => ps.some((p) => permissions().includes(p)),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function render(): ComponentFixture<CommandPalette> {
    const fixture = TestBed.createComponent(CommandPalette);
    fixture.detectChanges();
    return fixture;
  }

  async function type(fixture: ComponentFixture<CommandPalette>, value: string): Promise<void> {
    const input = fixture.nativeElement.querySelector('input') as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 350));
    fixture.detectChanges();
  }

  async function settle(fixture: ComponentFixture<CommandPalette>): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  const options = (fixture: ComponentFixture<CommandPalette>) =>
    [...(fixture.nativeElement as HTMLElement).querySelectorAll('[role="option"]')].map((o) => o.textContent?.replace(/\s+/g, ' ').trim());

  it('busca no servidor com o termo no corpo do POST (nunca na URL) e abre o caso, que entra em "Recentes"', async () => {
    permissions.set(['DEBTOR_VIEW', 'CASE_VIEW']);
    const fixture = render();
    expect((fixture.nativeElement.querySelector('input') as HTMLInputElement).placeholder).toContain('devedores e casos');

    await type(fixture, CPF);
    const req = http.expectOne((r) => r.url === '/api/v1/collection/quick-search');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ term: CPF });
    expect(req.request.urlWithParams).not.toContain('529');
    req.flush(view);
    await settle(fixture);

    const found = options(fixture);
    expect(found[0]).toContain('Ana Paula Ribeiro · Horizonte');
    expect(found[0]).toContain('Cartões setembro');
    expect(found[1]).toContain('***.982.247-**');
    expect(found.join(' ')).not.toContain(CPF);

    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
    (fixture.nativeElement.querySelector('[role="option"]') as HTMLElement).click();
    expect(close).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith('/cobranca/casos/k-1');

    // nova abertura com o campo vazio: o caso aparece em "Recentes"
    const again = render();
    expect((again.nativeElement as HTMLElement).querySelector('.palette__group')?.textContent).toContain('Recentes');
    expect(options(again)[0]).toContain('Ana Paula Ribeiro · Horizonte');
  });

  it('sem DEBTOR_VIEW ou com termo curto não consulta o servidor', async () => {
    permissions.set(['CASE_VIEW']);
    const fixture = render();
    expect((fixture.nativeElement.querySelector('input') as HTMLInputElement).placeholder).toBe('Buscar telas e ações…');
    await type(fixture, 'Ana Paula');
    http.expectNone('/api/v1/collection/quick-search');

    permissions.set(['DEBTOR_VIEW']);
    const other = render();
    await type(other, 'An');
    http.expectNone('/api/v1/collection/quick-search');
  });

  it('a resposta de um termo antigo não aparece e a falha vira aviso na própria paleta', async () => {
    permissions.set(['DEBTOR_VIEW']);
    const fixture = render();
    await type(fixture, 'Ana Paula');
    const first = http.expectOne('/api/v1/collection/quick-search');
    await type(fixture, 'Bruno Costa');
    expect(first.cancelled).toBe(true);
    http.expectOne('/api/v1/collection/quick-search').flush('erro', { status: 500, statusText: 'Erro' });
    await settle(fixture);
    expect(fixture.nativeElement.textContent).toContain('Não foi possível buscar devedores e casos agora.');
  });

  it('"Recentes" ficam só em memória e somem na troca de usuário', () => {
    const recents = TestBed.inject(RecentRecords);
    recents.add({ kind: 'debtor', id: 'd-1', label: 'Ana' });
    recents.add({ kind: 'case', id: 'k-1', label: 'Ana · Horizonte' });
    recents.add({ kind: 'debtor', id: 'd-1', label: 'Ana' });
    expect(recents.items().map((r) => r.id)).toEqual(['d-1', 'k-1']);

    user.set(null);
    TestBed.tick();
    expect(recents.items()).toEqual([]);
    user.set(fakeUser());
  });
});

describe('isQuickSearchable', () => {
  it('pede 3 letras ou dígitos', () => {
    expect(isQuickSearchable('An')).toBe(false);
    expect(isQuickSearchable('Ana')).toBe(true);
    expect(isQuickSearchable('Jõ ')).toBe(false);
    expect(isQuickSearchable(CPF)).toBe(true);
    expect(isQuickSearchable('x'.repeat(81))).toBe(false);
  });
});
