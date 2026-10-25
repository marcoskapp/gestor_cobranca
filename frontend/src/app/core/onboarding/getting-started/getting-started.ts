import { Component, computed, inject } from '@angular/core';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { RouterLink } from '@angular/router';
import { Icon } from '../../../shared/components/icon/icon';
import { ChecklistEntry, OnboardingService } from '../onboarding.service';

/**
 * Checklist "Primeiros passos" do perfil do usuário. Itens automáticos (MFA, telas visitadas,
 * tour concluído) se marcam sozinhos; os demais o usuário marca quando concluir.
 */
@Component({
  selector: 'app-getting-started',
  imports: [RouterLink, NgbTooltip, Icon],
  template: `
    @if (onboarding.checklist(); as summary) {
      <section class="gs app-card" data-tour="getting-started" aria-labelledby="primeiros-passos">
        <header class="gs__header">
          <div>
            <h2 class="gs__title" id="primeiros-passos">{{ summary.profile.title }}</h2>
            <p class="gs__progress-text app-muted">{{ summary.done }} de {{ summary.total }} concluídos</p>
          </div>
          <button
            type="button"
            class="btn-close gs__close"
            aria-label="Ocultar primeiros passos"
            ngbTooltip="Ocultar (reabra pela Ajuda)"
            (click)="onboarding.setChecklistDismissed(true)"
          ></button>
        </header>
        <div
          class="progress"
          role="progressbar"
          [attr.aria-valuenow]="percent()"
          aria-valuemin="0"
          aria-valuemax="100"
          [attr.aria-label]="'Progresso dos primeiros passos: ' + percent() + '%'"
        >
          <div class="progress-bar" [style.width.%]="percent()"></div>
        </div>
        <ol class="gs__list">
          @for (entry of summary.entries; track entry.item.id) {
            <li class="gs__item" [class.gs__item--done]="entry.done">
              @if (entry.item.completion.kind === 'manual') {
                <input
                  class="form-check-input gs__check"
                  type="checkbox"
                  [checked]="entry.done"
                  (change)="toggle(entry, $event)"
                  [attr.aria-label]="'Marcar como concluído: ' + entry.item.label"
                />
              } @else {
                <app-icon
                  class="gs__state"
                  [name]="entry.done ? 'check-circle' : 'circle'"
                  [label]="entry.done ? 'Concluído' : 'Pendente'"
                />
              }
              <div class="gs__text">
                <span class="gs__label">{{ entry.item.label }}</span>
                <span class="gs__description app-muted">{{ entry.item.description }}</span>
              </div>
              @if (!entry.done) {
                @if (entry.item.tourId) {
                  <button type="button" class="btn btn-sm btn-outline-primary" (click)="start(entry)">Iniciar</button>
                } @else if (entry.item.route) {
                  <a class="btn btn-sm btn-outline-primary" [routerLink]="entry.item.route">Abrir</a>
                }
              }
            </li>
          }
        </ol>
      </section>
    }
  `,
  styles: `
    .gs__header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 8px;
    }
    .gs__title {
      margin: 0;
      font-size: var(--app-text-lg);
      font-weight: 400;
    }
    .gs__progress-text {
      margin: 2px 0 10px;
      font-size: var(--app-text-sm);
    }
    .gs__list {
      margin: 8px 0 0;
      padding: 0;
      list-style: none;
    }
    .gs__item {
      display: flex;
      align-items: center;
      gap: 12px;
      min-height: 48px;
      padding: 6px 0;
      border-bottom: var(--bs-border-width) solid var(--bs-border-color);
    }
    .gs__item:last-child {
      border-bottom: 0;
    }
    .gs__close {
      font-size: 0.75rem;
    }
    .gs__check {
      flex: none;
      width: 1.25em;
      height: 1.25em;
      margin: 0 6px;
    }
    .gs__state {
      margin: 0 4px;
      font-size: 1.25rem;
      color: var(--bs-secondary-color);
    }
    .gs__item--done .gs__state {
      color: var(--app-success-fg);
    }
    .gs__item--done .gs__label {
      text-decoration: line-through;
      color: var(--bs-secondary-color);
    }
    .gs__text {
      display: flex;
      flex: 1;
      flex-direction: column;
      min-width: 0;
    }
    .gs__label {
      font-weight: 400;
    }
    .gs__description {
      font-size: var(--app-text-sm);
    }
  `,
})
export class GettingStarted {
  protected readonly onboarding = inject(OnboardingService);
  protected readonly percent = computed(() => {
    const s = this.onboarding.checklist();
    return s && s.total > 0 ? Math.round((s.done / s.total) * 100) : 0;
  });

  protected toggle(entry: ChecklistEntry, event: Event): void {
    this.onboarding.setChecklistItem(entry.item.id, (event.target as HTMLInputElement).checked);
  }

  protected start(entry: ChecklistEntry): void {
    if (entry.item.tourId) {
      this.onboarding.startTour(entry.item.tourId);
    }
  }
}
