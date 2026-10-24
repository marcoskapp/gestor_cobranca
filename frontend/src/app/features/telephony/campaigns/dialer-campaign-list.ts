import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { QueueNames } from '../../collection/data/collection.lookups';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { DialerCampaignsApi } from '../data/telephony.api';
import { DIALER_MODE_LABELS } from '../data/telephony.labels';
import { CampaignView } from '../data/telephony.models';
import { percentText } from '../data/telephony.rules';
import { CampaignDialog, CampaignDialogData } from './campaign-dialog';

const ORDER: Record<string, number> = { ACTIVE: 0, PAUSED: 1, DRAFT: 2, FINISHED: 3 };

/** Campanhas de discagem (progressivo/preditivo) montadas a partir das filas do collection. */
@Component({
  selector: 'app-dialer-campaign-list',
  imports: [RouterLink, MatButtonModule, MatIconModule, MatTooltipModule, AppDatePipe, CellDef, DataTable, PageHeader, StatusChip],
  template: `
    <app-page-header title="Campanhas de discagem" subtitle="Discador progressivo ou preditivo sobre uma fila de cobrança, com abandono máximo, retentativas e janela própria.">
      @if (canManage()) {
        <button mat-flat-button type="button" (click)="create()" data-tour="dialer-new">
          <mat-icon aria-hidden="true">add</mat-icon>
          Nova campanha
        </button>
      }
    </app-page-header>
    <p class="app-alert app-alert--info" role="note">
      <mat-icon aria-hidden="true">verified_user</mat-icon>
      <span>Toda discagem passa pelo porteiro de contato (horário, feriados, limites e restrições). Fora da janela, o item é adiado sem gastar tentativa.</span>
    </p>
    <app-data-table
      caption="Campanhas de discagem"
      [rows]="rows()"
      [columns]="columns"
      [loading]="list.isLoading()"
      [failed]="list.status() === 'error'"
      (retry)="list.reload()"
      emptyIcon="phone_forwarded"
      emptyMessage="Nenhuma campanha de discagem."
      emptyHint="Crie uma campanha a partir de uma fila de cobrança."
    >
      <ng-template appCell="name" [appCellOf]="rows()" let-c>
        <strong>{{ c.name }}</strong>
        <div class="app-muted dl-small">{{ c.code }} · {{ c.creditorId ? creditors.name(c.creditorId) : 'vários credores' }}</div>
      </ng-template>
      <ng-template appCell="queue" [appCellOf]="rows()" let-c>
        {{ queues.name(c.queueId) }}
        <div class="app-muted dl-small">{{ c.entriesLoaded }} caso(s) carregados</div>
      </ng-template>
      <ng-template appCell="mode" [appCellOf]="rows()" let-c>
        {{ modes[c.mode] }}
        <div class="app-muted dl-small">abandono máx. {{ percent(c.maxAbandonRate) }}{{ c.mode === 'PREDICTIVE' ? ' · razão até ' + c.maxDialRatio : '' }}</div>
      </ng-template>
      <ng-template appCell="status" [appCellOf]="rows()" let-c>
        <app-status-chip domain="dialerCampaign" [status]="c.status" />
        <div class="app-muted dl-small">{{ (c.finishedAt ?? c.pausedAt ?? c.activatedAt) | appDate }}</div>
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-c>
        <a mat-icon-button [routerLink]="['/telefonia/campanhas', c.id]" [attr.aria-label]="'Abrir campanha ' + c.name" matTooltip="Abrir painel">
          <mat-icon aria-hidden="true">chevron_right</mat-icon>
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .dl-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class DialerCampaignList {
  private readonly api = inject(DialerCampaignsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  protected readonly modes = DIALER_MODE_LABELS;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.TELEPHONY_MANAGE));
  protected readonly percent = (v: number) => percentText(v, 1);

  protected readonly list = rxResource({ stream: () => this.api.list() });
  private readonly values = valueOr(this.list, []);
  protected readonly rows = computed(() => [...this.values()].sort((a, b) => (ORDER[a.status] ?? 9) - (ORDER[b.status] ?? 9) || a.name.localeCompare(b.name)));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.rows().map((c) => c.creditorId));
  protected readonly queues = nameMap(inject(QueueNames), () => this.rows().map((c) => c.queueId));

  protected readonly columns: TableColumn[] = [
    { key: 'name', header: 'Campanha' },
    { key: 'queue', header: 'Fila de origem' },
    { key: 'mode', header: 'Ritmo' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected create(): void {
    this.dialog
      .open<CampaignDialog, CampaignDialogData, CampaignView>(CampaignDialog, { data: { campaign: null }, width: '720px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((c) => c && void this.router.navigate(['/telefonia/campanhas', c.id]));
  }
}
