import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { provideAppLocale } from '../../../../core/config/app-locale';
import { NotificationService } from '../../../../core/notification/notification.service';
import { ConfirmDialogData } from '../../../../shared/components/confirm-dialog/confirm-dialog';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { TenantView } from '../../../organization/data/organization.models';
import { TenantList } from './tenant-list';

const tenant = (overrides: Partial<TenantView> = {}): TenantView => ({
  id: 't-1',
  slug: 'horizonte',
  legalName: 'Horizonte Cobranças Ltda',
  tradeName: 'Horizonte',
  cnpj: '11222333000181',
  status: 'ACTIVE',
  platform: false,
  contactWindowStart: '08:00:00',
  contactWindowEnd: '20:00:00',
  contactOnSaturday: true,
  contactOnSunday: false,
  ...overrides,
});

describe('TenantList', () => {
  let http: HttpTestingController;
  const confirmations: ConfirmDialogData[] = [];
  const success = vi.fn();

  beforeEach(() => {
    confirmations.length = 0;
    success.mockReset();
    TestBed.configureTestingModule({
      imports: [TenantList],
      providers: [
        provideAppLocale(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: NotificationService, useValue: { success, error: vi.fn(), info: vi.fn(), warning: vi.fn() } },
        {
          provide: ConfirmService,
          useValue: {
            confirm: (data: ConfirmDialogData) => {
              confirmations.push(data);
              return of('Link expirou');
            },
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(rows: TenantView[]): Promise<ComponentFixture<TenantList>> {
    const fixture = TestBed.createComponent(TenantList);
    fixture.detectChanges();
    http.expectOne('/api/v1/platform/tenants').flush(rows);
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  const buttons = (fixture: ComponentFixture<TenantList>, label: string) =>
    [...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button')].filter((b) => b.textContent?.trim() === label);

  it('reenvia o convite do administrador inicial com motivo e mostra o destino mascarado e a validade', async () => {
    const fixture = await render([tenant(), tenant({ id: 'p', slug: 'plataforma', platform: true }), tenant({ id: 't-2', status: 'SUSPENDED' })]);
    const resend = buttons(fixture, 'Reenviar convite');
    // só assessorias ativas, nunca o tenant da plataforma
    expect(resend.length).toBe(1);

    resend[0].click();
    expect(confirmations[0].reason).toBe('required');
    expect(confirmations[0].message).toContain('O link anterior deixa de valer');
    const req = http.expectOne('/api/v1/platform/tenants/t-1/admin-invitation');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ reason: 'Link expirou' });
    req.flush({ tenantId: 't-1', adminUserId: 'u-1', maskedEmail: 'a***@horizonte.com.br', expiresAt: '2026-10-29T15:00:00Z' });

    expect(success).toHaveBeenCalledTimes(1);
    expect(success.mock.calls[0][0]).toContain('a***@horizonte.com.br');
    expect(success.mock.calls[0][0]).toMatch(/O link vale até \d{2}\/\d{2}\/2026 \d{2}:\d{2}\./);
  });
});
