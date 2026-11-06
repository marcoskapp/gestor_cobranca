import { Component, DestroyRef, inject, input, viewChild } from '@angular/core';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';

let nextId = 0;

/** Espera antes de fechar ao tirar o ponteiro: dá tempo de levar o ponteiro até o balão (WCAG 1.4.13). */
const HOVER_CLOSE_MS = 250;

/**
 * Ação visível, desabilitada e com a explicação de por que não está disponível. É a exceção à regra
 * "menus e telas sem permissão ficam ocultos" (`CLAUDE.md` e `docs/tecnico/desenho/ux.md`, decisão 9402):
 * vale só para ação irreversível ou sensível que a pessoa precisa saber que existe (ex.: "Executar" da
 * retenção, "Baixar dados do titular"), decidida pelo responsável.
 *
 * O botão nunca dispara ação nem chamada ao servidor: usa `aria-disabled` (continua focável, para o
 * teclado e o leitor de tela chegarem à explicação) e não emite eventos para fora. A explicação abre
 * num balão ao passar o mouse, ao receber o foco do teclado e ao tocar ou clicar; o balão pode receber o
 * ponteiro sem fechar (fecha 250 ms depois de o ponteiro sair do botão e do balão); Esc ou um toque fora
 * fecham. O leitor de tela lê a explicação como descrição do botão.
 *
 * ```html
 * <app-disabled-action buttonClass="btn btn-danger" reason="Para executar é preciso ter a permissão…">
 *   <app-icon name="trash3" /> Executar
 * </app-disabled-action>
 * ```
 */
@Component({
  selector: 'app-disabled-action',
  imports: [NgbTooltip],
  host: { class: 'app-disabled-action' },
  template: `
    <span
      class="app-disabled-action__wrap"
      [ngbTooltip]="balloon"
      #tip="ngbTooltip"
      triggers="manual"
      container="body"
      placement="top bottom"
      (pointerenter)="pointerEnter($event)"
      (pointerleave)="pointerLeave($event)"
    >
      <button
        type="button"
        [class]="buttonClass()"
        class="app-disabled-action__btn"
        aria-disabled="true"
        [attr.aria-describedby]="reasonId"
        (focus)="show()"
        (blur)="hide()"
        (click)="clicked($event)"
        (keydown.escape)="hide()"
      >
        <ng-content />
      </button>
    </span>
    <span class="visually-hidden" [id]="reasonId">{{ reason() }}</span>
    <ng-template #balloon>
      <span class="app-disabled-action__balloon" (pointerenter)="cancelClose()" (pointerleave)="pointerLeave($event)">{{ reason() }}</span>
    </ng-template>
  `,
  styles: `
    :host,
    .app-disabled-action__wrap {
      display: inline-block;
    }
    /* aparência de botão desabilitado sem o pointer-events: none do Bootstrap (o balão precisa do ponteiro) */
    .app-disabled-action__btn {
      opacity: var(--bs-btn-disabled-opacity, 0.65);
      cursor: not-allowed;
    }
    /* sem a cor de hover/ativo/foco de botão habilitado; o anel de foco (box-shadow/outline) continua */
    .app-disabled-action__btn:hover,
    .app-disabled-action__btn:active,
    .app-disabled-action__btn:focus-visible {
      color: var(--bs-btn-color);
      background-color: var(--bs-btn-bg);
      border-color: var(--bs-btn-border-color);
    }
    .app-disabled-action__balloon {
      display: block;
    }
  `,
})
export class DisabledAction {
  /** Explicação de por que a ação não está disponível (o que falta para usá-la). */
  readonly reason = input.required<string>();
  /** Classes do botão, as mesmas da ação habilitada (ex.: `btn btn-danger`). */
  readonly buttonClass = input('btn btn-outline-secondary');

  protected readonly reasonId = `app-disabled-action-${++nextId}`;
  private readonly tip = viewChild.required<NgbTooltip>('tip');
  private closeTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.cancelClose());
  }

  protected pointerEnter(event: PointerEvent): void {
    if (event.pointerType !== 'touch') {
      this.show();
    }
  }

  /** Ponteiro saiu do botão ou do balão: fecha depois da espera, a menos que volte a um dos dois. */
  protected pointerLeave(event: PointerEvent): void {
    if (event.pointerType === 'touch') {
      return;
    }
    this.cancelClose();
    this.closeTimer = setTimeout(() => this.hide(), HOVER_CLOSE_MS);
  }

  protected cancelClose(): void {
    if (this.closeTimer) {
      clearTimeout(this.closeTimer);
      this.closeTimer = null;
    }
  }

  /** Toque ou clique: nunca executa nada, só mostra a explicação. */
  protected clicked(event: Event): void {
    event.preventDefault();
    event.stopPropagation();
    this.show();
  }

  protected show(): void {
    this.cancelClose();
    this.tip().open();
  }

  protected hide(): void {
    this.cancelClose();
    this.tip().close();
  }
}
