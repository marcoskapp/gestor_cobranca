import { Call, Device } from '@twilio/voice-sdk';
import type { VoiceCall, VoiceDevice } from './softphone';

/**
 * Adaptador do Twilio Voice JavaScript SDK (`@twilio/voice-sdk`, empacotado no build e carregado
 * sob demanda só quando o operador tem ramal de navegador). Só recebe chamadas: a ligação é pedida
 * ao backend, que chama este navegador primeiro. Borda (`edge`) vinda do backend, a mesma liberada
 * na CSP; sons do SDK servidos por sdk.twilio.com (liberado em `media-src`).
 */
export function createTwilioDevice(token: string, options: Record<string, string>): VoiceDevice {
  const device = new Device(token, {
    edge: options['edge'] || 'roaming',
    closeProtection: 'Há uma ligação em andamento. Sair mesmo assim?',
    codecPreferences: [Call.Codec.Opus, Call.Codec.PCMU],
  });
  return {
    register: () => device.register(),
    updateToken: (t) => device.updateToken(t),
    destroy: () => device.destroy(),
    onRegistered: (handler) => device.on(Device.EventName.Registered, handler),
    onError: (handler) => device.on(Device.EventName.Error, (error: { code?: number }) => handler(error?.code ?? null)),
    onIncoming: (handler) => device.on(Device.EventName.Incoming, (call: Call) => handler(wrap(call))),
    onTokenWillExpire: (handler) => device.on(Device.EventName.TokenWillExpire, handler),
  };
}

function wrap(call: Call): VoiceCall {
  return {
    accept: () => call.accept(),
    disconnect: () => call.disconnect(),
    mute: (muted) => call.mute(muted),
    on: (event, handler) => call.on(event, handler),
  };
}
