import { Component, ElementRef, inject, viewChild } from '@angular/core';
import { TourEngine } from './tour-engine.service';

let nextId = 0;

/**
 * Balão de um passo do tour. Diálogo não modal: a tela continua utilizável, o foco vai para o
 * balão a cada passo e Esc encerra (tratado pelo motor). Setas ← → navegam entre passos.
 */
@Component({
  selector: 'app-tour-popover',
  template: `
    @if (engine.view(); as v) {
      <section
        #panel
        class="tour"
        role="dialog"
        aria-modal="false"
        tabindex="-1"
        [attr.aria-labelledby]="id + '-title'"
        [attr.aria-describedby]="id + '-progress ' + id + '-body'"
        (keydown)="onKeydown($event)"
      >
        <header class="tour__header">
          <span class="tour__progress" [id]="id + '-progress'">{{ v.tour.title }} · passo {{ v.index + 1 }} de {{ v.total }}</span>
          <button type="button" class="btn-close tour__close" aria-label="Fechar tour" (click)="engine.dismiss()"></button>
        </header>
        <h2 class="tour__title" [id]="id + '-title'">{{ v.step.title }}</h2>
        <p class="tour__body" [id]="id + '-body'">{{ v.step.body }}</p>
        <div class="tour__dots" aria-hidden="true">
          @for (dot of dots(v.total); track dot) {
            <span class="tour__dot" [class.tour__dot--active]="dot === v.index"></span>
          }
        </div>
        <footer class="tour__actions">
          @if (!v.isLast) {
            <button type="button" class="btn btn-sm btn-link" (click)="engine.dismiss()">Pular tour</button>
          }
          <span class="tour__spacer"></span>
          @if (v.hasPrevious) {
            <button type="button" class="btn btn-sm btn-outline-secondary" (click)="engine.previous()">Voltar</button>
          }
          <button type="button" class="btn btn-sm btn-primary" (click)="engine.next()">{{ v.isLast ? 'Concluir' : 'Próximo' }}</button>
        </footer>
      </section>
    }
  `,
  styles: `
    .tour {
      box-sizing: border-box;
      width: 360px;
      max-width: calc(100vw - 32px);
      padding: 14px 16px 12px;
      border: var(--bs-border-width) solid var(--bs-border-color);
      border-radius: var(--bs-border-radius-lg);
      background: var(--bs-body-bg);
      color: var(--bs-body-color);
      box-shadow: var(--app-elevation-3);
      outline: none;
    }
    .tour:focus-visible {
      outline: var(--app-focus-ring);
      outline-offset: 2px;
    }
    .tour__header {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0 0 4px;
    }
    .tour__progress {
      flex: 1;
      font-size: var(--app-text-xs);
      font-weight: 500;
      color: var(--bs-secondary-color);
    }
    .tour__title {
      margin: 0 0 6px;
      font-size: var(--app-text-lg);
      font-weight: 400;
      line-height: var(--app-line-tight);
    }
    .tour__body {
      margin: 0 0 12px;
      font-size: var(--app-text-md);
      line-height: var(--app-line-normal);
    }
    .tour__dots {
      display: flex;
      gap: 6px;
      margin-bottom: 12px;
    }
    .tour__dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--bs-border-color);
    }
    .tour__dot--active {
      width: 18px;
      border-radius: 3px;
      background: var(--bs-primary);
    }
    .tour__actions {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .tour__close {
      font-size: 0.75rem;
    }
    .tour__spacer {
      flex: 1;
    }
  `,
})
export class TourPopover {
  protected readonly engine = inject(TourEngine);
  protected readonly id = `app-tour-${nextId++}`;
  private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');

  /** Move o foco para o balão (chamado pelo motor a cada passo). */
  focus(): void {
    this.panel()?.nativeElement.focus();
  }

  protected dots(total: number): number[] {
    return Array.from({ length: total }, (_, i) => i);
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowRight') {
      event.preventDefault();
      this.engine.next();
    } else if (event.key === 'ArrowLeft') {
      event.preventDefault();
      this.engine.previous();
    }
  }
}
