import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Type, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { pixAccount } from '../../../../testing/payment-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideLocale } from '../../../core/config/locale.config';
import { PixAccountDialog, PixAccountDialogData } from './pix-account-dialog';
import { RotateDialog } from './rotate-dialog';

describe('Contas Pix', () => {
  let http: HttpTestingController;
  const close = vi.fn();

  function setup<T>(component: Type<T>, data: unknown): ComponentFixture<T> {
    TestBed.configureTestingModule({
      imports: [component],
      providers: [
        provideLocale(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: { close } },
        { provide: AuthService, useValue: { user: signal(fakeUser({ allCreditors: true })), hasPermission: () => true, hasAnyPermission: () => true } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(component);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    http.match(() => true).forEach((r) => r.flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }));
    http.verify();
    vi.clearAllMocks();
  });

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const submit = (f: ComponentFixture<unknown>) => el(f).querySelector<HTMLButtonElement>('button[type="submit"]')!;

  it('cadastra conta da assessoria com credenciais e certificado em Base64; mTLS exige impressão digital', () => {
    const fixture = setup(PixAccountDialog, {} satisfies PixAccountDialogData);
    const c = fixture.componentInstance as PixAccountDialog;
    c['form'].patchValue({ name: 'Banco X', pixKey: '11222333000181', apiBaseUrl: 'https://pix.bancox.com.br/api/v2', tokenUrl: 'https://pix.bancox.com.br/oauth/token' });
    c['credentials'].patchValue({ clientId: 'id-1', clientSecret: 'segredo', certificatePassword: 'senha' });
    c['certificate'].set('MIIKBASE64==');
    fixture.detectChanges();
    submit(fixture).click();
    http.expectNone((r) => r.method === 'POST');
    expect(c['form'].controls.fingerprints.invalid).toBe(true);

    c['form'].patchValue({ fingerprints: 'AB:'.repeat(31) + 'AB' });
    fixture.detectChanges();
    submit(fixture).click();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/payment/pix-accounts');
    expect(req.request.body).toEqual({
      name: 'Banco X',
      pixKey: '11222333000181',
      apiBaseUrl: 'https://pix.bancox.com.br/api/v2',
      tokenUrl: 'https://pix.bancox.com.br/oauth/token',
      webhookAuth: 'MTLS',
      webhookFingerprints: ['ab'.repeat(32)],
      creditorId: null,
      provider: 'BACEN_API',
      credentials: { clientId: 'id-1', clientSecret: 'segredo', webhookSecret: null, certificateBase64: 'MIIKBASE64==', certificatePassword: 'senha' },
    });
    req.flush(pixAccount());
    // segredos não ficam no formulário depois do envio
    expect(c['credentials'].getRawValue()).toEqual({ clientId: '', clientSecret: '', webhookSecret: '', certificatePassword: '' });
    expect(close).toHaveBeenCalled();
  });

  it('URL sem HTTPS é recusada; edição não envia nem mostra segredos', () => {
    const fixture = setup(PixAccountDialog, { account: pixAccount() } satisfies PixAccountDialogData);
    const c = fixture.componentInstance as PixAccountDialog;
    expect(el(fixture).textContent).not.toContain('client_secret');
    expect(el(fixture).querySelectorAll('input[type="password"]').length).toBe(0);
    c['form'].patchValue({ apiBaseUrl: 'http://pix.bancox.com.br' });
    submit(fixture).click();
    http.expectNone((r) => r.method === 'PUT');
    c['form'].patchValue({ apiBaseUrl: 'https://pix.bancox.com.br/v3' });
    submit(fixture).click();
    const req = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/v1/payment/pix-accounts/pa-1');
    expect(Object.keys(req.request.body)).not.toContain('credentials');
    req.flush(pixAccount());
  });

  it('rotação exige client_id e client_secret e mantém o certificado sem arquivo novo', () => {
    const fixture = setup(RotateDialog, { account: pixAccount() });
    const c = fixture.componentInstance as RotateDialog;
    const rotate = [...el(fixture).querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes('Rotacionar'))!;
    expect(el(fixture).textContent).toContain('1212 1212');
    rotate.click();
    fixture.detectChanges();
    expect(el(fixture).querySelector('[role="alert"]')?.textContent).toContain('client_id');
    c['form'].patchValue({ clientId: 'novo', clientSecret: 'novo-segredo' });
    rotate.click();
    const req = http.expectOne((r) => r.method === 'PUT' && r.url.endsWith('/credentials'));
    expect(req.request.body).toEqual({ clientId: 'novo', clientSecret: 'novo-segredo', webhookSecret: null, certificateBase64: null, certificatePassword: null });
    req.flush(pixAccount());
  });
});
