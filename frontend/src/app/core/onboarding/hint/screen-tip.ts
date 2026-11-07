import { Component, inject } from '@angular/core';
import { NgbDropdownItem } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../../shared/components/icon/icon';
import { ManualTextPipe } from '../manual/manual-text.pipe';
import { OnboardingService } from '../onboarding.service';

/**
 * Tour novo ou novidades da tela atual, no topo do menu de ajuda (o botão de ajuda ganha um ponto).
 * Substitui o cartão flutuante que cobria colunas e setas das telas de trabalho (T8 da revisão de
 * produto de 2026-11). Some ao trocar de tela; aparece no máximo uma vez por tela e versão.
 */
@Component({
  selector: 'app-screen-tip',
  imports: [NgbDropdownItem, Icon, ManualTextPipe],
  template: `
    @if (onboarding.screenTip(); as tip) {
      @switch (tip.kind) {
        @case ('tour') {
          <button ngbDropdownItem type="button" role="menuitem" class="tip" (click)="onboarding.acceptHint()">
            <app-icon name="lightbulb" />
            <span class="tip__text">
              <strong>Tour rápido: {{ tip.tour.title }}</strong>
              <span class="tip__sub">{{ tip.tour.summary }}</span>
            </span>
            <span class="badge rounded-pill bg-warning-subtle text-warning-emphasis ms-auto">Novo</span>
          </button>
        }
        @case ('changes') {
          <div class="dropdown-item-text tip">
            <app-icon name="patch-exclamation" />
            <span class="tip__text">
              <strong>Novidades nesta tela</strong>
              <ul class="tip__list">
                @for (item of tip.items.slice(0, 4); track item) {
                  <li>{{ item | manualText }}</li>
                }
              </ul>
            </span>
          </div>
          @if (tip.tour) {
            <button ngbDropdownItem type="button" role="menuitem" (click)="onboarding.acceptHint()">
              <app-icon name="signpost" /> Ver as novidades no tour
            </button>
          }
        }
      }
      <div class="dropdown-divider" role="separator"></div>
    }
  `,
  styles: `
    .tip {
      display: flex;
      align-items: flex-start;
      gap: 8px;
      max-width: 340px;
      white-space: normal;
    }
    .tip__text {
      display: flex;
      flex-direction: column;
      gap: 2px;
      min-width: 0;
    }
    .tip__sub,
    .tip__list {
      font-size: var(--app-text-xs);
      color: var(--bs-secondary-color);
    }
    .tip__list {
      margin: 2px 0 0;
      padding-left: 16px;
    }
  `,
})
export class ScreenTip {
  protected readonly onboarding = inject(OnboardingService);
}
