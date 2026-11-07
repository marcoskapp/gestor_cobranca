import { Component, computed, inject, input } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';

/** Navegação entre as telas de uma campanha (visão geral, régua, execução e testes A/B). */
@Component({
  selector: 'app-campaign-tabs',
  imports: [RouterLink, RouterLinkActive],
  template: `
    <nav aria-label="Telas da campanha" data-tour="campaign-tabs">
      <ul class="nav nav-underline app-tabs">
        @for (t of tabs(); track t.path) {
          <li class="nav-item">
            <a
              class="nav-link"
              [routerLink]="['/campanhas', campaignId(), ...t.path]"
              routerLinkActive="active"
              ariaCurrentWhenActive="page"
              [routerLinkActiveOptions]="{ exact: true }"
            >
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
        { path: [], label: 'Visão geral', show: true },
        { path: ['regua'], label: 'Régua', show: true },
        { path: ['execucao'], label: 'Execução', show: true },
        { path: ['testes'], label: 'Testes A/B', show: this.auth.hasAnyPermission([Permission.EXPERIMENT_MANAGE, Permission.CAMPAIGN_VIEW]) },
      ] satisfies { path: string[]; label: string; show: boolean }[]
    ).filter((t) => t.show),
  );
}
