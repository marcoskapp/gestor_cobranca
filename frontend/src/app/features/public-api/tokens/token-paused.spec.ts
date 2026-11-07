import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { provideAppLocale } from '../../../core/config/app-locale';
import { NotificationService } from '../../../core/notification/notification.service';
import { TOKEN_STATE_LABELS, tokenStateTone } from '../data/public-api.labels';
import { TokenView } from '../data/public-api.models';
import { TokenDetail } from './token-detail';

const token = (overrides: Partial<TokenView> = {}): TokenView => ({
  id: 'k-1', creditorId: 'c-1', name: 'ERP do credor', description: null, prefix: 'gck_abcdefghijkl', scopes: ['titles:read'],
  rateLimitPerMinute: 600, allowedIps: [], expiresAt: null, state: 'ACTIVE', createdBy: null, createdAt: '2026-10-01T12:00:00Z',
  lastUsedAt: null, lastUsedIp: null, revokedAt: null, revokedBy: null, revokeReason: null, rotatedAt: null, rotationCount: 0,
  previousValidUntil: null, revokedByPlatform: false, pausedAt: null, pauseReason: null, resumeAt: null,
  ...overrides,
});

describe('token pausado pela plataforma (visão da assessoria)', () => {
  it('rótulo e tom próprios da pausa (texto, não só cor)', () => {
    expect(TOKEN_STATE_LABELS.PAUSED).toBe('Pausado pela plataforma');
    expect(tokenStateTone('PAUSED')).toBe('warning');
    expect(tokenStateTone('ACTIVE')).toBe('success');
    expect(tokenStateTone('REVOKED')).toBe('danger');
  });

  async function render(t: TokenView, permissions: string[]) {
    TestBed.configureTestingModule({
      providers: [
        provideAppLocale(),
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { user: signal(null), hasPermission: (p: string) => permissions.includes(p), hasAnyPermission: () => false } },
        { provide: NotificationService, useValue: { success: vi.fn(), error: vi.fn(), info: vi.fn() } },
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(TokenDetail);
    fixture.componentRef.setInput('id', t.id);
    fixture.detectChanges();
    http.expectOne((r) => r.url.endsWith('/public-api/tokens/k-1')).flush(t);
    for (let i = 0; i < 3; i++) {
      fixture.detectChanges();
      await new Promise((resolve) => setTimeout(resolve, 0));
    }
    http.match((r) => r.url.includes('/creditors/')).forEach((r) => r.flush({ id: 'c-1', tradeName: 'Banco A', legalName: 'Banco A S.A.' }));
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('mostra motivo e retomada agendada; a assessoria não retoma, mas edita, rotaciona e revoga', async () => {
    const el = await render(token({
      state: 'PAUSED', pausedAt: '2026-11-04T10:00:00Z', pauseReason: 'laço de chamadas', resumeAt: '2026-11-05T10:00:00Z',
    }), ['PUBLIC_API_TOKEN_VIEW', 'PUBLIC_API_TOKEN_MANAGE', 'DEBTOR_VIEW']);
    const text = el.textContent ?? '';
    expect(text).toContain('Pausado pela plataforma');
    expect(text).toContain('laço de chamadas');
    expect(text).toContain('Volta a valer automaticamente');
    const labels = [...el.querySelectorAll('button')].map((b) => b.textContent?.trim());
    expect(labels).toEqual(expect.arrayContaining(['Editar', 'Rotacionar', 'Revogar']));
    expect(labels.some((l) => l?.includes('Retomar'))).toBe(false);
  });

  it('sem as permissões dos escopos do token, Editar e Rotacionar ficam ocultos e Revogar continua (decisão 850)', async () => {
    const el = await render(token({ state: 'ACTIVE' }), ['PUBLIC_API_TOKEN_VIEW', 'PUBLIC_API_TOKEN_MANAGE']);
    const labels = [...el.querySelectorAll('button')].map((b) => b.textContent?.trim());
    expect(labels).toContain('Revogar');
    expect(labels).not.toContain('Editar');
    expect(labels).not.toContain('Rotacionar');
  });

  it('revogação pela plataforma identificada sem expor o usuário da plataforma', async () => {
    const el = await render(token({
      state: 'REVOKED', revokedAt: '2026-11-04T10:00:00Z', revokedByPlatform: true, revokeReason: 'chave vazada',
    }), ['PUBLIC_API_TOKEN_VIEW']);
    expect(el.textContent).toContain('Revogado pela plataforma');
    expect(el.textContent).toContain('Equipe da plataforma');
    expect(el.textContent).toContain('chave vazada');
  });
});
