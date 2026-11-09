import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthenticatedUser } from '../../../core/auth/auth.models';
import { AuthService } from '../../../core/auth/auth.service';
import { PageResponse } from '../../../shared/models/page';
import { ComplaintView } from './complaint.data';
import { ComplaintList } from './complaint-list';

const BASE = '/api/v1/complaints';

function page(content: ComplaintView[], total = content.length): PageResponse<ComplaintView> {
  return { content, page: 0, size: 20, totalElements: total, totalPages: 1 };
}

describe('ComplaintList', () => {
  let http: HttpTestingController;
  let granted: string[] = [];
  const user = signal<AuthenticatedUser>(fakeUser({ permissions: [] }));

  function setup(permissions: string[]): void {
    granted = permissions;
    user.set(fakeUser({ permissions, allCreditors: true }));
    TestBed.configureTestingModule({
      imports: [ComplaintList],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            user,
            hasPermission: (p: string) => granted.includes(p),
            hasAnyPermission: (ps: readonly string[]) => ps.some((p) => granted.includes(p)),
          },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  }

  async function render(): Promise<ComponentFixture<ComplaintList>> {
    const fixture = TestBed.createComponent(ComplaintList);
    fixture.detectChanges();
    for (const req of http.match((r) => r.url === BASE && r.params.get('size') === '1')) {
      req.flush(page([], req.request.params.get('deadline') === 'overdue' ? 2 : 0));
    }
    for (const req of http.match((r) => r.url === BASE && r.params.get('size') === '20')) {
      req.flush(page([]));
    }
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('quem registra vê "Registrar reclamação", mas não a configuração nem "Minhas"', async () => {
    setup(['COMPLAINT_REGISTER']);
    const el = (await render()).nativeElement as HTMLElement;
    expect(el.querySelector('[data-tour="complaint-new"]')).not.toBeNull();
    expect(el.textContent).not.toContain('Prazos e indicador');
    expect(el.textContent).not.toContain('Minhas em aberto');
    expect(el.querySelector('.cl-kpi__value.cl-late')?.textContent?.trim()).toBe('2');
  });

  it('quem trata vê a configuração e "Minhas em aberto"', async () => {
    setup(['COMPLAINT_MANAGE']);
    const el = (await render()).nativeElement as HTMLElement;
    expect(el.textContent).toContain('Prazos e indicador');
    expect(el.textContent).toContain('Minhas em aberto');
  });

  it('sem permissão de registrar, o botão fica oculto', async () => {
    setup([]);
    const el = (await render()).nativeElement as HTMLElement;
    expect(el.querySelector('[data-tour="complaint-new"]')).toBeNull();
  });
});
