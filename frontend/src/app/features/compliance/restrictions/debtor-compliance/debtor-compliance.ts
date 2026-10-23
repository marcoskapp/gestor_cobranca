import { Component, computed, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthService } from '../../../../core/auth/auth.service';
import { Permission } from '../../../../core/auth/permission';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../../shared/data/name-map';
import { pagedQuery } from '../../../../shared/data/paged-query';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { CreditorLookup } from '../../../organization/data/organization.lookups';
import { RestrictionsApi } from '../../data/compliance.api';
import {
  ATTEMPT_ORIGIN_ICONS,
  ATTEMPT_ORIGIN_LABELS,
  CHANNEL_LABELS,
  RESTRICTION_SOURCE_LABELS,
  RESTRICTION_TYPE_LABELS,
  restrictionScope,
} from '../../data/compliance.labels';
import { RestrictionView } from '../../data/compliance.models';
import { EvidenceButtons } from '../evidence/evidence-buttons';
import { RestrictionActions } from '../restriction-actions';

/** Restrições e tentativas de contato de um devedor (aba da ficha). */
@Component({
  selector: 'app-debtor-compliance',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule, DataTable, CellDef, StatusChip, AppDatePipe, EvidenceButtons],
  template: `
    <div class="dc-toolbar">
      <h3 class="app-section-title">Restrições</h3>
      @if (canManage()) {
        <button mat-stroked-button type="button" (click)="create()">
          <mat-icon aria-hidden="true">block</mat-icon>
          Nova restrição
        </button>
      }
    </div>
    <app-data-table
      caption="Restrições de contato do devedor"
      [rows]="restrictions.rows()"
      [columns]="restrictionColumns"
      [loading]="restrictions.loading()"
      [failed]="restrictions.failed()"
      [page]="restrictions.tablePage()"
      (pageChange)="restrictions.setPage($event)"
      (retry)="restrictions.reload()"
      emptyIcon="verified_user"
      emptyMessage="Nenhuma restrição de contato registrada."
      emptyHint="O contato segue a política de contato vigente (janela, dias e limites)."
    >
      <ng-template appCell="type" [appCellOf]="restrictions.rows()" let-r>
        {{ typeLabels[r.type] }}
        <div class="app-muted">{{ sourceLabels[r.source] }}</div>
      </ng-template>
      <ng-template appCell="scope" [appCellOf]="restrictions.rows()" let-r>{{ scope(r) }}</ng-template>
      <ng-template appCell="period" [appCellOf]="restrictions.rows()" let-r>
        {{ r.startsAt | appDate: 'date' }} – {{ r.endsAt ? (r.endsAt | appDate: 'date') : 'sem prazo' }}
      </ng-template>
      <ng-template appCell="status" [appCellOf]="restrictions.rows()" let-r>
        <app-status-chip domain="restriction" [status]="r.status" />
        @if (r.status === 'ACTIVE' && !r.effective) {
          <div class="app-muted">Fora da vigência</div>
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="restrictions.rows()" let-r>
        <app-evidence-buttons [restriction]="r" [canManage]="canManage()" (updated)="restrictions.reload()" />
        @if (canManage() && r.status === 'ACTIVE') {
          <button mat-icon-button type="button" (click)="revoke(r)" aria-label="Revogar restrição" matTooltip="Revogar">
            <mat-icon aria-hidden="true">undo</mat-icon>
          </button>
        }
      </ng-template>
    </app-data-table>

    <h3 class="app-section-title dc-gap">Tentativas de contato</h3>
    <app-data-table
      caption="Tentativas de contato do devedor"
      [rows]="attempts.rows()"
      [columns]="attemptColumns"
      [loading]="attempts.loading()"
      [failed]="attempts.failed()"
      [page]="attempts.tablePage()"
      (pageChange)="attempts.setPage($event)"
      (retry)="attempts.reload()"
      emptyIcon="call"
      emptyMessage="Nenhuma tentativa de contato registrada."
    >
      <ng-template appCell="attemptedAt" [appCellOf]="attempts.rows()" let-a>{{ a.attemptedAt | appDate }}</ng-template>
      <ng-template appCell="channel" [appCellOf]="attempts.rows()" let-a>{{ channelLabels[a.channel] }}</ng-template>
      <ng-template appCell="creditor" [appCellOf]="attempts.rows()" let-a>{{ creditors.name(a.creditorId) }}</ng-template>
      <ng-template appCell="origin" [appCellOf]="attempts.rows()" let-a>
        <mat-icon class="dc-icon" aria-hidden="true">{{ originIcons[a.origin] }}</mat-icon> {{ originLabels[a.origin] ?? a.origin }}
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .dc-toolbar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      margin-bottom: 8px;
    }
    .dc-gap {
      margin-top: 24px;
    }
    .dc-icon {
      width: 16px;
      height: 16px;
      font-size: 16px;
      vertical-align: -3px;
    }
  `,
})
export class DebtorCompliance {
  readonly debtorId = input.required<string>();
  readonly debtorName = input<string>('');

  private readonly api = inject(RestrictionsApi);
  private readonly actions = inject(RestrictionActions);
  private readonly auth = inject(AuthService);

  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.CONTACT_RESTRICTION_MANAGE));
  protected readonly typeLabels = RESTRICTION_TYPE_LABELS;
  protected readonly sourceLabels = RESTRICTION_SOURCE_LABELS;
  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly originLabels = ATTEMPT_ORIGIN_LABELS;
  protected readonly originIcons = ATTEMPT_ORIGIN_ICONS;

  private readonly filter = computed(() => this.debtorId());
  protected readonly restrictions = pagedQuery(this.filter, (debtorId, page) => this.api.search({ debtorId }, page));
  protected readonly attempts = pagedQuery(this.filter, (debtorId, page) => this.api.attempts(debtorId, page));
  protected readonly creditors = nameMap(inject(CreditorLookup), () => [
    ...this.restrictions.rows().map((r) => r.creditorId),
    ...this.attempts.rows().map((a) => a.creditorId),
  ]);

  protected readonly restrictionColumns: TableColumn[] = [
    { key: 'type', header: 'Tipo' },
    { key: 'scope', header: 'Alcance' },
    { key: 'reason', header: 'Motivo' },
    { key: 'period', header: 'Vigência' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected readonly attemptColumns: TableColumn[] = [
    { key: 'attemptedAt', header: 'Quando' },
    { key: 'channel', header: 'Canal' },
    { key: 'creditor', header: 'Credor' },
    { key: 'origin', header: 'Origem' },
  ];

  protected scope(r: RestrictionView): string {
    return restrictionScope(r, (id) => this.creditors.name(id));
  }

  protected create(): void {
    this.actions.create({ debtorId: this.debtorId(), debtorName: this.debtorName() }).subscribe(() => this.restrictions.reload());
  }

  protected revoke(r: RestrictionView): void {
    this.actions.revoke(r, this.scope(r)).subscribe(() => this.restrictions.reload());
  }
}
