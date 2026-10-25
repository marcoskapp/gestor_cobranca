import { DOCUMENT } from '@angular/common';
import { Component, computed, inject } from '@angular/core';
import { OnboardingService } from '../../../core/onboarding/onboarding.service';
import { ThemeService } from '../../../core/preferences/theme.service';
import { UiStateStore } from '../../../core/preferences/ui-state.store';
import { Icon } from '../../../shared/components/icon/icon';

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
  imports: [Icon],
  template: `
    @if (visible()) {
      <aside class="di" role="status" aria-live="polite" aria-label="Sugestão de densidade">
        <app-icon class="di__icon" name="list" />
        <p class="di__text"><strong>Mais casos por tela?</strong> A densidade compacta mostra mais linhas e campos sem rolar.</p>
        <button type="button" class="btn btn-primary btn-sm" (click)="accept()">Usar compacta</button>
        <button type="button" class="btn btn-link btn-sm" (click)="decline()">Agora não</button>
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
      border: var(--bs-border-width) solid var(--bs-border-color);
      border-left: 4px solid var(--bs-primary);
      border-radius: var(--bs-border-radius);
      background: var(--bs-body-bg);
    }
    .di__icon {
      color: var(--bs-primary-text-emphasis);
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
