import { DOCUMENT } from '@angular/common';
import { Component, DestroyRef, ElementRef, inject, input, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgbPopover } from '@ng-bootstrap/ng-bootstrap';
import type { Options } from '@popperjs/core';
import { IndicatorHelp } from '../../../core/onboarding/manual/indicators/indicator.models';
import { indicatorManualLink, loadIndicator } from '../../../core/onboarding/manual/indicators/indicator-index';
import { Icon } from '../icon/icon';

type LoadState = 'idle' | 'loading' | 'ready' | 'missing' | 'error';

/** Espera antes de abrir pelo ponteiro (evita piscar ao atravessar vários "i") e antes de fechar (dá tempo de chegar ao balão). */
const HOVER_OPEN_MS = 120;
const HOVER_CLOSE_MS = 250;
/** Foco recebido até este tempo depois de um pointerdown no botão veio do ponteiro, não do teclado. */
const POINTER_FOCUS_MS = 600;

/**
 * Informação contextual de um indicador: botão "i" que abre um balão (popover do ng-bootstrap) com o
 * que é o valor, como é calculado, como usá-lo na decisão e o "Saiba mais" do manual do módulo.
 * Contrato em docs/tecnico/desenho/ux.md §8.6.
 *
 * Abertura: ponteiro sobre o botão (o balão também pode receber o ponteiro), foco pelo teclado e
 * toque/clique — nada depende só de hover. O clique fixa o balão; um segundo clique, Esc ou um clique
 * fora fecham. Pelo teclado, Enter/Espaço fixa o balão e leva o foco para dentro dele (para chegar ao
 * "Saiba mais"); Esc ou Tab para fora devolvem o foco ao botão. O texto vem do catálogo de
 * indicadores, carregado sob demanda na primeira abertura.
 *
 * ```html
 * <app-info-hint indicator="analytics.recovery.rate" label="Taxa de recuperação" />
 * ```
 */
@Component({
  selector: 'app-info-hint',
  imports: [NgbPopover, RouterLink, Icon],
  // âncora dos tours ("o i de cada número"): o motor destaca o primeiro visível da tela
  host: { class: 'info-hint', 'data-tour': 'info-hint' },
  template: `
    <button
      #trigger
      type="button"
      class="info-hint__btn"
      [ngbPopover]="body"
      #popover="ngbPopover"
      triggers="manual"
      [autoClose]="false"
      container="body"
      placement="top bottom end start"
      popoverClass="app-info-popover"
      [popperOptions]="popperOptions"
      [attr.aria-label]="'Sobre: ' + label()"
      [attr.aria-expanded]="open()"
      [attr.data-indicator]="indicator()"
      (pointerdown)="pointerDown()"
      (pointerenter)="pointerEnter($event)"
      (pointerleave)="pointerLeave($event)"
      (focus)="focused()"
      (blur)="blurred($event)"
      (click)="clicked($event)"
      (keydown.escape)="escape($event)"
    >
      <app-icon name="info-circle" />
    </button>
    <ng-template #body>
      <div
        #content
        class="info-hint__body"
        tabindex="-1"
        (pointerenter)="cancelTimers()"
        (pointerleave)="pointerLeave($event)"
        (keydown)="contentKeydown($event)"
        (focusout)="contentFocusOut($event)"
      >
        @switch (state()) {
          @case ('ready') {
            @let h = help()!;
            <p class="info-hint__title">{{ h.title }}</p>
            <p class="info-hint__section"><span class="info-hint__label">O que é</span>{{ h.what }}</p>
            <p class="info-hint__section"><span class="info-hint__label">Como é calculado</span>{{ h.how }}</p>
            <p class="info-hint__section"><span class="info-hint__label">Como usar</span>{{ h.use }}</p>
            @if (link(); as l) {
              <a class="info-hint__more" [routerLink]="l.path" [fragment]="l.fragment" (click)="close()">
                Saiba mais no manual
                <app-icon name="arrow-right" />
              </a>
            }
          }
          @case ('error') {
            <p class="info-hint__title">{{ label() }}</p>
            <p class="info-hint__section" role="status">Não foi possível carregar a explicação agora. Tente de novo em instantes.</p>
          }
          @case ('missing') {
            <p class="info-hint__title">{{ label() }}</p>
            <p class="info-hint__section">Explicação ainda não disponível. Consulte o manual pela ajuda da tela.</p>
          }
          @default {
            <p class="info-hint__title">{{ label() }}</p>
            <p class="info-hint__section" role="status">Carregando a explicação…</p>
          }
        }
      </div>
    </ng-template>
  `,
  styles: `
    :host {
      display: inline-flex;
      vertical-align: middle;
    }
    /* alvo de 24 × 24 px (WCAG 2.5.8) sem aumentar a linha: margens negativas compensam o tamanho */
    .info-hint__btn {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      inline-size: 24px;
      block-size: 24px;
      margin-block: -4px;
      padding: 0;
      border: 0;
      border-radius: 50%;
      background: transparent;
      color: var(--bs-secondary-color);
      font-size: 14px;
      line-height: 1;
      cursor: help;
    }
    .info-hint__btn:hover,
    .info-hint__btn[aria-expanded='true'] {
      color: var(--bs-primary-text-emphasis);
      background: var(--bs-primary-bg-subtle);
    }
    .info-hint__btn:focus-visible {
      outline: 2px solid var(--bs-primary);
      outline-offset: 1px;
    }
    /* telas de toque: alvo maior */
    @media (pointer: coarse) {
      .info-hint__btn {
        inline-size: 32px;
        block-size: 32px;
        margin-block: -8px;
      }
    }
    .info-hint__body {
      display: flex;
      flex-direction: column;
      gap: 6px;
      outline: none;
    }
    .info-hint__title {
      margin: 0;
      font-weight: 600;
      color: var(--bs-emphasis-color);
    }
    .info-hint__section {
      margin: 0;
    }
    .info-hint__label {
      display: block;
      font-size: var(--app-text-xs, 0.75rem);
      font-weight: 600;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      color: var(--bs-secondary-color);
    }
    .info-hint__more {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      align-self: flex-start;
      min-block-size: 24px;
    }
  `,
})
export class InfoHint {
  /** Id do indicador no catálogo (`core/onboarding/manual/indicators/content/`). */
  readonly indicator = input.required<string>();
  /** Nome do indicador como aparece na tela: rótulo acessível "Sobre: <nome>". */
  readonly label = input.required<string>();

  private readonly document = inject(DOCUMENT);
  private readonly trigger = viewChild.required<ElementRef<HTMLButtonElement>>('trigger');
  private readonly popover = viewChild.required<NgbPopover>('popover');
  private readonly content = viewChild<ElementRef<HTMLElement>>('content');

  /** Margem de 12 px entre o balão e as bordas da janela (no celular o balão não encosta na tela). */
  protected readonly popperOptions = (options: Partial<Options>): Partial<Options> => ({
    ...options,
    modifiers: [...(options.modifiers ?? []), { name: 'preventOverflow', options: { padding: 12 } }, { name: 'flip', options: { padding: 12 } }],
  });

  protected readonly open = signal(false);
  protected readonly state = signal<LoadState>('idle');
  protected readonly help = signal<IndicatorHelp | undefined>(undefined);
  protected readonly link = signal<{ path: string; fragment: string } | null>(null);

  /** Aberto por clique/toque/Enter: não fecha ao tirar o ponteiro ou o foco do botão. */
  private pinned = false;
  private hovering = false;
  private lastPointerDown = 0;
  private restoring = false;
  private openTimer: ReturnType<typeof setTimeout> | null = null;
  private closeTimer: ReturnType<typeof setTimeout> | null = null;

  private readonly onDocumentKeydown = (event: KeyboardEvent) => {
    if (event.key === 'Escape' && this.open()) {
      this.escape(event);
    }
  };
  private readonly onDocumentPointerdown = (event: PointerEvent) => {
    const target = event.target as Node | null;
    if (target && (this.trigger().nativeElement.contains(target) || this.popoverElement()?.contains(target))) {
      return;
    }
    this.close();
  };

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      this.cancelTimers();
      this.unlisten();
    });
  }

  protected pointerEnter(event: PointerEvent): void {
    if (event.pointerType === 'touch') {
      return;
    }
    this.hovering = true;
    this.cancelTimers();
    if (!this.open()) {
      this.openTimer = setTimeout(() => this.show(), HOVER_OPEN_MS);
    }
  }

  protected pointerLeave(event: PointerEvent): void {
    if (event.pointerType === 'touch') {
      return;
    }
    this.hovering = false;
    this.cancelTimers();
    if (this.open() && !this.pinned && !this.focusInside()) {
      this.closeTimer = setTimeout(() => this.close(), HOVER_CLOSE_MS);
    }
  }

  protected pointerDown(): void {
    this.lastPointerDown = Date.now();
  }

  protected focused(): void {
    // só o foco do teclado abre: o foco que vem de um toque ou clique (logo após o pointerdown) é
    // tratado pelo clique, para o toque não abrir e fechar o balão em seguida
    if (this.restoring) {
      this.restoring = false;
      return;
    }
    if (Date.now() - this.lastPointerDown > POINTER_FOCUS_MS) {
      this.show();
    }
  }

  /** Devolve o foco ao botão depois de fechar (Esc ou Tab para fora) sem reabrir o balão. */
  private restoreFocus(): void {
    this.restoring = true;
    this.trigger().nativeElement.focus();
    this.restoring = false;
  }

  protected blurred(event: FocusEvent): void {
    const next = event.relatedTarget as Node | null;
    if (next && this.popoverElement()?.contains(next)) {
      return;
    }
    if (!this.hovering) {
      this.close();
    }
  }

  protected clicked(event: MouseEvent): void {
    this.cancelTimers();
    if (this.open() && this.pinned) {
      this.close();
      return;
    }
    this.pinned = true;
    this.show();
    // Enter/Espaço (clique sem ponteiro): o foco entra no balão para alcançar o "Saiba mais"
    if (event.detail === 0) {
      setTimeout(() => this.content()?.nativeElement.focus());
    }
  }

  protected escape(event: Event): void {
    if (!this.open()) {
      return;
    }
    event.stopPropagation();
    const restore = this.focusInside();
    this.close();
    if (restore) {
      this.restoreFocus();
    }
  }

  protected contentKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      event.preventDefault();
      this.escape(event);
      return;
    }
    if (event.key !== 'Tab') {
      return;
    }
    // balão anexado ao body: sair dele com Tab devolve o foco ao botão, na ordem natural da tela
    const focusables = this.focusables();
    const first = focusables[0];
    const last = focusables.at(-1);
    const active = this.document.activeElement;
    const leaving = event.shiftKey ? active === first || active === this.content()?.nativeElement : active === last || !last;
    if (leaving) {
      event.preventDefault();
      this.close();
      this.restoreFocus();
    }
  }

  protected contentFocusOut(event: FocusEvent): void {
    const next = event.relatedTarget as Node | null;
    if (next && (this.popoverElement()?.contains(next) || next === this.trigger().nativeElement)) {
      return;
    }
    if (next) {
      this.close();
    }
  }

  protected cancelTimers(): void {
    if (this.openTimer) {
      clearTimeout(this.openTimer);
      this.openTimer = null;
    }
    if (this.closeTimer) {
      clearTimeout(this.closeTimer);
      this.closeTimer = null;
    }
  }

  /** Fecha o balão (também usado pelo "Saiba mais"). */
  close(): void {
    this.cancelTimers();
    this.pinned = false;
    if (!this.open()) {
      return;
    }
    this.open.set(false);
    this.popover().close(false);
    this.unlisten();
  }

  private show(): void {
    this.cancelTimers();
    if (this.open()) {
      return;
    }
    this.open.set(true);
    this.popover().open();
    this.document.addEventListener('keydown', this.onDocumentKeydown, true);
    this.document.addEventListener('pointerdown', this.onDocumentPointerdown, true);
    void this.load();
  }

  private async load(): Promise<void> {
    if (this.state() === 'ready' || this.state() === 'loading' || this.state() === 'missing') {
      return;
    }
    this.state.set('loading');
    try {
      const help = await loadIndicator(this.indicator());
      this.help.set(help);
      this.link.set(help ? indicatorManualLink(help) : null);
      this.state.set(help ? 'ready' : 'missing');
    } catch {
      this.state.set('error');
    }
  }

  private unlisten(): void {
    this.document.removeEventListener('keydown', this.onDocumentKeydown, true);
    this.document.removeEventListener('pointerdown', this.onDocumentPointerdown, true);
  }

  private popoverElement(): HTMLElement | null {
    const id = this.trigger().nativeElement.getAttribute('aria-describedby');
    return id ? this.document.getElementById(id) : null;
  }

  private focusInside(): boolean {
    const active = this.document.activeElement;
    return !!active && !!this.popoverElement()?.contains(active);
  }

  private focusables(): HTMLElement[] {
    const root = this.content()?.nativeElement;
    return root ? Array.from(root.querySelectorAll<HTMLElement>('a[href], button:not([disabled])')) : [];
  }
}
