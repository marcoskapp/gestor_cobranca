import { Component, computed, inject } from '@angular/core';
import { Icon } from '../../../shared/components/icon/icon';
import { IconName } from '../../../shared/components/icon/icon-names';
import { OnboardingService } from '../onboarding.service';

/**
 * Cartão de convite do onboarding (canto inferior da tela). Não bloqueia nem rouba o foco;
 * é anunciado educadamente por leitores de tela e some ao trocar de tela.
 */
@Component({
  selector: 'app-onboarding-hint',
  imports: [Icon],
  template: `
    <div class="hint-region" role="region" aria-label="Dica de uso" aria-live="polite">
      @if (onboarding.hint(); as hint) {
        <div class="hint">
          <app-icon class="hint__icon" [name]="icon()" />
          <div class="hint__content">
            @switch (hint.kind) {
              @case ('welcome') {
                <h2 class="hint__title">Primeira vez por aqui?</h2>
                <p class="hint__text">{{ hint.tour.summary }}</p>
              }
              @case ('tour') {
                <h2 class="hint__title">Tour rápido: {{ hint.tour.title }}</h2>
                <p class="hint__text">{{ hint.tour.summary }} Leva menos de um minuto.</p>
              }
              @case ('changes') {
                <h2 class="hint__title">Novidades nesta tela</h2>
                <ul class="hint__list">
                  @for (item of hint.items; track item) {
                    <li>{{ item }}</li>
                  }
                </ul>
              }
            }
            <div class="hint__actions">
              @if (hint.kind === 'changes') {
                @if (hint.tour) {
                  <button type="button" class="btn btn-sm btn-primary" (click)="onboarding.acceptHint()">Ver no tour</button>
                }
                <button type="button" class="btn btn-sm btn-link" (click)="onboarding.closeHint()">Entendi</button>
              } @else {
                <button type="button" class="btn btn-sm btn-primary" (click)="onboarding.acceptHint()">Fazer o tour</button>
                <button type="button" class="btn btn-sm btn-link" (click)="onboarding.declineHint()">Agora não</button>
              }
            </div>
            <button type="button" class="hint__optout" (click)="onboarding.setHintsEnabled(false)">
              Não mostrar dicas automáticas
            </button>
          </div>
          <button type="button" class="btn-close hint__close" aria-label="Fechar dica" (click)="onboarding.closeHint()"></button>
        </div>
      }
    </div>
  `,
  styles: `
    .hint-region {
      position: fixed;
      right: 16px;
      /* acima das barras de ações fixas no rodapé (appBottomBar) */
      bottom: calc(16px + var(--app-bottom-inset, 0px));
      z-index: 900;
      max-width: calc(100vw - 32px);
    }
    .hint {
      display: flex;
      gap: 12px;
      width: 360px;
      max-width: 100%;
      box-sizing: border-box;
      padding: 14px 14px 12px 16px;
      border: var(--bs-border-width) solid var(--bs-border-color);
      border-left: 4px solid var(--bs-primary);
      border-radius: var(--bs-border-radius-lg);
      background: var(--bs-body-bg);
      box-shadow: var(--app-elevation-3);
      animation: hint-in var(--app-motion-standard) var(--app-easing);
    }
    @keyframes hint-in {
      from {
        opacity: 0;
        transform: translateY(8px);
      }
    }
    .hint__icon {
      flex: none;
      font-size: 1.25rem;
      color: var(--bs-primary-text-emphasis);
    }
    .hint__content {
      flex: 1;
      min-width: 0;
    }
    .hint__title {
      margin: 0 0 4px;
      font-size: var(--app-text-md);
      font-weight: 500;
    }
    .hint__text,
    .hint__list {
      margin: 0 0 10px;
      font-size: var(--app-text-sm);
      line-height: var(--app-line-normal);
      color: var(--bs-secondary-color);
    }
    .hint__list {
      padding-left: 18px;
    }
    .hint__actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .hint__optout {
      margin-top: 8px;
      padding: 4px 0;
      border: 0;
      background: none;
      color: var(--bs-secondary-color);
      font: inherit;
      font-size: var(--app-text-xs);
      text-decoration: underline;
      cursor: pointer;
    }
    .hint__close {
      flex: none;
      font-size: 0.75rem;
    }
    @media (max-width: 599.98px) {
      .hint-region {
        right: 8px;
        left: 8px;
        bottom: 8px;
      }
      .hint {
        width: auto;
      }
    }
  `,
})
export class OnboardingHint {
  protected readonly onboarding = inject(OnboardingService);
  protected readonly icon = computed<IconName>(() => {
    const kind = this.onboarding.hint()?.kind;
    return kind === 'changes' ? 'patch-exclamation' : kind === 'welcome' ? 'hand-index-thumb' : 'lightbulb';
  });
}
