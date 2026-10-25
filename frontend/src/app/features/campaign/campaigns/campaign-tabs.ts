import { Component, computed, inject, input } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Icon } from '../../../shared/components/icon/icon';
import { IconName } from '../../../shared/components/icon/icon-names';

/** Navegação entre as telas de uma campanha (visão geral, régua, execução e testes A/B). */
@Component({
  selector: 'app-campaign-tabs',
  imports: [Icon, RouterLink, RouterLinkActive],
  template: `
    <nav class="mb-3" aria-label="Telas da campanha" data-tour="campaign-tabs">
      <ul class="nav nav-tabs">
        @for (t of tabs(); track t.path) {
          <li class="nav-item">
            <a
              class="nav-link d-inline-flex align-items-center gap-2"
              [routerLink]="['/campanhas', campaignId(), ...t.path]"
              routerLinkActive="active"
              ariaCurrentWhenActive="page"
              [routerLinkActiveOptions]="{ exact: true }"
            >
              <app-icon [name]="t.icon" />
              {{ t.label }}
            </a>
          </li>
        }
      </ul>
    </nav>
  `,
})
export class CampaignTabs {
  readonly campaignId = input.required<string>();
  private readonly auth = inject(AuthService);

  protected readonly tabs = computed(() =>
    (
      [
        { path: [], label: 'Visão geral', icon: 'megaphone', show: true },
        { path: ['regua'], label: 'Régua', icon: 'list-ol', show: true },
        { path: ['execucao'], label: 'Execução', icon: 'graph-up', show: true },
        { path: ['testes'], label: 'Testes A/B', icon: 'eyedropper', show: this.auth.hasAnyPermission([Permission.EXPERIMENT_MANAGE, Permission.CAMPAIGN_VIEW]) },
      ] satisfies { path: string[]; label: string; icon: IconName; show: boolean }[]
    ).filter((t) => t.show),
  );
}
