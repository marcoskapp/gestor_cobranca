import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthenticatedUser } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { previewBatch } from '../review/review.fixtures';
import { SubjectBlockView } from '../data/ingestion.models';
import { SubjectBlockCard } from './subject-block-card';

const view: SubjectBlockView = {
  batchId: 'b-1',
  blockedAt: '2026-11-26T12:00:00Z',
  fileName: 'carteira.csv',
  fileSentAt: '2026-11-20T12:00:00Z',
  protocol: 'abcdef012345',
  lines: [{ rowNumber: 2, reference: 'R-7', matchedBy: 'TITLE_REFERENCE' }],
  linesTotal: 1,
  message: 'Prezados(as), Banco A,\n- Linha 2: título R-7',
  notice: { id: 'n-1', status: 'PENDING', notifiedAt: null },
  request: null,
  inProgress: false,
};

describe('SubjectBlockCard', () => {
  let http: HttpTestingController;
  const user = signal<AuthenticatedUser>(fakeUser({ permissions: ['IMPORT_VIEW'] }));
  let permissions: string[] = [];

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [SubjectBlockCard],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            user,
            hasPermission: (p: string) => permissions.includes(p),
            hasAnyPermission: (ps: string[]) => ps.some((p) => permissions.includes(p)),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render() {
    const fixture = TestBed.createComponent(SubjectBlockCard);
    fixture.componentRef.setInput('batch', previewBatch({ id: 'b-1', blockedReason: 'SUBJECT_ANONYMIZED' }));
    fixture.detectChanges();
    http.expectOne('/api/v1/ingestion/batches/b-1/subject-block').flush(view);
    for (const req of http.match((r) => r.url.includes('/creditors'))) {
      req.flush({ id: req.request.url.split('/').pop(), legalName: 'Banco A S.A.', tradeName: 'Banco A' });
    }
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('explica a recusa, lista as linhas e mostra o texto; registrar só com IMPORT_EXECUTE', async () => {
    permissions = ['IMPORT_VIEW'];
    const el = await render();
    expect(el.textContent).toContain('Linhas recusadas');
    expect(el.querySelector('table')?.textContent).toContain('R-7');
    expect(el.querySelector('.sb-text')?.textContent).toContain('Linha 2: título R-7');
    expect(el.textContent).toContain('Copiar texto');
    expect(el.textContent).not.toContain('Registrar pedido enviado');
    // sem a permissão do compliance, o aviso não vira link
    expect(el.querySelector('a[href*="avisos-credores"]')).toBeNull();
  });

  it('com IMPORT_EXECUTE oferece o registro do pedido', async () => {
    permissions = ['IMPORT_VIEW', 'IMPORT_EXECUTE'];
    const el = await render();
    expect(el.textContent).toContain('Registrar pedido enviado');
  });
});
