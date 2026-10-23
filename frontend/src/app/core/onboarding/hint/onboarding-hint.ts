import { Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { OnboardingService } from '../onboarding.service';

/**
 * Cartão de convite do onboarding (canto inferior da tela). Não bloqueia nem rouba o foco;
 * é anunciado educadamente por leitores de tela e some ao trocar de tela.
 */
@Component({
  selector: 'app-onboarding-hint',
  imports: [MatButtonModule, MatIconModule],
  template: `
    <div class="hint-region" role="region" aria-label="Dica de uso" aria-live="polite">
      @if (onboarding.hint(); as hint) {
        <div class="hint">
          <mat-icon class="hint__icon" aria-hidden="true">{{ icon() }}</mat-icon>
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
                  <button mat-flat-button type="button" (click)="onboarding.acceptHint()">Ver no tour</button>
                }
                <button mat-button type="button" (click)="onboarding.closeHint()">Entendi</button>
              } @else {
                <button mat-flat-button type="button" (click)="onboarding.acceptHint()">Fazer o tour</button>
                <button mat-button type="button" (click)="onboarding.declineHint()">Agora não</button>
              }
            </div>
            <button type="button" class="hint__optout" (click)="onboarding.setHintsEnabled(false)">
              Não mostrar dicas automáticas
            </button>
          </div>
          <button mat-icon-button type="button" class="hint__close" aria-label="Fechar dica" (click)="onboarding.closeHint()">
            <mat-icon aria-hidden="true">close</mat-icon>
          </button>
        </div>
      }
    </div>
  `,
  styles: `
    .hint-region {
      position: fixed;
      right: 16px;
      bottom: 16px;
      z-index: 900;
      max-width: calc(100vw - 32px);
    }
    .hint {
      display: flex;
      gap: 12px;
      width: 360px;
      max-width: 100%;
      box-sizing: border-box;
      padding: 14px 8px 12px 16px;
      border: 1px solid var(--app-border);
      border-left: 4px solid var(--mat-sys-primary);
      border-radius: var(--app-radius-lg);
      background: var(--mat-sys-surface-container-high);
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
      color: var(--mat-sys-primary);
    }
    .hint__content {
      flex: 1;
      min-width: 0;
    }
    .hint__title {
      margin: 0 0 4px;
      font-size: var(--app-text-md);
      font-weight: 600;
    }
    .hint__text,
    .hint__list {
      margin: 0 0 10px;
      font-size: var(--app-text-sm);
      line-height: var(--app-line-normal);
      color: var(--mat-sys-on-surface-variant);
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
      color: var(--mat-sys-on-surface-variant);
      font: inherit;
      font-size: var(--app-text-xs);
      text-decoration: underline;
      cursor: pointer;
    }
    .hint__close {
      flex: none;
      margin-top: -8px;
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
  protected readonly icon = computed(() => {
    const kind = this.onboarding.hint()?.kind;
    return kind === 'changes' ? 'new_releases' : kind === 'welcome' ? 'waving_hand' : 'tips_and_updates';
  });
}
