import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { fakeUser } from '../../../../../testing/auth-fixtures';
import { AuthService } from '../../../../core/auth/auth.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { DisputeContactView } from '../../data/organization.models';
import { CreditorDisputeContacts } from './creditor-dispute-contacts';

const BASE = '/api/v1/creditors/c-1/dispute-contacts';
const contact = (o: Partial<DisputeContactView> = {}): DisputeContactView => ({
  id: 'k-1',
  creditorId: 'c-1',
  portfolioId: null,
  name: 'Contestações',
  email: 'contestacoes@alfa.test',
  phone: null,
  role: 'PRIMARY',
  active: true,
  version: 0,
  ...o,
});

describe('CreditorDisputeContacts', () => {
  let http: HttpTestingController;
  const permissions = signal<string[]>([]);

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [CreditorDisputeContacts],
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
        { provide: ConfirmService, useValue: { confirm: () => of('') } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  async function render(list: DisputeContactView[]): Promise<ComponentFixture<CreditorDisputeContacts>> {
    const fixture = TestBed.createComponent(CreditorDisputeContacts);
    fixture.componentRef.setInput('creditorId', 'c-1');
    fixture.detectChanges();
    http.expectOne(BASE).flush(list);
    fixture.detectChanges();
    await Promise.resolve();
    for (const req of http.match((r) => r.url.startsWith('/api/v1/portfolios/'))) {
      req.flush({ id: 'p-1', name: 'Cartões', code: 'CRT' });
    }
    fixture.detectChanges();
    return fixture;
  }

  it('sem contato principal avisa que os pedidos não podem ser enviados; sem CREDITOR_MANAGE não edita', async () => {
    permissions.set(['CREDITOR_VIEW']);
    const fixture = await render([]);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('não consegue enviar pedidos de contestação');
    expect(el.querySelector('form')).toBeNull();
  });

  it('lista os contatos com papel e carteira', async () => {
    permissions.set(['CREDITOR_VIEW']);
    const fixture = await render([contact(), contact({ id: 'k-2', name: 'Jurídico', email: 'juridico@alfa.test', role: 'COPY', portfolioId: 'p-1' })]);
    const text = (fixture.nativeElement as HTMLElement).textContent!;
    expect(text).toContain('contestacoes@alfa.test');
    expect(text).toContain('Principal');
    expect(text).toContain('Cópia');
    expect(text).toContain('Cópia carteira');
    expect(text).toContain('todas as carteiras');
  });

  it('inclui contato com e-mail validado', async () => {
    permissions.set(['CREDITOR_VIEW', 'CREDITOR_MANAGE']);
    const fixture = await render([]);
    const el = fixture.nativeElement as HTMLElement;
    const inputs = el.querySelectorAll('form input');
    const set = (input: Element, value: string) => {
      (input as HTMLInputElement).value = value;
      input.dispatchEvent(new Event('input'));
    };
    set(inputs[0], 'Contestações');
    set(inputs[1], 'não-é-email');
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    http.expectNone({ method: 'POST', url: BASE });
    set(inputs[1], 'contestacoes@alfa.test');
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const req = http.expectOne({ method: 'POST', url: BASE });
    expect(req.request.body).toEqual({ portfolioId: null, name: 'Contestações', email: 'contestacoes@alfa.test', phone: null, role: 'PRIMARY' });
    req.flush(contact());
    fixture.detectChanges();
    await Promise.resolve();
    http.expectOne(BASE).flush([contact()]);
  });
});
