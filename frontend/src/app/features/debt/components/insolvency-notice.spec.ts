import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { DebtorInsolvency, ProceedingView } from '../data/insolvency';
import { InsolvencyNotice } from './insolvency-notice';

const recovery: ProceedingView = {
  id: 'ip-1',
  debtorId: 'd-1',
  kind: 'JUDICIAL_RECOVERY',
  caseNumber: '0001234-03.2026.8.26.0100',
  court: '1ª Vara de Falências',
  filedOn: '2026-03-02',
  since: '2026-03-02',
  convertedFrom: null,
  convertedOn: null,
  status: 'ACTIVE',
  endedOn: null,
  endReason: null,
  note: 'Edital',
};

describe('InsolvencyNotice (recuperação judicial e falência)', () => {
  let http: HttpTestingController;
  const permissions = signal<string[]>([]);

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [InsolvencyNotice],
      providers: [
        provideRouter([]),
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

  async function render(personType: string, manage = false): Promise<ComponentFixture<InsolvencyNotice>> {
    const fixture = TestBed.createComponent(InsolvencyNotice);
    fixture.componentRef.setInput('debtorId', 'd-1');
    fixture.componentRef.setInput('personType', personType);
    fixture.componentRef.setInput('manage', manage);
    fixture.detectChanges();
    await tick(fixture);
    return fixture;
  }

  /** Deixa o recurso disparar ou aplicar a resposta (sem `whenStable`, que esperaria a requisição pendente). */
  async function tick(fixture: ComponentFixture<InsolvencyNotice>): Promise<void> {
    await new Promise((r) => setTimeout(r, 0));
    fixture.detectChanges();
  }

  const text = (f: ComponentFixture<unknown>) => ((f.nativeElement as HTMLElement).textContent ?? '').replace(/\s+/g, ' ');

  it('pessoa física: nada é consultado nem exibido', async () => {
    permissions.set(['DEBTOR_VIEW', 'DEBTOR_MANAGE', 'LEGAL_VIEW']);
    const fixture = await render('INDIVIDUAL', true);
    http.expectNone('/api/v1/debtors/d-1/insolvency');
    expect(text(fixture).trim()).toBe('');
  });

  it('empresa em recuperação: aviso com desde quando, processo e atalho para o único caso jurídico', async () => {
    permissions.set(['CASE_VIEW', 'LEGAL_VIEW']);
    const fixture = await render('COMPANY');
    http.expectOne('/api/v1/debtors/d-1/insolvency').flush({ debtorId: 'd-1', company: true, active: recovery, history: [recovery], canManage: true } satisfies DebtorInsolvency);
    fixture.detectChanges();
    await tick(fixture);
    http.expectOne((r) => r.url === '/api/v1/legal/matters' && r.params.get('debtorId') === 'd-1').flush({
      content: [{ id: 'm-1' }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });
    fixture.detectChanges();
    await tick(fixture);
    expect(text(fixture)).toContain('Empresa em recuperação judicial desde 02/03/2026');
    expect(text(fixture)).toContain('0001234-03.2026.8.26.0100');
    const link = (fixture.nativeElement as HTMLElement).querySelector('a');
    expect(link?.getAttribute('href')).toBe('/juridico/casos/m-1');
    // sem DEBTOR_MANAGE (ou fora da ficha do devedor), sem ações
    expect(text(fixture)).not.toContain('Encerrar processo');
  });

  it('ficha do devedor com DEBTOR_MANAGE: sem processo, oferece o registro; sem LEGAL_VIEW, não consulta o jurídico', async () => {
    permissions.set(['DEBTOR_VIEW', 'DEBTOR_MANAGE']);
    const fixture = await render('COMPANY', true);
    http.expectOne('/api/v1/debtors/d-1/insolvency').flush({ debtorId: 'd-1', company: true, active: null, history: [], canManage: true } satisfies DebtorInsolvency);
    fixture.detectChanges();
    await tick(fixture);
    expect(text(fixture)).toContain('Registrar recuperação judicial ou falência');
    http.expectNone((r) => r.url === '/api/v1/legal/matters');
  });
});
