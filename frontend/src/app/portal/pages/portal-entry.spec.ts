import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideLocale } from '../../core/config/locale.config';
import { PortalSite } from '../layout/portal-site';
import { PortalEntry, lockMessage } from './portal-entry';
import { HttpErrorResponse } from '@angular/common/http';

describe('PortalEntry', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      imports: [PortalEntry],
      providers: [provideLocale(), provideRouter([]), provideHttpClient(), provideHttpClientTesting(), PortalSite],
    });
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(PortalSite).set({
      slug: 'banco-a', displayName: 'Banco A', primaryColor: '#1D4E89', accentColor: '#0B6E4F', hasLogo: false, logoVersion: null,
      otpChannels: ['WHATSAPP'], knowledgeChallenge: 'BIRTH_DATE', supportText: null, privacyNoticeVersion: 1, privacyNotice: 'x',
    });
  });

  afterEach(() => http.verify());

  function render() {
    const fixture = TestBed.createComponent(PortalEntry);
    fixture.componentRef.setInput('slug', 'banco-a');
    fixture.detectChanges();
    return fixture;
  }

  it('não envia documento inválido e mostra a mesma mensagem neutra após pedir o código', async () => {
    const fixture = render();
    const el: HTMLElement = fixture.nativeElement;
    const input = el.querySelector('input') as HTMLInputElement;
    input.value = '52998224724';
    input.dispatchEvent(new Event('input'));
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    http.expectNone('/api/portal/v1/sites/banco-a/access/code');
    expect(el.textContent).toContain('Confira o CPF ou CNPJ');

    input.value = '52998224725';
    input.dispatchEvent(new Event('input'));
    expect(input.value).toBe('529.982.247-25');
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const req = http.expectOne('/api/portal/v1/sites/banco-a/access/code');
    expect(req.request.body).toEqual({ document: '529.982.247-25', channel: 'WHATSAPP' });
    req.flush({ challengeId: 'c1', expiresAt: new Date().toISOString(), channel: 'WHATSAPP', knowledgeChallenge: 'BIRTH_DATE', codeRequired: true, codeLength: 6 });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el.textContent).toContain('Se o documento estiver cadastrado');
    expect(el.textContent).toContain('Data de nascimento');
  });

  it('bloqueio progressivo vira mensagem de espera em minutos', () => {
    const error = new HttpErrorResponse({ status: 429, error: { code: 'portal.access_locked', retryAfterSeconds: 900 } });
    expect(lockMessage(error)).toContain('15 minutos');
    expect(lockMessage(new HttpErrorResponse({ status: 401 }))).toBeNull();
  });
});
