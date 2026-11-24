import { Injectable } from '@angular/core';

/**
 * Token do link do e-mail (convite ou nova senha), guardado só em memória e de uso único: a moldura tira o `#token=` da
 * barra de endereços logo ao abrir (antes de carregar a marca) e a tela o lê daqui.
 */
@Injectable({ providedIn: 'root' })
export class CreditorLinkToken {
  private value: string | null = null;

  keep(token: string | null): void {
    if (token) {
      this.value = token;
    }
  }

  /** Devolve e esquece o token. */
  take(): string | null {
    const token = this.value;
    this.value = null;
    return token;
  }
}
