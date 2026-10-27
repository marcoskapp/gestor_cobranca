import { Injectable, InjectionToken, Signal, computed, effect, inject, signal, untracked } from '@angular/core';
import { catchError, of } from 'rxjs';
import { PresenceApi } from '../data/telephony.api';
import { SoftphoneSession } from '../data/telephony.models';
import { TelephonySession } from './telephony-session';

/**
 * Ponto de integração do áudio do operador na estação de cobrança (decisões 569 e 584).
 *
 * Padrão `external`: a plataforma de telefonia liga primeiro para o ramal/telefone do operador
 * (clique para ligar "operador primeiro") ou conecta a chamada atendida a ele (discador). Quando o
 * ramal do operador numa conta Twilio é uma identidade de navegador (`client:<identidade>`), o modo
 * passa a `webrtc`: o Twilio Voice JavaScript SDK — empacotado no build e carregado sob demanda,
 * fora do pacote inicial (nunca CDN: a CSP segue `script-src 'self'`; `connect-src`/`media-src`
 * só ganham as origens da Twilio) — registra o operador com um token curto emitido pelo backend
 * para o próprio usuário e atende automaticamente as chamadas que a plataforma entrega a ele.
 */
export interface SoftphoneAdapter {
  /** `external`: ramal SIP/telefone fora do navegador; `webrtc`: áudio no navegador (SDK do fornecedor). */
  readonly kind: Signal<'external' | 'webrtc'>;
  /** Orientação mostrada antes de ligar. */
  readonly instructions: Signal<string>;
}

export const EXTERNAL_INSTRUCTIONS = 'A plataforma chama primeiro o seu ramal: atenda nele e a ligação para o devedor começa em seguida.';
export const WEBRTC_INSTRUCTIONS = 'O áudio é no navegador: use fone com microfone. A ligação conecta sozinha quando a plataforma chamar.';

/** Chamada de voz no navegador (contrato mínimo do SDK, dublável nos testes). */
export interface VoiceCall {
  accept(): void;
  disconnect(): void;
  mute(muted: boolean): void;
  on(event: 'disconnect' | 'cancel' | 'error', handler: () => void): void;
}

/** Dispositivo de voz registrado na plataforma. */
export interface VoiceDevice {
  register(): Promise<void>;
  updateToken(token: string): void;
  destroy(): void;
  onRegistered(handler: () => void): void;
  onError(handler: (code: number | null) => void): void;
  onIncoming(handler: (call: VoiceCall) => void): void;
  onTokenWillExpire(handler: () => void): void;
}

export type VoiceDeviceFactory = (token: string, options: Record<string, string>) => VoiceDevice;

/** Carregamento sob demanda do SDK (pedaço próprio do build); os testes substituem por um dublê. */
export const VOICE_DEVICE_LOADER = new InjectionToken<() => Promise<VoiceDeviceFactory>>('VOICE_DEVICE_LOADER', {
  providedIn: 'root',
  factory: () => () => import('./twilio-voice-device').then((m) => m.createTwilioDevice),
});

export type SoftphoneState = 'off' | 'connecting' | 'ready' | 'in-call' | 'error';

/**
 * Softphone WebRTC do operador: liga quando o operador entra na telefonia e há sessão de navegador
 * para ele (token do backend), desliga ao sair. Chamadas entregues ao navegador são atendidas
 * automaticamente (o operador pediu a ligação ou está disponível no discador); o encerramento da
 * chamada continua pelo backend (botão "Encerrar"), que avisa a plataforma.
 */
@Injectable({ providedIn: 'root' })
export class WebrtcSoftphone implements SoftphoneAdapter {
  private readonly presenceApi = inject(PresenceApi);
  private readonly session = inject(TelephonySession);
  private readonly loader = inject(VOICE_DEVICE_LOADER);

  private readonly stateValue = signal<SoftphoneState>('off');
  private readonly mutedValue = signal(false);
  private device: VoiceDevice | null = null;
  private current: VoiceCall | null = null;
  private starting = false;

  readonly state = this.stateValue.asReadonly();
  readonly muted = this.mutedValue.asReadonly();
  readonly kind = computed<'external' | 'webrtc'>(() => (this.stateValue() === 'off' ? 'external' : 'webrtc'));
  readonly instructions = computed(() => {
    switch (this.stateValue()) {
      case 'off':
        return EXTERNAL_INSTRUCTIONS;
      case 'error':
        return 'O softphone do navegador não conectou (microfone bloqueado ou rede). Confira a permissão do microfone e entre de novo na telefonia.';
      default:
        return WEBRTC_INSTRUCTIONS;
    }
  });

  constructor() {
    effect(() => {
      const loggedIn = this.session.loggedIn();
      untracked(() => (loggedIn ? this.start() : this.stop()));
    });
  }

  toggleMute(): void {
    if (this.current) {
      const next = !this.mutedValue();
      this.current.mute(next);
      this.mutedValue.set(next);
    }
  }

  /** Inicia o softphone se houver sessão de navegador para o operador (idempotente). */
  start(): void {
    if (this.device || this.starting) {
      return;
    }
    this.starting = true;
    this.presenceApi
      .softphone()
      .pipe(catchError(() => of([] as SoftphoneSession[])))
      .subscribe((sessions) => {
        const twilio = sessions.find((s) => s.provider === 'TWILIO');
        if (!twilio || !this.session.loggedIn()) {
          this.starting = false;
          return;
        }
        this.stateValue.set('connecting');
        this.loader()
          .then((factory) => this.register(factory, twilio))
          .catch(() => this.stateValue.set('error'))
          .finally(() => (this.starting = false));
      });
  }

  stop(): void {
    this.current?.disconnect();
    this.current = null;
    this.device?.destroy();
    this.device = null;
    this.mutedValue.set(false);
    this.stateValue.set('off');
  }

  private register(factory: VoiceDeviceFactory, s: SoftphoneSession): Promise<void> {
    const device = factory(s.token, s.options ?? {});
    this.device = device;
    device.onRegistered(() => this.stateValue.set(this.current ? 'in-call' : 'ready'));
    device.onError(() => this.stateValue.set('error'));
    device.onTokenWillExpire(() =>
      this.presenceApi
        .softphone()
        .pipe(catchError(() => of([] as SoftphoneSession[])))
        .subscribe((sessions) => {
          const renewed = sessions.find((x) => x.accountId === s.accountId);
          if (renewed && this.device === device) {
            device.updateToken(renewed.token);
          }
        }),
    );
    device.onIncoming((call) => {
      this.current = call;
      this.mutedValue.set(false);
      const ended = () => {
        if (this.current === call) {
          this.current = null;
          this.mutedValue.set(false);
          this.stateValue.set(this.device ? 'ready' : 'off');
        }
      };
      call.on('disconnect', ended);
      call.on('cancel', ended);
      call.on('error', ended);
      call.accept();
      this.stateValue.set('in-call');
    });
    return device.register();
  }
}

export const SOFTPHONE = new InjectionToken<SoftphoneAdapter>('SOFTPHONE', { providedIn: 'root', factory: () => inject(WebrtcSoftphone) });
