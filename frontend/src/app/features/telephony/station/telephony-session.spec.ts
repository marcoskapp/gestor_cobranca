import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { agentView, callView } from '../../../../testing/telephony-fixtures';
import { TelephonySession } from './telephony-session';

describe('TelephonySession', () => {
  let http: HttpTestingController;
  let session: TelephonySession;
  const user = signal<ReturnType<typeof fakeUser> | null>(fakeUser({ permissions: ['TELEPHONY_USE'] }));

  beforeEach(() => {
    vi.useFakeTimers();
    user.set(fakeUser({ permissions: ['TELEPHONY_USE'] }));
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: AuthService, useValue: { user, hasPermission: (p: string) => p === 'TELEPHONY_USE' } }],
    });
    http = TestBed.inject(HttpTestingController);
    session = TestBed.inject(TelephonySession);
  });

  afterEach(() => {
    session.stopTimers();
    vi.useRealTimers();
  });

  it('usuário sem ramal: fica marcado como não configurado, sem sinal periódico', () => {
    session.refresh();
    http.expectOne('/api/v1/telephony/presence/me').flush({ code: 'telephony.agent_not_configured' }, { status: 409, statusText: 'Conflict' });
    http.expectOne('/api/v1/telephony/calls/awaiting-tabulation').flush([]);
    TestBed.tick();
    expect(session.configured()).toBe(false);
    vi.advanceTimersByTime(31_000);
    http.expectNone('/api/v1/telephony/presence/me/heartbeat');
  });

  it('conectado: envia o sinal a cada 30 s e para ao sair', () => {
    session.login(null).subscribe();
    http.expectOne('/api/v1/telephony/presence/me/login').flush(agentView({ status: 'AVAILABLE' }));
    TestBed.tick();
    vi.advanceTimersByTime(30_000);
    http.expectOne('/api/v1/telephony/presence/me/heartbeat').flush(null);
    vi.advanceTimersByTime(30_000);
    http.expectOne('/api/v1/telephony/presence/me/heartbeat').flush(null);
    session.logout().subscribe();
    http.expectOne('/api/v1/telephony/presence/me/logout').flush(agentView({ status: 'OFFLINE' }));
    TestBed.tick();
    vi.advanceTimersByTime(60_000);
    http.expectNone('/api/v1/telephony/presence/me/heartbeat');
  });

  it('acompanha a chamada até o fim e passa a exigir a tabulação', () => {
    session.dial('k-1', 'ct-1', 'key-1').subscribe();
    const req = http.expectOne('/api/v1/telephony/calls');
    expect(req.request.headers.get('Idempotency-Key')).toBe('key-1');
    req.flush(callView());
    http.expectOne('/api/v1/telephony/presence/me').flush(agentView({ status: 'ON_CALL', currentCallId: 'call-1' }));
    expect(session.call()?.status).toBe('QUEUED');
    vi.advanceTimersByTime(2_000);
    http.expectOne('/api/v1/telephony/calls/call-1').flush(callView({ status: 'ANSWERED' }));
    vi.advanceTimersByTime(2_000);
    http.expectOne('/api/v1/telephony/calls/call-1').flush(callView({ status: 'COMPLETED', result: 'ANSWERED', endedAt: '2026-10-24T12:05:00Z', interactionState: 'AWAITING_TABULATION' }));
    http.expectOne('/api/v1/telephony/presence/me').flush(agentView({ status: 'WRAP_UP' }));
    expect(session.pendingTabulation()?.id).toBe('call-1');
    vi.advanceTimersByTime(4_000);
    http.expectNone('/api/v1/telephony/calls/call-1');
  });

  it('negada pelo porteiro: não acompanha nem reserva', () => {
    session.dial('k-1', null, 'key-2').subscribe();
    http.expectOne('/api/v1/telephony/calls').flush(callView({ status: 'FAILED', failureCode: 'CONTACT_DENIED', denialReasons: ['OUTSIDE_HOURS'] }));
    expect(session.call()).toBeNull();
    vi.advanceTimersByTime(5_000);
    http.verify();
  });

  it('fim da sessão do usuário: limpa o estado', () => {
    session.login(null).subscribe();
    http.expectOne('/api/v1/telephony/presence/me/login').flush(agentView({ status: 'AVAILABLE' }));
    user.set(null);
    TestBed.tick();
    expect(session.agent()).toBeNull();
    vi.advanceTimersByTime(60_000);
    http.expectNone('/api/v1/telephony/presence/me/heartbeat');
  });
});
