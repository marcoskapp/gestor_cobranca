import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { provideLocale } from '../../../core/config/locale.config';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { ConfirmDialogData } from '../../../shared/components/confirm-dialog/confirm-dialog';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EffectivePolicyView, PolicyView } from '../data/compliance.models';
import { ContactPolicyPage } from './contact-policy';

const BASE = '/api/v1/compliance/contact-policies';

const v1: PolicyView = {
  id: 'pol-1',
  creditorId: null,
  version: 1,
  effectiveFrom: '2026-01-01T11:00:00Z',
  notes: 'Versão inicial',
  publishedAt: '2026-01-01T11:00:00Z',
  publishedBy: null,
  rules: [
    {
      channel: 'PHONE',
      inheritsWindow: false,
      windowStart: '08:00:00',
      windowEnd: '20:00:00',
      allowedDays: ['MONDAY', 'TUESDAY'],
      maxPerDay: 3,
      maxPerWeek: 10,
      minIntervalMinutes: null,
      blockOnHolidays: true,
    },
  ],
};

const effective: EffectivePolicyView = {
  creditorId: null,
  tenantPolicyVersion: 1,
  creditorPolicyVersion: null,
  channels: [
    {
      channel: 'PHONE',
      ruleSource: 'TENANT',
      windowSource: 'RULE',
      windowStart: '08:00:00',
      windowEnd: '20:00:00',
      allowedDays: ['MONDAY', 'TUESDAY'],
      maxPerDay: 3,
      maxPerWeek: 10,
      minIntervalMinutes: null,
      blockOnHolidays: true,
    },
  ],
};

describe('ContactPolicyPage', () => {
  let http: HttpTestingController;
  const confirmations: ConfirmDialogData[] = [];

  beforeEach(() => {
    confirmations.length = 0;
    TestBed.configureTestingModule({
      imports: [ContactPolicyPage],
      providers: [
        provideLocale(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { user: signal(fakeUser()), hasPermission: () => true, hasAnyPermission: () => true } },
        {
          provide: ConfirmService,
          useValue: {
            confirm: (data: ConfirmDialogData) => {
              confirmations.push(data);
              return of('');
            },
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(): Promise<ComponentFixture<ContactPolicyPage>> {
    const fixture = TestBed.createComponent(ContactPolicyPage);
    fixture.detectChanges();
    http.expectOne(`${BASE}/effective`).flush(effective);
    http.expectOne(BASE).flush([v1]);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('mostra regras vigentes, versões e o rascunho da próxima versão', async () => {
    const fixture = await render();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Assessoria: versão 1');
    expect(text).toContain('Rascunho da versão 2');
    expect(text).toContain('Versão 1');
    expect(text).toContain('3/dia · 10/semana');
  });

  it('publica a nova versão com as regras do rascunho após confirmação com o resumo', async () => {
    const fixture = await render();
    const publish = [...fixture.nativeElement.querySelectorAll('button')].find((b: HTMLButtonElement) =>
      b.textContent?.includes('Publicar nova versão'),
    ) as HTMLButtonElement;
    publish.click();

    expect(confirmations[0].title).toBe('Publicar versão 2');
    expect(confirmations[0].details).toEqual([
      { label: 'Política', value: 'Padrão da assessoria' },
      { label: 'Vigência', value: 'Imediata' },
      { label: 'Canais', value: 'Telefone' },
    ]);
    const req = http.expectOne({ method: 'POST', url: BASE });
    expect(req.request.body).toEqual({
      creditorId: null,
      effectiveFrom: null,
      notes: null,
      rules: [
        {
          channel: 'PHONE',
          windowStart: '08:00',
          windowEnd: '20:00',
          allowedDays: ['MONDAY', 'TUESDAY'],
          maxPerDay: 3,
          maxPerWeek: 10,
          minIntervalMinutes: null,
          blockOnHolidays: true,
        },
      ],
    });
    req.flush({ ...v1, id: 'pol-2', version: 2 });
    TestBed.tick();
    http.expectOne(BASE).flush([{ ...v1, id: 'pol-2', version: 2 }, v1]);
    http.expectOne(`${BASE}/effective`).flush(effective);
  });
});
