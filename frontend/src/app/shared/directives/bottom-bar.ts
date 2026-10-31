import { DOCUMENT } from '@angular/common';
import { DestroyRef, Directive, ElementRef, Injectable, NgZone, afterNextRender, inject } from '@angular/core';

/** Variável CSS (no `<html>`) com a altura ocupada no rodapé da janela pelas barras de ações fixas. */
export const BOTTOM_INSET_VAR = '--app-bottom-inset';
/** Folga entre a barra e o que sobe acima dela (ex.: notificações). */
const GAP = 8;

/**
 * Espaço do rodapé da janela ocupado por barras de ações fixas (`position: sticky/fixed; bottom`). Cada barra se
 * registra pela diretiva {@link BottomBar}; o serviço mede quanto da parte de baixo da janela elas cobrem e publica em
 * {@link BOTTOM_INSET_VAR}, que os elementos flutuantes do rodapé (a pilha de notificações) usam para subir acima delas.
 * Recalcula ao rolar (a barra "gruda" ao rolar), ao redimensionar a janela ou a barra, e ao registrar/retirar barras.
 */
@Injectable({ providedIn: 'root' })
export class BottomInset {
  private readonly document = inject(DOCUMENT);
  private readonly zone = inject(NgZone);
  private readonly bars = new Set<HTMLElement>();
  private readonly resize = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => this.schedule());
  private frame: number | null = null;
  private listening = false;
  private readonly onChange = () => this.schedule();

  register(bar: HTMLElement): void {
    this.bars.add(bar);
    this.resize?.observe(bar);
    this.listen(true);
    this.update();
  }

  unregister(bar: HTMLElement): void {
    this.bars.delete(bar);
    this.resize?.unobserve(bar);
    if (!this.bars.size) {
      this.listen(false);
    }
    this.update();
  }

  /** Altura coberta no rodapé da janela (px), já com a folga; 0 sem barra visível. */
  measure(): number {
    const view = this.document.defaultView;
    const height = view?.innerHeight ?? 0;
    let inset = 0;
    for (const bar of this.bars) {
      const rect = bar.getBoundingClientRect();
      if (rect.height === 0 || rect.top >= height || rect.bottom <= 0) {
        continue;
      }
      inset = Math.max(inset, height - rect.top + GAP);
    }
    return Math.round(inset);
  }

  private update(): void {
    const inset = this.measure();
    const root = this.document.documentElement;
    if (inset > 0) {
      root.style.setProperty(BOTTOM_INSET_VAR, `${inset}px`);
    } else {
      root.style.removeProperty(BOTTOM_INSET_VAR);
    }
  }

  private schedule(): void {
    const view = this.document.defaultView;
    if (!view || this.frame !== null) {
      return;
    }
    this.frame = view.requestAnimationFrame(() => {
      this.frame = null;
      this.update();
    });
  }

  private listen(on: boolean): void {
    const view = this.document.defaultView;
    if (!view || on === this.listening) {
      return;
    }
    this.listening = on;
    // a rolagem acontece em contêineres internos (ex.: o conteúdo do shell): captura no documento
    this.zone.runOutsideAngular(() => {
      if (on) {
        this.document.addEventListener('scroll', this.onChange, { capture: true, passive: true });
        view.addEventListener('resize', this.onChange, { passive: true });
      } else {
        this.document.removeEventListener('scroll', this.onChange, { capture: true });
        view.removeEventListener('resize', this.onChange);
      }
    });
  }
}

/**
 * Marca uma barra de ações fixa no rodapé (`<div appBottomBar class="…sticky…">`): enquanto ela estiver na tela, a
 * pilha de notificações sobe acima dela, sem cobrir os botões. Só a marcação; a posição continua no CSS da barra.
 */
@Directive({ selector: '[appBottomBar]' })
export class BottomBar {
  constructor() {
    const element = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
    const inset = inject(BottomInset);
    afterNextRender(() => inset.register(element));
    inject(DestroyRef).onDestroy(() => inset.unregister(element));
  }
}
