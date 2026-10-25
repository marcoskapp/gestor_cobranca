import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { statementImport } from '../../../../testing/payment-fixtures';
import { provideAppLocale } from '../../../core/config/app-locale';
import { provideBootstrapDefaults } from '../../../core/config/bootstrap.config';
import { provideBootstrapComponentDefaults } from '../../../core/config/bootstrap-components.config';
import { NotificationService } from '../../../core/notification/notification.service';
import { DialogRef } from '../../../shared/dialog/dialog-ref';
import { StatementUploadDialog } from './statement-upload-dialog';

const csv = (text: string, name = 'extrato.csv') => new File([text], name, { type: 'text/csv' });

describe('StatementUploadDialog', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<StatementUploadDialog>;
  const close = vi.fn();

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [StatementUploadDialog],
      providers: [
        provideAppLocale(),
        provideBootstrapDefaults(), provideBootstrapComponentDefaults(),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: DialogRef, useValue: { close } },
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn() } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(StatementUploadDialog);
    fixture.detectChanges();
  });

  afterEach(() => {
    http.verify();
    vi.clearAllMocks();
  });

  const el = () => fixture.nativeElement as HTMLElement;
  const importButton = () => [...el().querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent?.includes('Importar extrato'))!;

  it('prévia com problema bloqueia o envio e aponta a linha', async () => {
    await fixture.componentInstance['pick']([csv('data;valor\n2026-10-20;12.345,00\n')] as unknown as FileList);
    fixture.detectChanges();
    expect(el().textContent).toContain('Linha 2:');
    expect(el().textContent).toContain('o servidor recusa o extrato inteiro');
    expect(importButton().disabled).toBe(true);
  });

  it('prévia válida mostra os totais e envia o arquivo; repetido explica a recusa', async () => {
    await fixture.componentInstance['pick']([csv('data;valor;tipo\n2026-10-20;171,05;C\n2026-10-20;10;D\n')] as unknown as FileList);
    fixture.detectChanges();
    expect(el().textContent).toContain('Total creditado');
    expect(el().textContent).toContain('171,05');
    expect(importButton().disabled).toBe(false);
    importButton().click();
    const first = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/payment/reconciliation/statements');
    expect(first.request.params.get('format')).toBe('CSV_GENERIC');
    expect(first.request.params.has('creditorId')).toBe(false);
    first.flush({ code: 'statement.already_imported', detail: 'Arquivo já importado' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(el().querySelector('[role="alert"]')?.textContent).toContain('já foi importado');
    importButton().click();
    http.expectOne((r) => r.method === 'POST').flush(statementImport({ status: 'RECEIVED' }));
    expect(close).toHaveBeenCalledWith(expect.objectContaining({ id: 'st-1' }));
  });

  it('recusa arquivo que não é CSV antes de ler', async () => {
    await fixture.componentInstance['pick']([csv('x', 'extrato.ofx')] as unknown as FileList);
    fixture.detectChanges();
    expect(el().textContent).toContain('Envie o extrato em CSV');
    expect(fixture.componentInstance['preview']()).toBeNull();
  });
});
