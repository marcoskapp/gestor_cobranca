import { DOCUMENT, Injectable, OnDestroy, inject } from '@angular/core';

const EVENTS = ['pointerdown', 'keydown', 'wheel', 'touchstart'] as const;

/**
 * Registra o instante da última interação do usuário. A renovação proativa do token só ocorre se
 * houve atividade, para não manter a sessão viva indefinidamente numa aba esquecida e respeitar o
 * tempo de inatividade definido pelo servidor.
 */
@Injectable({ providedIn: 'root' })
export class UserActivity implements OnDestroy {
  private readonly document = inject(DOCUMENT);
  private last = Date.now();
  private readonly listener = (): void => {
    this.last = Date.now();
  };

  constructor() {
    for (const event of EVENTS) {
      this.document.addEventListener(event, this.listener, { passive: true, capture: true });
    }
  }

  lastActivityAt(): number {
    return this.last;
  }

  ngOnDestroy(): void {
    for (const event of EVENTS) {
      this.document.removeEventListener(event, this.listener, { capture: true });
    }
  }
}
