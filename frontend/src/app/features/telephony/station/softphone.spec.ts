import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { SoftphoneSession } from '../data/telephony.models';
import { EXTERNAL_INSTRUCTIONS, SOFTPHONE, VOICE_DEVICE_LOADER, VoiceCall, VoiceDevice, WEBRTC_INSTRUCTIONS, WebrtcSoftphone } from './softphone';
import { TelephonySession } from './telephony-session';

/** Dublê do SDK de voz: registra os manipuladores e deixa o teste disparar os eventos. */
class FakeDevice implements VoiceDevice {
  registered = false;
  destroyed = false;
  tokens: string[] = [];
  handlers: Record<string, (arg?: unknown) => void> = {};
  constructor(
    readonly token: string,
    readonly options: Record<string, string>,
  ) {}
  register() {
    this.registered = true;
    this.handlers['registered']?.();
    return Promise.resolve();
  }
  updateToken(t: string) {
    this.tokens.push(t);
  }
  destroy() {
    this.destroyed = true;
  }
  onRegistered(h: () => void) {
    this.handlers['registered'] = h;
  }
  onError(h: (code: number | null) => void) {
    this.handlers['error'] = h as (arg?: unknown) => void;
  }
  onIncoming(h: (call: VoiceCall) => void) {
    this.handlers['incoming'] = h as (arg?: unknown) => void;
  }
  onTokenWillExpire(h: () => void) {
    this.handlers['tokenWillExpire'] = h;
  }
}

class FakeCall implements VoiceCall {
  accepted = false;
  muted: boolean[] = [];
  handlers: Record<string, () => void> = {};
  accept() {
    this.accepted = true;
  }
  disconnect() {
    this.handlers['disconnect']?.();
  }
  mute(m: boolean) {
    this.muted.push(m);
  }
  on(event: string, h: () => void) {
    this.handlers[event] = h;
  }
}

const session = (o: Partial<SoftphoneSession> = {}): SoftphoneSession => ({
  accountId: 'acc-tw',
  provider: 'TWILIO',
  identity: 'op_x',
  token: 'jwt-1',
  expiresAt: '2026-10-26T13:00:00Z',
  options: { edge: 'roaming' },
  ...o,
});

describe('WebrtcSoftphone (Twilio Voice SDK dublado)', () => {
  let http: HttpTestingController;
  const loggedIn = signal(false);
  let devices: FakeDevice[];
  const loader = vi.fn();

  beforeEach(() => {
    devices = [];
    loggedIn.set(false);
    loader.mockReset();
    loader.mockImplementation(() =>
      Promise.resolve((token: string, options: Record<string, string>) => {
        const d = new FakeDevice(token, options);
        devices.push(d);
        return d;
      }),
    );
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: TelephonySession, useValue: { loggedIn } },
        { provide: VOICE_DEVICE_LOADER, useValue: loader },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  const flushAsync = async () => {
    for (let i = 0; i < 5; i++) {
      await Promise.resolve();
    }
  };

  it('sem ramal de navegador: continua no ramal externo e nem carrega o SDK', async () => {
    const phone = TestBed.inject(WebrtcSoftphone);
    loggedIn.set(true);
    TestBed.tick();
    http.expectOne({ method: 'POST', url: '/api/v1/telephony/presence/me/softphone' }).flush([]);
    await flushAsync();
    expect(loader).not.toHaveBeenCalled();
    expect(phone.kind()).toBe('external');
    expect(TestBed.inject(SOFTPHONE).instructions()).toBe(EXTERNAL_INSTRUCTIONS);
  });

  it('com sessão Twilio: carrega o SDK sob demanda, registra, atende sozinho e silencia', async () => {
    const phone = TestBed.inject(WebrtcSoftphone);
    loggedIn.set(true);
    TestBed.tick();
    http.expectOne('/api/v1/telephony/presence/me/softphone').flush([session()]);
    await flushAsync();
    expect(loader).toHaveBeenCalledTimes(1);
    expect(devices[0].token).toBe('jwt-1');
    expect(devices[0].options).toEqual({ edge: 'roaming' });
    expect(devices[0].registered).toBe(true);
    expect(phone.state()).toBe('ready');
    expect(phone.instructions()).toBe(WEBRTC_INSTRUCTIONS);

    const call = new FakeCall();
    devices[0].handlers['incoming'](call);
    expect(call.accepted).toBe(true);
    expect(phone.state()).toBe('in-call');
    phone.toggleMute();
    expect(call.muted).toEqual([true]);
    expect(phone.muted()).toBe(true);
    call.handlers['disconnect']();
    expect(phone.state()).toBe('ready');
    expect(phone.muted()).toBe(false);
  });

  it('renova o token antes de vencer e desliga ao sair da telefonia', async () => {
    const phone = TestBed.inject(WebrtcSoftphone);
    loggedIn.set(true);
    TestBed.tick();
    http.expectOne('/api/v1/telephony/presence/me/softphone').flush([session()]);
    await flushAsync();
    devices[0].handlers['tokenWillExpire']();
    http.expectOne('/api/v1/telephony/presence/me/softphone').flush([session({ token: 'jwt-2' })]);
    expect(devices[0].tokens).toEqual(['jwt-2']);

    loggedIn.set(false);
    TestBed.tick();
    expect(devices[0].destroyed).toBe(true);
    expect(phone.state()).toBe('off');
  });

  it('falha ao carregar o SDK (ou microfone negado): estado de erro com orientação', async () => {
    loader.mockImplementation(() => Promise.reject(new Error('sem rede')));
    const phone = TestBed.inject(WebrtcSoftphone);
    loggedIn.set(true);
    TestBed.tick();
    http.expectOne('/api/v1/telephony/presence/me/softphone').flush([session()]);
    await flushAsync();
    expect(phone.state()).toBe('error');
    expect(phone.instructions()).toContain('microfone');
  });
});
