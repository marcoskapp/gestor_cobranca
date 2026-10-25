import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { AssistantPoliciesApi } from '../data/assistant.api';
import { PolicyView } from '../data/assistant.models';
import { scopeOf } from '../data/assistant.rules';
import { DisablePortfolioData, DisablePortfolioDialog, NewPolicyDialog } from './policy-dialogs';
import { Icon } from '../../../shared/components/icon/icon';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { DialogService } from '../../../shared/dialog/dialog.service';

const SCOPE_ORDER = { TENANT: 0, CREDITOR: 1, PORTFOLIO: 2 } as const;
const STATUS_ORDER: Record<string, number> = { PUBLISHED: 0, DRAFT: 1, RETIRED: 2 };

/**
 * Políticas do assistente por escopo (assessoria → credor → carteira; a publicada mais específica
 * vence), com a versão vigente e os rascunhos; desligamento imediato por carteira.
 */
@Component({
  selector: 'app-assistant-policy-list',
  imports: [NgbTooltip, Icon, RouterLink, AppDatePipe, CellDef, DataTable, PageHeader, StatusChip],
  template: `
    <app-page-header title="Políticas do assistente" subtitle="Modo, canais, horários, alçada, temas, gatilhos de transferência e limites do assistente de IA, por assessoria, credor ou carteira.">
      @if (canManage()) {
        <button class="btn btn-outline-primary" type="button" (click)="disable()" data-tour="assistant-disable">
          <app-icon name="power" />
          Desligar numa carteira
        </button>
        <button class="btn btn-primary" type="button" (click)="create()" data-tour="assistant-new">
          <app-icon name="plus-lg" />
          Nova política
        </button>
      }
    </app-page-header>
    <div class="alert alert-info d-flex align-items-start gap-2" role="note">
      <app-icon name="bank2" />
      <span><strong>A IA conversa, o motor decide:</strong> condições vêm sempre da política de cobrança e da alçada; todo contato passa pelo porteiro. A versão publicada mais específica vale na próxima mensagem, inclusive em conversas em andamento.</span>
    </div>
    <app-data-table
      caption="Políticas do assistente"
      [rows]="rows()"
      [columns]="columns"
      [loading]="list.isLoading()"
      [failed]="list.status() === 'error'"
      (retry)="list.reload()"
      emptyIcon="robot"
      emptyMessage="Nenhuma política do assistente."
      emptyHint="Sem política publicada, o assistente não atua: as conversas vão para os humanos."
    >
      <ng-template appCell="scope" [appCellOf]="rows()" let-p>
        {{ scopeText(p) }}
        <div class="app-muted al-small">{{ p.code }}</div>
      </ng-template>
      <ng-template appCell="name" [appCellOf]="rows()" let-p>
        <strong>{{ p.name }}</strong> · v{{ p.policyVersion }}
        <div class="app-muted al-small">prompt {{ p.promptCode }} v{{ p.promptVersion }}</div>
      </ng-template>
      <ng-template appCell="mode" [appCellOf]="rows()" let-p><app-status-chip domain="assistantMode" [status]="p.mode" /></ng-template>
      <ng-template appCell="status" [appCellOf]="rows()" let-p>
        <app-status-chip domain="assistantPolicy" [status]="p.status" />
        <div class="app-muted al-small">
          @if (p.publishedAt) {
            {{ p.publishedAt | appDate }} por {{ users.name(p.publishedBy) }}
          } @else {
            criada {{ p.createdAt | appDate }}
          }
        </div>
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-p>
        <a class="btn btn-sm btn-outline-secondary border-0" [routerLink]="['/assistente/politicas', p.id]" [attr.aria-label]="'Abrir política ' + p.name" ngbTooltip="Abrir"><app-icon name="chevron-right" /></a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .al-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class AssistantPolicyList {
  private readonly api = inject(AssistantPoliciesApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.ASSISTANT_MANAGE));

  protected readonly list = rxResource({ stream: () => this.api.list() });
  private readonly values = valueOr(this.list, []);
  protected readonly rows = computed(() =>
    [...this.values()].sort(
      (a, b) => SCOPE_ORDER[scopeOf(a)] - SCOPE_ORDER[scopeOf(b)] || a.code.localeCompare(b.code) || (STATUS_ORDER[a.status] ?? 9) - (STATUS_ORDER[b.status] ?? 9) || b.policyVersion - a.policyVersion,
    ),
  );
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.rows().map((p) => p.creditorId));
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => this.rows().map((p) => p.portfolioId));
  protected readonly users = nameMap(inject(UserNames), () => this.rows().map((p) => p.publishedBy), { batch: USER_NAMES_BATCH });

  protected readonly columns: TableColumn[] = [
    { key: 'scope', header: 'Escopo' },
    { key: 'name', header: 'Política' },
    { key: 'mode', header: 'Modo' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected scopeText(p: PolicyView): string {
    switch (scopeOf(p)) {
      case 'TENANT':
        return 'Toda a assessoria';
      case 'CREDITOR':
        return this.creditors.name(p.creditorId);
      default:
        return `${this.creditors.name(p.creditorId)} › ${this.portfolios.name(p.portfolioId)}`;
    }
  }

  protected create(): void {
    this.dialog
      .open<NewPolicyDialog, void, PolicyView>(NewPolicyDialog)
      .afterClosed()
      .subscribe((p) => p && void this.router.navigate(['/assistente/politicas', p.id]));
  }

  protected disable(): void {
    this.dialog
      .open<DisablePortfolioDialog, DisablePortfolioData, PolicyView>(DisablePortfolioDialog, { data: { creditorId: null, portfolioId: null } })
      .afterClosed()
      .subscribe((p) => {
        if (p) {
          this.notifications.success('Assistente desligado na carteira. As conversas em andamento estão sendo transferidas para humanos.');
          this.list.reload();
        }
      });
  }
}
