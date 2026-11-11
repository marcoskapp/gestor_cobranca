import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthenticatedUser } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { DataProcessorView } from '../data/compliance.models';
import { ProcessorList, processorState } from './processor-list';

const BASE = '/api/v1/compliance/data-processors';

const whatsapp: DataProcessorView = {
  id: 'p-1',
  providerKey: 'communication.whatsapp_cloud',
  name: 'Mensageria Fictícia Ltda.',
  role: 'SUB_OPERATOR',
  purpose: 'Entrega de mensagens de WhatsApp',
  dataCategories: 'Nome, telefone e mensagens',
  countryCode: 'US',
  internationalTransfer: true,
  transferSafeguard: 'STANDARD_CONTRACTUAL_CLAUSES',
  contractReference: 'DPA-01',
  validFrom: '2026-01-01',
  validUntil: null,
  active: true,
  current: true,
  updatedAt: '2026-11-11T12:00:00Z',
};

describe('ProcessorList (operadores e suboperadores)', () => {
  let http: HttpTestingController;
  const user = signal<AuthenticatedUser>(fakeUser({ permissions: ['DATA_SUBJECT_REQUEST_MANAGE'], allCreditors: true }));
  let permissions: string[];

  beforeEach(() => {
    permissions = ['DATA_SUBJECT_REQUEST_MANAGE', 'TELEPHONY_MANAGE'];
    TestBed.configureTestingModule({
      imports: [ProcessorList],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: { user, hasPermission: (p: string) => permissions.includes(p), hasAnyPermission: (ps: string[]) => ps.some((p) => permissions.includes(p)) },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(canEdit = true): Promise<ComponentFixture<ProcessorList>> {
    const fixture = TestBed.createComponent(ProcessorList);
    fixture.detectChanges();
    http.expectOne(BASE).flush([whatsapp]);
    if (canEdit) {
      http.expectOne(`${BASE}/unregistered`).flush([{ providerKey: 'telephony.twilio', name: 'Twilio', purpose: 'PHONE_CALLS', source: 'Telefonia' }]);
    }
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('mostra os provedores em uso sem cadastro, com atalho para a configuração, e o cadastro com país e garantia', async () => {
    const el = (await render()).nativeElement as HTMLElement;
    expect(el.querySelector('.pl-missing')?.textContent).toContain('Twilio');
    expect(el.querySelector('.pl-missing a')?.getAttribute('href')).toBe('/telefonia/plataformas');
    expect(el.textContent).toContain('Mensageria Fictícia Ltda.');
    expect(el.textContent).toContain('Estados Unidos');
    expect(el.textContent).toContain('Cláusulas-padrão aprovadas pela ANPD');
    expect([...el.querySelectorAll('button')].some((b) => b.textContent?.includes('Cadastrar operador'))).toBe(true);
  });

  it('sem acesso a todos os credores, só consulta: botões ocultos e sem a lista de provedores em uso (nem a chamada)', async () => {
    user.set(fakeUser({ permissions: ['DATA_SUBJECT_REQUEST_MANAGE'], allCreditors: false }));
    const el = (await render(false)).nativeElement as HTMLElement;
    http.expectNone(`${BASE}/unregistered`);
    expect(el.querySelector('[data-tour="processors-missing"]')).toBeNull();
    expect(el.textContent).not.toContain('Provedores em uso sem cadastro');
    expect([...el.querySelectorAll('button')].some((b) => /Cadastrar|Editar/.test(b.textContent ?? '') || b.getAttribute('aria-label')?.startsWith('Editar'))).toBe(false);
    expect(el.textContent).toContain('Consulta somente');
    user.set(fakeUser({ permissions: ['DATA_SUBJECT_REQUEST_MANAGE'], allCreditors: true }));
  });

  it('situação: vigente, inativo ou fora da vigência', () => {
    expect(processorState(whatsapp).label).toBe('Vigente');
    expect(processorState({ ...whatsapp, current: false, active: false }).label).toBe('Inativo');
    expect(processorState({ ...whatsapp, current: false }).label).toBe('Contrato fora da vigência');
  });
});
