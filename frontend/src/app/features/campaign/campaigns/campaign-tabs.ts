import { Component, computed, inject, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTabsModule } from '@angular/material/tabs';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';

/** Navegação entre as telas de uma campanha (visão geral, régua, execução e testes A/B). */
@Component({
  selector: 'app-campaign-tabs',
  imports: [MatTabsModule, MatIconModule, RouterLink, RouterLinkActive],
  template: `
    <nav mat-tab-nav-bar [tabPanel]="panel" class="ct" aria-label="Telas da campanha" data-tour="campaign-tabs">
      @for (t of tabs(); track t.path) {
        <a mat-tab-link [routerLink]="['/campanhas', campaignId(), ...t.path]" routerLinkActive #rla="routerLinkActive" [routerLinkActiveOptions]="{ exact: true }" [active]="rla.isActive">
          <mat-icon aria-hidden="true" class="ct-icon">{{ t.icon }}</mat-icon>
          {{ t.label }}
        </a>
      }
    </nav>
    <mat-tab-nav-panel #panel />
  `,
  styles: `
    .ct {
      margin: -8px 0 var(--app-gap);
    }
    .ct-icon {
      width: 18px;
      height: 18px;
      margin-right: 6px;
      font-size: 18px;
    }
  `,
})
export class CampaignTabs {
  readonly campaignId = input.required<string>();
  private readonly auth = inject(AuthService);

  protected readonly tabs = computed(() =>
    [
      { path: [], label: 'Visão geral', icon: 'campaign', show: true },
      { path: ['regua'], label: 'Régua', icon: 'linear_scale', show: true },
      { path: ['execucao'], label: 'Execução', icon: 'monitoring', show: true },
      { path: ['testes'], label: 'Testes A/B', icon: 'science', show: this.auth.hasAnyPermission([Permission.EXPERIMENT_MANAGE, Permission.CAMPAIGN_VIEW]) },
    ].filter((t) => t.show),
  );
}
