import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { payment } from '../../../../testing/payment-fixtures';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapComponentDefaults, provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { ReverseDialog } from './reverse-dialog';

describe('ReverseDialog', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<ReverseDialog>;
  const close = vi.fn();

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ReverseDialog],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(), provideBootstrapComponentDefaults(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: DIALOG_DATA, useValue: { payment: payment(), debtorName: 'Ana Paula Ribeiro' } },
        { provide: DialogRef, useValue: { close } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ReverseDialog);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  const submit = () => (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[type="submit"]')!;

  it('só habilita com motivo, justificativa e a palavra ESTORNAR; mostra o impacto com sinal', () => {
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('−R$');
    expect(text).toContain('Acordo · 1 parcela(s)');
    expect(submit().disabled).toBe(true);
    fixture.componentInstance['form'].patchValue({ reason: 'BANK_RETURN', note: 'TED devolvida pelo banco', confirmation: 'estornar' });
    fixture.detectChanges();
    expect(submit().disabled).toBe(false);
    submit().click();
    const req = http.expectOne('/api/v1/payment/payments/pg-1/reverse');
    expect(req.request.body).toEqual({ reason: 'BANK_RETURN', note: 'TED devolvida pelo banco' });
    req.flush(payment({ status: 'REVERSED' }));
    expect(close).toHaveBeenCalled();
  });

  it('não oferece a devolução do PSP como motivo manual', () => {
    expect(fixture.componentInstance['reasons']).not.toContain('PROVIDER_REFUND');
  });
});
