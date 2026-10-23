import { ConnectedPosition, Overlay, OverlayRef, PositionStrategy, ScrollDispatcher, ViewportRuler } from '@angular/cdk/overlay';
import { ComponentPortal } from '@angular/cdk/portal';
import { DOCUMENT } from '@angular/common';
import { Component, ComponentRef, Injectable, Injector, computed, inject, signal } from '@angular/core';
import { NavigationStart, Router } from '@angular/router';
import { Subscription, filter, merge } from 'rxjs';
import { UiStateStore } from '../../preferences/ui-state.store';
import { TourDefinition, TourPlacement, TourStep } from '../onboarding.models';
import { findTour } from '../content/tours';
import { TourPopover } from './tour-popover';

/** Passo em exibição. */
export interface TourView {
  tour: TourDefinition;
  step: TourStep;
  index: number;
  total: number;
  hasPrevious: boolean;
  isLast: boolean;
}

/** Tempo máximo de espera pelas âncoras de uma tela recém-carregada. */
const ANCHOR_WAIT_MS = 1200;
const ANCHOR_POLL_MS = 100;
const NARROW_VIEWPORT = 600;
const HIGHLIGHT_PADDING = 6;

const POSITIONS: Record<TourPlacement, ConnectedPosition> = {
  below: { originX: 'center', originY: 'bottom', overlayX: 'center', overlayY: 'top', offsetY: 14 },
  above: { originX: 'center', originY: 'top', overlayX: 'center', overlayY: 'bottom', offsetY: -14 },
  end: { originX: 'end', originY: 'center', overlayX: 'start', overlayY: 'center', offsetX: 14 },
  start: { originX: 'start', originY: 'center', overlayX: 'end', overlayY: 'center', offsetX: -14 },
};
const FALLBACK_ORDER: readonly TourPlacement[] = ['below', 'above', 'end', 'start'];

export function anchorSelector(anchor: string): string {
  return `[data-tour~="${anchor.replace(/["\\]/g, '')}"]`;
}

/**
 * Âncora visível na tela: renderizada, sem `visibility: hidden`/opacidade zero (ex.: menu lateral
 * fechado em telas estreitas) e ao menos parcialmente dentro da janela.
 */
function isVisible(element: Element): boolean {
  const candidate = element as Element & {
    checkVisibility?: (options?: { checkOpacity?: boolean; checkVisibilityCSS?: boolean }) => boolean;
  };
  if (typeof candidate.checkVisibility === 'function') {
    if (!candidate.checkVisibility({ checkOpacity: true, checkVisibilityCSS: true })) {
      return false;
    }
  } else if (element.getClientRects().length === 0) {
    return false;
  }
  const rect = element.getBoundingClientRect();
  const view = element.ownerDocument.defaultView;
  if (!view || (rect.width === 0 && rect.height === 0)) {
    // sem layout (ex.: testes em jsdom): considera o resultado de checkVisibility
    return true;
  }
  return rect.right > 0 && rect.bottom > 0 && rect.left < view.innerWidth && rect.top < view.innerHeight + view.scrollY;
}

/**
 * Motor de tours guiados sobre o Angular CDK Overlay (sem dependência externa).
 *
 * - Balão não modal posicionado junto à âncora (`data-tour`), com destaque que não captura
 *   cliques: o operador continua podendo usar a tela.
 * - Passos cuja âncora não existe (permissão, tela responsiva) são omitidos; a contagem
 *   "passo x de n" considera só os passos disponíveis.
 * - Esc ou "Pular" registram o tour como dispensado; "Concluir" como concluído (por versão).
 *   Navegar para outra tela encerra o tour sem registrar desfecho.
 * - O foco vai para o balão a cada passo e volta ao elemento de origem ao final.
 */
@Injectable({ providedIn: 'root' })
export class TourEngine {
  private readonly overlay = inject(Overlay);
  private readonly injector = inject(Injector);
  private readonly document = inject(DOCUMENT);
  private readonly router = inject(Router);
  private readonly store = inject(UiStateStore);
  private readonly scroll = inject(ScrollDispatcher);
  private readonly viewport = inject(ViewportRuler);

  private readonly current = signal<TourView | null>(null);
  readonly view = this.current.asReadonly();
  readonly active = computed(() => this.current() !== null);

  private steps: TourStep[] = [];
  private tour: TourDefinition | null = null;
  private popoverRef: OverlayRef | null = null;
  private highlightRef: OverlayRef | null = null;
  private popover: ComponentRef<TourPopover> | null = null;
  private anchor: HTMLElement | null = null;
  private returnFocus: HTMLElement | null = null;
  private subscriptions = new Subscription();
  private run = 0;
  private wasNarrow = false;

  /**
   * Inicia um tour. Resolve `false` se o tour não existe ou nenhum passo está disponível na tela.
   */
  async start(tourOrId: TourDefinition | string): Promise<boolean> {
    const tour = typeof tourOrId === 'string' ? findTour(tourOrId) : tourOrId;
    if (!tour) {
      return false;
    }
    this.stop();
    const run = ++this.run;
    await this.waitForAnchors(tour.steps);
    if (run !== this.run) {
      return false;
    }
    const steps = tour.steps.filter((step) => !step.anchor || this.findAnchor(step.anchor));
    if (steps.length === 0) {
      return false;
    }
    this.tour = tour;
    this.steps = steps;
    this.returnFocus = this.document.activeElement instanceof HTMLElement ? this.document.activeElement : null;
    this.createOverlays();
    this.show(0, 1);
    return true;
  }

  next(): void {
    const view = this.current();
    if (!view) {
      return;
    }
    if (view.isLast) {
      this.finish('COMPLETED');
    } else {
      this.show(view.index + 1, 1);
    }
  }

  previous(): void {
    const view = this.current();
    if (view && view.hasPrevious) {
      this.show(view.index - 1, -1);
    }
  }

  /** Encerra registrando o tour como dispensado (não será oferecido de novo nesta versão). */
  dismiss(): void {
    this.finish('DISMISSED');
  }

  /** Encerra sem registrar desfecho (ex.: navegação para outra tela). */
  stop(): void {
    this.run++;
    this.teardown(false);
  }

  // ------------------------------------------------------------------ interno

  private finish(outcome: 'COMPLETED' | 'DISMISSED'): void {
    const tour = this.tour;
    if (!tour) {
      return;
    }
    this.store.recordTour(tour.id, tour.version, outcome);
    this.teardown(true);
  }

  private show(index: number, direction: 1 | -1): void {
    // a âncora pode ter sumido desde o início (ex.: menu recolhido em tela estreita)
    let target = index;
    while (target >= 0 && target < this.steps.length) {
      const step = this.steps[target];
      if (!step.anchor || this.findAnchor(step.anchor)) {
        break;
      }
      target += direction;
    }
    if (target < 0) {
      return;
    }
    if (target >= this.steps.length) {
      this.finish('COMPLETED');
      return;
    }
    const step = this.steps[target];
    this.anchor = step.anchor ? this.findAnchor(step.anchor) : null;
    this.anchor?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    this.current.set({
      tour: this.tour as TourDefinition,
      step,
      index: target,
      total: this.steps.length,
      hasPrevious: target > 0,
      isLast: target === this.steps.length - 1,
    });
    this.popoverRef?.updatePositionStrategy(this.positionFor(step));
    this.updateHighlight();
    this.popover?.changeDetectorRef.detectChanges();
    this.popoverRef?.updatePosition();
    setTimeout(() => this.popover?.instance.focus());
  }

  private createOverlays(): void {
    // destaque primeiro, para o balão ficar acima dele
    this.highlightRef = this.overlay.create({
      panelClass: 'app-tour-highlight',
      positionStrategy: this.overlay.position().global(),
      scrollStrategy: this.overlay.scrollStrategies.noop(),
    });
    this.highlightRef.attach(new ComponentPortal(TourHighlightHost));
    this.popoverRef = this.overlay.create({
      panelClass: 'app-tour-pane',
      positionStrategy: this.overlay.position().global().centerHorizontally().centerVertically(),
      scrollStrategy: this.overlay.scrollStrategies.reposition(),
    });
    this.popover = this.popoverRef.attach(new ComponentPortal(TourPopover, null, this.injector));

    this.subscriptions = new Subscription();
    this.subscriptions.add(
      this.popoverRef
        .keydownEvents()
        .pipe(filter((event) => event.key === 'Escape'))
        .subscribe((event) => {
          event.preventDefault();
          this.dismiss();
        }),
    );
    this.subscriptions.add(
      merge(this.scroll.scrolled(20), this.viewport.change(50)).subscribe(() => {
        this.updateHighlight();
        const step = this.current()?.step;
        if (step && this.isNarrow() !== this.wasNarrow) {
          this.popoverRef?.updatePositionStrategy(this.positionFor(step));
        }
      }),
    );
    this.subscriptions.add(
      this.router.events.pipe(filter((event) => event instanceof NavigationStart)).subscribe(() => this.stop()),
    );
  }

  private isNarrow(): boolean {
    return (this.document.defaultView?.innerWidth ?? 1024) < NARROW_VIEWPORT;
  }

  private positionFor(step: TourStep): PositionStrategy {
    this.wasNarrow = this.isNarrow();
    if (this.wasNarrow) {
      // celular: balão fixo na parte de baixo, sem cobrir a âncora no topo
      return this.overlay.position().global().centerHorizontally().bottom('16px');
    }
    if (!this.anchor) {
      return this.overlay.position().global().centerHorizontally().centerVertically();
    }
    const preferred = step.placement ?? 'below';
    const order = [preferred, ...FALLBACK_ORDER.filter((p) => p !== preferred)];
    return this.overlay
      .position()
      .flexibleConnectedTo(this.anchor)
      .withPositions(order.map((p) => POSITIONS[p]))
      .withPush(true)
      .withViewportMargin(16)
      .withFlexibleDimensions(false);
  }

  private updateHighlight(): void {
    const ref = this.highlightRef;
    if (!ref) {
      return;
    }
    if (!this.anchor || !this.anchor.isConnected) {
      ref.overlayElement.style.display = 'none';
      return;
    }
    const rect = this.anchor.getBoundingClientRect();
    ref.overlayElement.style.display = '';
    ref.updatePositionStrategy(
      this.overlay
        .position()
        .global()
        .top(`${Math.max(rect.top - HIGHLIGHT_PADDING, 0)}px`)
        .left(`${Math.max(rect.left - HIGHLIGHT_PADDING, 0)}px`),
    );
    ref.updateSize({ width: rect.width + HIGHLIGHT_PADDING * 2, height: rect.height + HIGHLIGHT_PADDING * 2 });
  }

  private findAnchor(anchor: string): HTMLElement | null {
    const candidates = this.document.querySelectorAll<HTMLElement>(anchorSelector(anchor));
    for (const candidate of Array.from(candidates)) {
      if (isVisible(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  /** Aguarda a tela renderizar as âncoras (rotas preguiçosas, dados carregando). */
  private async waitForAnchors(steps: readonly TourStep[]): Promise<void> {
    const anchors = steps.map((s) => s.anchor).filter((a): a is string => !!a);
    if (anchors.length === 0) {
      return;
    }
    const deadline = Date.now() + ANCHOR_WAIT_MS;
    while (Date.now() < deadline && !anchors.every((a) => this.findAnchor(a))) {
      await new Promise((resolve) => setTimeout(resolve, ANCHOR_POLL_MS));
    }
  }

  private teardown(restoreFocus: boolean): void {
    this.subscriptions.unsubscribe();
    this.popoverRef?.dispose();
    this.highlightRef?.dispose();
    this.popoverRef = null;
    this.highlightRef = null;
    this.popover = null;
    this.anchor = null;
    this.tour = null;
    this.steps = [];
    this.current.set(null);
    if (restoreFocus && this.returnFocus?.isConnected) {
      this.returnFocus.focus();
    }
    this.returnFocus = null;
  }
}

/** Conteúdo vazio do painel de destaque (a moldura é o próprio painel do overlay). */
@Component({ selector: 'app-tour-highlight', template: '' })
class TourHighlightHost {}
