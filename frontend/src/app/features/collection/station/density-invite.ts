import { DOCUMENT } from '@angular/common';
import { Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { OnboardingService } from '../../../core/onboarding/onboarding.service';
import { ThemeService } from '../../../core/preferences/theme.service';
import { UiStateStore } from '../../../core/preferences/ui-state.store';

/** Registro do convite no estado de interface do servidor (mesmo mecanismo dos tours). */
export const DENSITY_INVITE_ID = 'collection.compact-density';
export const DENSITY_INVITE_VERSION = 1;

/**
 * Convite para a densidade compacta no primeiro acesso à estação (ux.md §10): nunca imposto,
 * aparece uma vez, só com ponteiro fino (em telas de toque a densidade compacta é ignorada) e
 * depois que o convite de tour da tela foi tratado. A escolha fica registrada na conta.
 */
@Component({
  selector: 'app-density-invite',
  imports: [MatButtonModule, MatIconModule],
  template: `
    @if (visible()) {
      <aside class="di" role="status" aria-live="polite" aria-label="Sugestão de densidade">
        <mat-icon aria-hidden="true">density_small</mat-icon>
        <p class="di__text"><strong>Mais casos por tela?</strong> A densidade compacta mostra mais linhas e campos sem rolar.</p>
        <button mat-flat-button type="button" (click)="accept()">Usar compacta</button>
        <button mat-button type="button" (click)="decline()">Agora não</button>
      </aside>
    }
  `,
  styles: `
    .di {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 12px;
      margin-bottom: 8px;
      padding: 6px 12px;
      border: 1px solid var(--app-border);
      border-left: 4px solid var(--mat-sys-primary);
      border-radius: var(--app-radius-md);
      background: var(--app-surface);
    }
    .di .mat-icon {
      color: var(--mat-sys-primary);
    }
    .di__text {
      flex: 1 1 280px;
      margin: 0;
      font-size: var(--app-text-sm);
    }
  `,
})
export class DensityInvite {
  private readonly store = inject(UiStateStore);
  private readonly theme = inject(ThemeService);
  private readonly onboarding = inject(OnboardingService);
  private readonly finePointer = inject(DOCUMENT).defaultView?.matchMedia?.('(pointer: fine)').matches ?? false;

  protected readonly visible = computed(() => {
    const state = this.store.state();
    return (
      this.finePointer &&
      this.store.ready() &&
      state.hintsEnabled &&
      state.density === 'COMFORTABLE' &&
      !state.tours[DENSITY_INVITE_ID] &&
      this.onboarding.hint() === null
    );
  });

  protected accept(): void {
    this.theme.setDensity('COMPACT');
    this.store.recordTour(DENSITY_INVITE_ID, DENSITY_INVITE_VERSION, 'COMPLETED');
  }

  protected decline(): void {
    this.store.recordTour(DENSITY_INVITE_ID, DENSITY_INVITE_VERSION, 'DISMISSED');
  }
}
