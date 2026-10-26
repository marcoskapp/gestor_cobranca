import { InjectionToken } from '@angular/core';

/**
 * Ponto de integração do áudio do operador na estação de cobrança (decisão 569).
 *
 * Hoje o áudio chega pelo ramal: a plataforma de telefonia liga primeiro para o ramal/telefone do
 * operador (clique para ligar "operador primeiro") ou conecta a chamada atendida a ele (discador).
 * Nem a Alvaria nem a Tarifando publicam SDK WebRTC de navegador; quando um fornecedor entregar o
 * SDK, a implementação `webrtc` deste contrato registra o operador, atende e desliga no navegador —
 * o SDK é empacotado no build (nunca carregado de CDN externa: a CSP continua `script-src 'self'`),
 * com `connect-src`/`media-src` acrescentados só para os domínios do fornecedor, e o ramal do
 * operador na conta passa a ser a identidade WebRTC.
 */
export interface SoftphoneAdapter {
  /** `external`: ramal SIP/telefone fora do navegador; `webrtc`: áudio no navegador (SDK do fornecedor). */
  readonly kind: 'external' | 'webrtc';
  /** Orientação mostrada antes de ligar. */
  readonly instructions: string;
}

/** Padrão: o operador atende no próprio ramal (softphone instalado, aparelho SIP ou telefone). */
export const EXTERNAL_PHONE: SoftphoneAdapter = {
  kind: 'external',
  instructions: 'A plataforma chama primeiro o seu ramal: atenda nele e a ligação para o devedor começa em seguida.',
};

export const SOFTPHONE = new InjectionToken<SoftphoneAdapter>('SOFTPHONE', { providedIn: 'root', factory: () => EXTERNAL_PHONE });
