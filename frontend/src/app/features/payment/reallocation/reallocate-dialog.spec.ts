import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { allocation, payment } from '../../../../testing/payment-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapComponentDefaults, provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { ReallocateDialog } from './reallocate-dialog';

describe('ReallocateDialog', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<ReallocateDialog>;
  const close = vi.fn();
  const view = payment({ unallocatedAmount: 50, pendingReallocation: 150, allocations: [allocation({ id: 'al-1', availableForReallocation: 100 })] });

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ReallocateDialog],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(), provideBootstrapComponentDefaults(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: DIALOG_DATA, useValue: { payment: view, debtorName: 'Ana Paula Ribeiro' } },
        { provide: DialogRef, useValue: { close } },
        { provide: AuthService, useValue: { hasPermission: () => true, hasAnyPermission: () => true } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ReallocateDialog);
    fixture.detectChanges();
  });

  afterEach(() => {
    http.match((r) => r.method === 'GET').filter((r) => !r.cancelled).forEach((r) => r.flush({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 }));
    http.verify();
  });

  const submit = () => (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button[type="submit"]')!;

  it('devolução ao pagador da parte não aplicada: referência e justificativa obrigatórias, valor até o disponível', () => {
    const c = fixture.componentInstance;
    c['form'].patchValue({ source: 1, mode: 'REFUND' });
    fixture.detectChanges();
    expect(c['form'].controls.amount.value).toBe('100');
    c['form'].patchValue({ amount: '120', note: 'Devedor pagou em duplicidade' });
    submit().click();
    http.expectNone((r) => r.method === 'POST');
    c['form'].patchValue({ amount: '100', refundReference: 'E2E-DEV-1' });
    submit().click();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/payment/payments/pg-1/reallocations');
    expect(req.request.body).toEqual({ sourceAllocationId: 'al-1', amount: 100, target: null, refundReference: 'E2E-DEV-1', note: 'Devedor pagou em duplicidade' });
    req.flush(view);
    expect(close).toHaveBeenCalled();
  });

  it('novo destino exige escolher acordo ou títulos', () => {
    fixture.componentInstance['form'].patchValue({ note: 'Acordo novo do devedor' });
    submit().click();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Escolha o acordo ou os títulos');
    http.expectNone((r) => r.method === 'POST');
  });
});
