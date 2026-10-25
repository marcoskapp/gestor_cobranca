import { Component, computed, inject, signal } from '@angular/core';
import { NgbNavModule } from '@ng-bootstrap/ng-bootstrap';
import { AuthService } from '../../../core/auth/auth.service';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { ScopeNotice } from '../components/scope-notice';
import { ExpectedRecoveryPanel } from './expected-recovery-panel';
import { StabilityPanel } from './stability-panel';

/**
 * Monitoramento do score em duas abas: estabilidade e calibração do campeão (exige todos os
 * credores) e recuperado × esperado (filtrado pelo escopo do usuário). Sem acesso a todos os
 * credores, a página abre direto no recuperado × esperado.
 */
@Component({
  selector: 'app-monitoring-page',
  imports: [Icon, NgbNavModule, PageHeader, ScopeNotice, ExpectedRecoveryPanel, StabilityPanel],
  template: `
    <app-page-header title="Monitoramento do score" subtitle="Deriva da população, calibração, desempenho real das safras e recuperado × esperado." />
    <ul ngbNav #nav="ngbNav" class="nav-tabs mb-3" [activeId]="tab()" (activeIdChange)="tab.set($event)" data-tour="scoring-monitoring-tabs">
      <li [ngbNavItem]="'stability'">
        <button ngbNavLink type="button" class="d-inline-flex align-items-center gap-2"><app-icon name="activity" />Estabilidade e calibração</button>
        <ng-template ngbNavContent>
          @if (allCreditors()) {
            <app-stability-panel />
          } @else {
            <app-scoring-scope-notice title="A estabilidade do modelo é acompanhada por quem acessa todos os credores." />
          }
        </ng-template>
      </li>
      <li [ngbNavItem]="'recovery'">
        <button ngbNavLink type="button" class="d-inline-flex align-items-center gap-2"><app-icon name="cash-stack" />Recuperado × esperado</button>
        <ng-template ngbNavContent>
          <app-expected-recovery-panel />
        </ng-template>
      </li>
    </ul>
    <div [ngbNavOutlet]="nav"></div>
  `,
})
export class MonitoringPage {
  private readonly auth = inject(AuthService);
  protected readonly allCreditors = computed(() => this.auth.user()?.allCreditors ?? false);
  protected readonly tab = signal<'stability' | 'recovery'>(this.auth.user()?.allCreditors ? 'stability' : 'recovery');
}
