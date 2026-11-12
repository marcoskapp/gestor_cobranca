import { Component, input, output } from '@angular/core';
import { SessionView } from '../../../core/auth/auth.models';
import { AppDatePipe } from '../../pipes/date-time.pipe';
import { CellDef } from '../data-table/cell-def';
import { DataTable, TableColumn } from '../data-table/data-table';
import { DATA_TABLE_STACK } from '../data-table/compact-tables';
import { StatusChip } from '../status-chip/status-chip';

/**
 * Sessões de um usuário (próprias ou administradas). Oito colunas: onde não cabem (cartão estreito, zoom), a tabela vira
 * lista com "Mais detalhes" em vez da rolagem lateral (`DATA_TABLE_STACK`).
 */
@Component({
  selector: 'app-session-table',
  providers: [{ provide: DATA_TABLE_STACK, useValue: 'fit' }],
  imports: [DataTable, CellDef, StatusChip, AppDatePipe],
  template: `
    <app-data-table
      [caption]="caption()"
      [rows]="sessions()"
      [columns]="columns"
      [loading]="loading()"
      [failed]="failed()"
      (retry)="retry.emit()"
      emptyMessage="Nenhuma sessão registrada."
    >
      <ng-template appCell="status" [appCellOf]="sessions()" let-s>
        <app-status-chip domain="session" [status]="s.status" />
        @if (s.current) {
          <span class="badge rounded-pill border bg-info-subtle text-info-emphasis border-info-subtle ms-1">Esta sessão</span>
        }
      </ng-template>
      <ng-template appCell="startedAt" [appCellOf]="sessions()" let-s>{{ s.startedAt | appDate }}</ng-template>
      <ng-template appCell="lastSeenAt" [appCellOf]="sessions()" let-s>{{ s.lastSeenAt | appDate }}</ng-template>
      <ng-template appCell="absoluteExpiresAt" [appCellOf]="sessions()" let-s>{{ s.absoluteExpiresAt | appDate }}</ng-template>
      <ng-template appCell="mfaVerified" [appCellOf]="sessions()" let-s>{{ s.mfaVerified ? 'Sim' : 'Não' }}</ng-template>
      <ng-template appCell="userAgent" [appCellOf]="sessions()" let-s>
        <span class="app-muted" [title]="s.userAgent ?? ''">{{ shortAgent(s.userAgent) }}</span>
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="sessions()" let-s>
        @if (revocable() && s.status === 'ACTIVE' && !s.current) {
          <button type="button" class="btn btn-sm btn-outline-danger" (click)="revoke.emit(s)">Encerrar</button>
        }
      </ng-template>
    </app-data-table>
  `,
})
export class SessionTable {
  readonly sessions = input.required<SessionView[]>();
  readonly caption = input('Sessões');
  readonly loading = input(false);
  readonly failed = input(false);
  /** Exibe "Encerrar" por sessão (somente nas sessões do próprio usuário). */
  readonly revocable = input(false);
  readonly revoke = output<SessionView>();
  readonly retry = output<void>();

  protected readonly columns: TableColumn[] = [
    { key: 'status', header: 'Situação', mobile: 'badge' },
    { key: 'startedAt', header: 'Início', mobile: 'title' },
    { key: 'lastSeenAt', header: 'Última atividade', mobile: 'meta' },
    { key: 'absoluteExpiresAt', header: 'Expira em', mobile: 'hidden' },
    { key: 'mfaVerified', header: 'MFA', mobile: 'hidden' },
    { key: 'clientIp', header: 'IP', mobile: 'hidden' },
    { key: 'userAgent', header: 'Navegador', mobile: 'meta' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected shortAgent(agent: string | null): string {
    if (!agent) {
      return '—';
    }
    const browser = /(Edg|Firefox|Chrome|Safari)\/[\d.]+/.exec(agent)?.[0]?.replace('Edg', 'Edge') ?? '';
    const os = /(Windows NT [\d.]+|Mac OS X [\d_]+|Android [\d.]+|iPhone OS [\d_]+|Linux)/.exec(agent)?.[0] ?? '';
    const summary = [browser, os].filter(Boolean).join(' · ');
    return summary || agent.slice(0, 40);
  }
}
