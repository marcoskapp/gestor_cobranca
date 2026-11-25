import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthenticatedUser } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { QueueView } from '../data/collection.models';
import { QueueList } from './queue-list';

const queue: QueueView = {
  id: 'q-1',
  code: 'FILA_TESTE',
  name: 'Fila Credor Fictício',
  description: null,
  active: true,
  priority: 10,
  creditorId: null,
  portfolioId: null,
  minDaysOverdue: null,
  maxDaysOverdue: null,
  minBalance: null,
  maxBalance: null,
  minScore: null,
  maxScore: null,
  ordering: 'OLDEST_OVERDUE',
  strategy: 'PULL',
  maxCasesPerUser: null,
  teamIds: [],
  updatedAt: '2026-11-25T12:00:00Z',
  version: 1,
};

describe('QueueList (filas de cobrança)', () => {
  let http: HttpTestingController;
  let permissions: string[];

  beforeEach(() => {
    const user = signal<AuthenticatedUser>(fakeUser({ permissions: ['QUEUE_MANAGE'], allCreditors: true }));
    TestBed.configureTestingModule({
      imports: [QueueList],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: { user, hasPermission: (p: string) => permissions.includes(p), hasAnyPermission: (ps: string[]) => ps.some((p) => permissions.includes(p)) },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  async function nameCell(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(QueueList);
    fixture.detectChanges();
    http.expectOne('/api/v1/collection/queues').flush([queue]);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const code = (fixture.nativeElement as HTMLElement).querySelector('td .app-mono');
    return code!.closest('td') as HTMLElement;
  }

  // o `&ngsp;` logo depois do fechamento de um bloco (`}`) era descartado e a tela mostrava "Fila Credor FictícioFILA_TESTE"
  it('separa o nome da fila do código por um espaço, com link para os casos', async () => {
    permissions = ['QUEUE_MANAGE', 'CASE_VIEW'];
    const cell = await nameCell();
    expect(cell.querySelector('a')?.textContent).toBe('Fila Credor Fictício');
    expect(cell.textContent).toContain('Fila Credor Fictício FILA_TESTE');
  });

  it('separa o nome da fila do código por um espaço também sem link (sem acesso aos casos)', async () => {
    permissions = ['QUEUE_MANAGE'];
    const cell = await nameCell();
    expect(cell.querySelector('a')).toBeNull();
    expect(cell.textContent).toContain('Fila Credor Fictício FILA_TESTE');
  });
});
