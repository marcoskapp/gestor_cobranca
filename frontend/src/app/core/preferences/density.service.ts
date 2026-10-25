import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';

const FINE_POINTER = '(pointer: fine)';

/**
 * Densidade efetiva para componentes que escolhem modificadores do Bootstrap (`table-sm`,
 * `form-control-sm`, `form-select-sm`): lê o atributo `data-density` do `<html>` (escrito pelo
 * ThemeService) e só considera "compacta" com ponteiro fino — em telas de toque os alvos ficam
 * grandes (WCAG 2.5.8), como em styles/_density.scss. Sem dependência do estado do usuário,
 * para poder ser usada por qualquer componente compartilhado.
 */
@Injectable({ providedIn: 'root' })
export class DensityService {
  private readonly attribute = signal<string | null>(null);
  private readonly finePointer = signal(true);

  /** `true` quando a densidade compacta está em vigor. */
  readonly compact = computed(() => this.attribute() === 'compact' && this.finePointer());

  constructor() {
    const doc = inject(DOCUMENT);
    const root = doc.documentElement;
    const view = doc.defaultView;
    this.attribute.set(root.getAttribute('data-density'));

    const media = view?.matchMedia?.(FINE_POINTER);
    if (media) {
      this.finePointer.set(media.matches);
    }
    const onPointer = (event: MediaQueryListEvent) => this.finePointer.set(event.matches);
    // navegadores antigos e ambientes de teste podem não ter `addEventListener` no MediaQueryList
    const observable = typeof media?.addEventListener === 'function' ? media : null;
    observable?.addEventListener('change', onPointer);

    const observer =
      view && 'MutationObserver' in view
        ? new view.MutationObserver(() => this.attribute.set(root.getAttribute('data-density')))
        : null;
    observer?.observe(root, { attributes: true, attributeFilter: ['data-density'] });

    inject(DestroyRef).onDestroy(() => {
      observer?.disconnect();
      observable?.removeEventListener('change', onPointer);
    });
  }
}
