import { Component, inject } from '@angular/core';
import { Icon } from '../../../shared/components/icon/icon';
import { OnboardingService } from '../onboarding.service';

/**
 * Convite de boas-vindas do onboarding, dentro da página inicial (nunca por cima do conteúdo):
 * uma faixa estreita e dispensável. Só existe quando o checklist "Primeiros passos" não oferece o
 * mesmo tour, e aparece no máximo uma vez (ux.md §8.3).
 */
@Component({
  selector: 'app-onboarding-hint',
  imports: [Icon],
  template: `
    <div role="region" aria-label="Dica de uso" aria-live="polite">
      @if (onboarding.welcomeInvite(); as hint) {
        <div class="hint">
          <app-icon class="hint__icon" name="hand-index-thumb" />
          <p class="hint__text">
            <strong>Primeira vez por aqui?</strong>
            {{ hint.tour.summary }}
          </p>
          <div class="hint__actions">
            <button type="button" class="btn btn-sm btn-primary" (click)="onboarding.acceptHint()">Fazer o tour</button>
            <button type="button" class="btn btn-sm btn-link" (click)="onboarding.declineHint()">Agora não</button>
          </div>
        </div>
      }
    </div>
  `,
  styles: `
    .hint {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 12px;
      margin-bottom: 12px;
      padding: 8px 12px;
      border: var(--bs-border-width) solid var(--bs-border-color);
      border-left: 4px solid var(--bs-primary);
      border-radius: var(--bs-border-radius);
      background: var(--bs-body-bg);
    }
    .hint__icon {
      flex: none;
      font-size: 1.125rem;
      color: var(--bs-primary-text-emphasis);
    }
    .hint__text {
      flex: 1 1 260px;
      margin: 0;
      font-size: var(--app-text-sm);
    }
    .hint__actions {
      display: flex;
      gap: 4px;
    }
  `,
})
export class OnboardingHint {
  protected readonly onboarding = inject(OnboardingService);
}
