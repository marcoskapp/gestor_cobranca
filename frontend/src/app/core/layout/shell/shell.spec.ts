import { BreakpointObserver, BreakpointState } from '@angular/cdk/layout';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Observable, of } from 'rxjs';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { routes } from '../../../app.routes';
import { AuthenticatedUser } from '../../auth/auth.models';
import { AuthService } from '../../auth/auth.service';
import { provideAppLocale } from '../../config/app-locale';
import { SupportChatService } from '../../support-chat/support-chat.service';
import { SHELL_NARROW } from './shell';

/** Cabeçalho do shell: o chat sai da barra e entra no menu do usuário só abaixo de 600 px. */
describe('Shell — cabeçalho estreito', () => {
  const user = signal<AuthenticatedUser | null>(fakeUser());
  let narrow = false;
  const chat = { open: vi.fn(), isOpen: () => false };

  beforeEach(() => {
    chat.open.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideAppLocale(),
        provideRouter(routes, withComponentInputBinding()),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SupportChatService, useValue: chat },
        {
          provide: BreakpointObserver,
          useValue: {
            observe: (query: string): Observable<BreakpointState> => of({ matches: query === SHELL_NARROW && narrow, breakpoints: {} }),
            isMatched: () => false,
          },
        },
        {
          provide: AuthService,
          useValue: {
            user,
            pendingStep: signal(null),
            isAuthenticated: () => user() !== null,
            hasPermission: (p: string) => (user()?.permissions ?? []).includes(p),
            hasAnyPermission: (ps: readonly string[]) => ps.length === 0 || ps.some((p) => (user()?.permissions ?? []).includes(p)),
            rememberedTenant: () => '',
            logout: vi.fn(),
          },
        },
      ],
    });
  });

  const chatItem = () => [...document.querySelectorAll<HTMLButtonElement>('.app-user-menu .dropdown-item')].find((b) => b.textContent?.includes('Chat de suporte'));

  it('telas largas: sem o item de chat no menu do usuário (o botão fica na barra)', async () => {
    narrow = false;
    await RouterTestingHarness.create('/');
    expect(document.querySelector('app-shell')).not.toBeNull();
    expect(chatItem()).toBeUndefined();
  }, 20_000);

  it('abaixo de 600 px: o item existe, fecha o menu, devolve o foco ao botão do menu e abre o chat', async () => {
    narrow = true;
    const harness = await RouterTestingHarness.create('/');
    const toggle = document.querySelector<HTMLButtonElement>('[data-tour="user-menu"]')!;
    toggle.click();
    harness.detectChanges();
    const item = chatItem();
    expect(item).toBeDefined();
    item!.click();
    harness.detectChanges();
    expect(document.activeElement).toBe(toggle);
    expect(chat.open).toHaveBeenCalledTimes(1);
  }, 20_000);
});
