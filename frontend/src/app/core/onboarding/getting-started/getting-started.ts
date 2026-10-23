import { Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { ChecklistEntry, OnboardingService } from '../onboarding.service';

/**
 * Checklist "Primeiros passos" do perfil do usuário. Itens automáticos (MFA, telas visitadas,
 * tour concluído) se marcam sozinhos; os demais o usuário marca quando concluir.
 */
@Component({
  selector: 'app-getting-started',
  imports: [RouterLink, MatButtonModule, MatCheckboxModule, MatIconModule, MatProgressBarModule, MatTooltipModule],
  template: `
    @if (onboarding.checklist(); as summary) {
      <section class="gs app-card" data-tour="getting-started" aria-labelledby="primeiros-passos">
        <header class="gs__header">
          <div>
            <h2 class="gs__title" id="primeiros-passos">{{ summary.profile.title }}</h2>
            <p class="gs__progress-text app-muted">{{ summary.done }} de {{ summary.total }} concluídos</p>
          </div>
          <button
            mat-icon-button
            type="button"
            aria-label="Ocultar primeiros passos"
            matTooltip="Ocultar (reabra pela Ajuda)"
            (click)="onboarding.setChecklistDismissed(true)"
          >
            <mat-icon aria-hidden="true">close</mat-icon>
          </button>
        </header>
        <mat-progress-bar
          mode="determinate"
          [value]="percent()"
          [attr.aria-label]="'Progresso dos primeiros passos: ' + percent() + '%'"
        />
        <ol class="gs__list">
          @for (entry of summary.entries; track entry.item.id) {
            <li class="gs__item" [class.gs__item--done]="entry.done">
              @if (entry.item.completion.kind === 'manual') {
                <mat-checkbox
                  [checked]="entry.done"
                  (change)="onboarding.setChecklistItem(entry.item.id, $event.checked)"
                  [aria-label]="'Marcar como concluído: ' + entry.item.label"
                />
              } @else {
                <mat-icon class="gs__state" [attr.aria-label]="entry.done ? 'Concluído' : 'Pendente'" aria-hidden="false">
                  {{ entry.done ? 'check_circle' : 'radio_button_unchecked' }}
                </mat-icon>
              }
              <div class="gs__text">
                <span class="gs__label">{{ entry.item.label }}</span>
                <span class="gs__description app-muted">{{ entry.item.description }}</span>
              </div>
              @if (!entry.done) {
                @if (entry.item.tourId) {
                  <button mat-stroked-button type="button" (click)="start(entry)">Iniciar</button>
                } @else if (entry.item.route) {
                  <a mat-stroked-button [routerLink]="entry.item.route">Abrir</a>
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
      font-weight: 600;
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
      border-bottom: 1px solid var(--app-border);
    }
    .gs__item:last-child {
      border-bottom: 0;
    }
    .gs__state {
      margin: 0 8px;
      color: var(--mat-sys-on-surface-variant);
    }
    .gs__item--done .gs__state {
      color: var(--app-success-fg);
    }
    .gs__item--done .gs__label {
      text-decoration: line-through;
      color: var(--mat-sys-on-surface-variant);
    }
    .gs__text {
      display: flex;
      flex: 1;
      flex-direction: column;
      min-width: 0;
    }
    .gs__label {
      font-weight: 500;
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

  protected start(entry: ChecklistEntry): void {
    if (entry.item.tourId) {
      this.onboarding.startTour(entry.item.tourId);
    }
  }
}
