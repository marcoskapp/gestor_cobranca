import { BreakpointObserver } from '@angular/cdk/layout';
import { NgTemplateOutlet } from '@angular/common';
import { Component, DestroyRef, TemplateRef, effect, inject, input, signal, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NgbOffcanvas, NgbOffcanvasRef } from '@ng-bootstrap/ng-bootstrap';
import { map } from 'rxjs';
import { Icon } from '../icon/icon';

/** Abaixo de 768 px (ponto `md` do Bootstrap) os filtros ficam recolhidos num painel lateral. */
export const FILTER_BAR_COLLAPSED = '(max-width: 767.98px)';

/**
 * Filtros de lista ou painel (docs/tecnico/desenho/ux.md §12.3). Em telas largas mostra os filtros
 * projetados na própria linha; abaixo de 768 px recolhe-os num botão "Filtros (n)" que abre um
 * painel lateral (`NgbOffcanvas`, foco preso e Esc), para os números e a lista aparecerem na
 * primeira tela. `activeCount` é a quantidade de filtros aplicados (a tela sabe quais contam).
 *
 * ```html
 * <app-filter-bar [activeCount]="activeFilters()">
 *   <form class="app-filters" [formGroup]="form">…</form>
 * </app-filter-bar>
 * ```
 *
 * O conteúdo é projetado uma única vez e mostrado num lugar só (na linha ou no painel): o estado
 * dos campos fica no formulário da tela, que não é recriado.
 */
@Component({
  selector: 'app-filter-bar',
  imports: [NgTemplateOutlet, Icon],
  host: { class: 'app-filter-bar' },
  template: `
    @if (collapsed()) {
      <div class="app-filter-bar__toggle">
        <button type="button" class="btn btn-outline-secondary" (click)="open()" aria-haspopup="dialog" [attr.aria-expanded]="isOpen()">
          <app-icon name="funnel" />
          {{ label() }}
          @if (activeCount()) {
            <span class="badge rounded-pill text-bg-primary app-numeric">{{ activeCount() }}</span>
            <span class="visually-hidden">aplicado(s)</span>
          }
        </button>
      </div>
    } @else {
      <ng-container *ngTemplateOutlet="content" />
    }

    <ng-template #content><ng-content /></ng-template>

    <ng-template #panel let-offcanvas>
      <div class="offcanvas-header">
        <h2 class="offcanvas-title h3" id="app-filter-panel-title">{{ label() }}</h2>
        <button type="button" class="btn-close" aria-label="Fechar" (click)="offcanvas.dismiss()"></button>
      </div>
      <div class="offcanvas-body">
        <ng-container *ngTemplateOutlet="content" />
      </div>
      <div class="app-filter-panel__footer">
        <button type="button" class="btn btn-primary w-100" (click)="offcanvas.close()">Ver resultados</button>
      </div>
    </ng-template>
  `,
})
export class FilterBar {
  readonly activeCount = input(0);
  readonly label = input('Filtros');

  private readonly offcanvas = inject(NgbOffcanvas);
  private readonly panel = viewChild.required<TemplateRef<unknown>>('panel');
  private ref: NgbOffcanvasRef | null = null;
  /** Painel de filtros aberto (estado do `aria-expanded` do botão). */
  protected readonly isOpen = signal(false);

  protected readonly collapsed = toSignal(
    inject(BreakpointObserver)
      .observe(FILTER_BAR_COLLAPSED)
      .pipe(map((state) => state.matches)),
    { initialValue: false },
  );

  constructor() {
    // ao alargar a tela com o painel aberto, os filtros voltam para a linha
    effect(() => {
      if (!this.collapsed()) {
        this.ref?.close();
      }
    });
    inject(DestroyRef).onDestroy(() => this.ref?.close());
  }

  protected open(): void {
    this.ref = this.offcanvas.open(this.panel(), {
      position: 'end',
      panelClass: 'app-filter-panel',
      ariaLabelledBy: 'app-filter-panel-title',
    });
    this.isOpen.set(true);
    this.ref.result
      .finally(() => {
        this.ref = null;
        this.isOpen.set(false);
      })
      .catch(() => undefined);
  }
}
