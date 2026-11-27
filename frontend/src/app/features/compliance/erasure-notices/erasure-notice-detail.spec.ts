import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthenticatedUser } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { ErasureNoticeView } from '../data/compliance.models';
import { ErasureNoticeDetail } from './erasure-notice-detail';

const BASE = '/api/v1/compliance/creditor-erasure-notices';

const notice: ErasureNoticeView = {
  id: 'n-1',
  debtorId: 'd-1',
  creditorId: 'c-1',
  creditorName: 'Banco A',
  origin: 'DATA_SUBJECT_REQUEST',
  originId: 'q-1',
  erasedAt: '2026-11-20T12:00:00Z',
  status: 'NOTIFIED',
  notifiedAt: '2026-11-21T12:00:00Z',
  notifiedChannel: 'EMAIL',
  notifiedBy: 'u-1',
  notifiedByName: 'Ana',
  registeredAt: '2026-11-21T12:00:00Z',
  items: [{ portfolioId: 'p-1', portfolioName: 'Carteira 1', reference: '***982247**-001', installmentNumber: 1 }],
  message: 'Prezados(as), Banco A',
  fileRequests: [
    {
      batchId: 'b-1', protocol: 'ab12cd34', blockedAt: '2026-11-20T12:00:00Z', lines: 2, inProgress: false,
      sentAt: '2026-11-22T12:00:00Z', channel: 'EMAIL', sentBy: 'u-2', sentByName: 'Bia',
    },
    {
      batchId: 'b-2', protocol: 'ef56ab78', blockedAt: '2026-11-20T12:00:00Z', lines: 1, inProgress: false,
      sentAt: null, channel: null, sentBy: null, sentByName: null,
    },
  ],
  fileRequestsTotal: 2,
};

describe('ErasureNoticeDetail', () => {
  let http: HttpTestingController;
  let canImport = true;
  const user = signal<AuthenticatedUser>(fakeUser({ permissions: ['DATA_SUBJECT_REQUEST_MANAGE'], allCreditors: true }));

  beforeEach(() => {
    canImport = true;
    TestBed.configureTestingModule({
      imports: [ErasureNoticeDetail],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: { user, hasPermission: (p: string) => p !== 'IMPORT_VIEW' || canImport, hasAnyPermission: () => true },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(body: ErasureNoticeView = notice): Promise<ComponentFixture<ErasureNoticeDetail>> {
    const fixture = TestBed.createComponent(ErasureNoticeDetail);
    fixture.componentRef.setInput('id', 'n-1');
    fixture.detectChanges();
    http.expectOne(`${BASE}/n-1`).flush(body);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('mostra os pedidos de novo arquivo com o envio registrado e o que falta enviar', async () => {
    const el: HTMLElement = (await render()).nativeElement;
    const section = el.querySelector('[data-tour="notice-file-requests"]')!;
    expect(section.textContent).toContain('Pedidos de novo arquivo');
    const rows = section.querySelectorAll('tbody tr');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('E-mail');
    expect(rows[0].textContent).toContain('registrado por Bia');
    expect(rows[1].textContent).toContain('Ainda não enviado');
    expect(rows[0].querySelector('a')?.getAttribute('href')).toBe('/importacao/b-1');
  });

  it('sem acesso à importação, o protocolo aparece sem link', async () => {
    canImport = false;
    const el: HTMLElement = (await render()).nativeElement;
    const section = el.querySelector('[data-tour="notice-file-requests"]')!;
    expect(section.querySelector('a')).toBeNull();
    expect(section.textContent).toContain('ab12cd34');
  });

  it('lista cortada avisa quantos há e leva aos recusados ainda sem pedido; lote em andamento aparece como tal', async () => {
    const inProgress = { ...notice.fileRequests[1], batchId: 'b-3', inProgress: true };
    const el: HTMLElement = (await render({ ...notice, fileRequests: [inProgress], fileRequestsTotal: 51 })).nativeElement;
    const section = el.querySelector('[data-tour="notice-file-requests"]')!;
    expect(section.textContent).toContain('Mostrando 1 de 51 arquivos');
    expect(section.textContent).toContain('A importação ainda está em andamento');
    const link = Array.from(section.querySelectorAll('a')).find((a) => a.textContent?.includes('ainda sem pedido'));
    expect(link?.getAttribute('href')).toBe('/importacao?credor=c-1&bloqueados=pendentes');
  });

  it('sem arquivos recusados, o quadro não aparece', async () => {
    const el: HTMLElement = (await render({ ...notice, fileRequests: [], fileRequestsTotal: 0 })).nativeElement;
    expect(el.querySelector('[data-tour="notice-file-requests"]')).toBeNull();
  });
});
