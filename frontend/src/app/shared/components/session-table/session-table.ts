import { Component, input, output } from '@angular/core';
import { SessionView } from '../../../core/auth/auth.models';
import { AppDatePipe } from '../../pipes/date-time.pipe';
import { CellDef } from '../data-table/cell-def';
import { DataTable, TableColumn } from '../data-table/data-table';
import { StatusChip } from '../status-chip/status-chip';

/** Sessões de um usuário (próprias ou administradas). */
@Component({
  selector: 'app-session-table',
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
    { key: 'status', header: 'Situação' },
    { key: 'startedAt', header: 'Início' },
    { key: 'lastSeenAt', header: 'Última atividade' },
    { key: 'absoluteExpiresAt', header: 'Expira em' },
    { key: 'mfaVerified', header: 'MFA' },
    { key: 'clientIp', header: 'IP' },
    { key: 'userAgent', header: 'Navegador' },
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
