import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { Provider, Type, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../app/core/auth/auth.service';
import { provideAppLocale } from '../app/core/config/app-locale';
import { NotificationService } from '../app/core/notification/notification.service';
import { fakeUser } from './auth-fixtures';

export const tick = () => new Promise((r) => setTimeout(r, 0));

export interface Harness {
  http: HttpTestingController;
  /** Mensagens enviadas ao usuário, por tipo (sem depender do `vi`: o arquivo também é compilado no build). */
  notifications: { success: string[]; error: string[]; info: string[] };
}

/** TestBed com HTTP de teste, rotas vazias, locale pt-BR, usuário com as permissões e notificações simuladas. */
export function setup(component: Type<unknown>, permissions: string[], userId = 'u-1', providers: Provider[] = []): Harness {
  const notifications = { success: [] as string[], error: [] as string[], info: [] as string[] };
  const notifier = {
    success: (m: string) => notifications.success.push(m),
    error: (m: string) => notifications.error.push(m),
    info: (m: string) => notifications.info.push(m),
  };
  TestBed.configureTestingModule({
    imports: [component],
    providers: [
      provideAppLocale(),
      provideRouter([{ path: '**', children: [] }]),
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: NotificationService, useValue: notifier },
      {
        provide: AuthService,
        useFactory: () => ({
          user: signal(fakeUser({ id: userId, permissions })),
          hasPermission: (p: string) => permissions.includes(p),
          hasAnyPermission: (ps: string[]) => ps.some((p) => permissions.includes(p)),
        }),
      },
      ...providers,
    ],
  });
  return { http: TestBed.inject(HttpTestingController), notifications };
}

/**
 * Renderiza respondendo às requisições GET pelo `respond` (url → corpo); as não mapeadas recebem
 * `{}` (ou lista vazia quando o caminho termina em `s`).
 */
export async function render<T>(
  type: Type<T>,
  http: HttpTestingController,
  respond: (url: string, req: TestRequest) => unknown,
  inputs: Record<string, unknown> = {},
  rounds = 5,
): Promise<ComponentFixture<T>> {
  const fixture = TestBed.createComponent(type);
  for (const [k, v] of Object.entries(inputs)) {
    fixture.componentRef.setInput(k, v);
  }
  for (let i = 0; i < rounds; i++) {
    fixture.detectChanges();
    await tick();
    for (const req of http.match((r) => r.method === 'GET')) {
      const body = respond(req.request.url, req);
      req.flush(body === undefined ? {} : (body as object));
    }
  }
  fixture.detectChanges();
  return fixture;
}

export const text = (f: ComponentFixture<unknown>) => (f.nativeElement as HTMLElement).textContent ?? '';

export const button = (f: ComponentFixture<unknown>, label: string) =>
  [...(f.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>('button, a')].find((b) => b.textContent?.trim().startsWith(label) || b.textContent?.includes(label)) as HTMLButtonElement | undefined;

export const page = <T>(content: T[]) => ({ content, page: 0, size: 20, totalElements: content.length, totalPages: 1 });
