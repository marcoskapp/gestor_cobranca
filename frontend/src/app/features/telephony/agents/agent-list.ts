import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { describeStatus } from '../../../shared/status/status';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { UserLookup } from '../../admin/data/user.lookup';
import { AgentsApi, DialerCampaignsApi } from '../data/telephony.api';
import { PAUSE_REASON_LABELS, TELEPHONY_ERROR_LABELS } from '../data/telephony.labels';
import { AgentView } from '../data/telephony.models';
import { SUPERVISION_POLL_MS, clockText } from '../data/telephony.rules';

export interface AgentDialogData {
  agent: AgentView | null;
  userName: string | null;
}

/** Habilita o usuário na telefonia com o ramal (ou usuário SIP/identidade WebRTC) do provedor. */
@Component({
  selector: 'app-agent-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>{{ data.agent ? 'Ramal de ' + data.userName : 'Habilitar operador na telefonia' }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        @if (!data.agent) {
          <app-entity-picker [control]="form.controls.user" [source]="users" label="Usuário" [single]="true" />
        }
        <mat-form-field class="app-full-width">
          <mat-label>Ramal ou usuário SIP</mat-label>
          <input matInput formControlName="extension" maxlength="64" required autocomplete="off" />
          <mat-hint>O provedor chama este ramal antes de discar para o devedor</mat-hint>
          <mat-error>Informe o ramal.</mat-error>
        </mat-form-field>
        <mat-checkbox formControlName="active">Habilitado (pode entrar na telefonia)</mat-checkbox>
        @if (error()) {
          <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving() || (!data.agent && !form.controls.user.value.length)">Salvar</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class AgentDialog {
  protected readonly data = inject<AgentDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<AgentDialog, AgentView>>(MatDialogRef);
  private readonly api = inject(AgentsApi);
  protected readonly users = inject(UserLookup);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    user: this.fb.control<string[]>(this.data.agent ? [this.data.agent.userId] : []),
    extension: [this.data.agent?.extension ?? '', [Validators.required, Validators.maxLength(64)]],
    active: [this.data.agent?.active ?? true],
  });

  protected save(): void {
    const v = this.form.getRawValue();
    const userId = v.user[0];
    if (this.form.invalid || !userId || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.api
      .configure(userId, v.extension.trim(), v.active)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (a) => this.ref.close(a),
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && TELEPHONY_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }
}

/** Operadores habilitados na telefonia: ramal, presença ao vivo, campanha e saída forçada. */
@Component({
  selector: 'app-agent-list',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule, AppDatePipe, CellDef, DataTable, PageHeader, StatusChip],
  template: `
    <app-page-header title="Operadores e ramais" subtitle="Quem está na telefonia agora, em que estado e há quanto tempo; ramais do provedor por usuário.">
      @if (canManage()) {
        <button mat-flat-button type="button" (click)="configure(null)">
          <mat-icon aria-hidden="true">person_add</mat-icon>
          Habilitar operador
        </button>
      }
    </app-page-header>
    <app-data-table
      caption="Operadores na telefonia"
      [rows]="rows()"
      [columns]="columns"
      [loading]="list.isLoading()"
      [failed]="list.status() === 'error'"
      (retry)="list.reload()"
      emptyIcon="headset_mic"
      emptyMessage="Nenhum operador habilitado na telefonia."
      emptyHint="Habilite os usuários com o ramal do provedor para usar o clique para ligar e o discador."
    >
      <ng-template appCell="user" [appCellOf]="rows()" let-a>
        {{ users.name(a.userId) }}
        <div class="app-muted al-small">ramal {{ a.extension }}{{ a.active ? '' : ' · desabilitado' }}</div>
      </ng-template>
      <ng-template appCell="status" [appCellOf]="rows()" let-a>
        <app-status-chip domain="agentPresence" [status]="a.status" />
        @if (a.pauseReason) {
          <div class="app-muted al-small">{{ pauses[a.pauseReason] }}</div>
        }
      </ng-template>
      <ng-template appCell="since" [appCellOf]="rows()" let-a>
        <span class="app-numeric">{{ a.status === 'OFFLINE' ? '—' : since(a.statusSince) }}</span>
        <div class="app-muted al-small">sinal {{ a.lastHeartbeatAt | appDate: 'time' }}</div>
      </ng-template>
      <ng-template appCell="campaign" [appCellOf]="rows()" let-a>{{ a.campaignId ? campaignName(a.campaignId) : 'clique para ligar' }}</ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-a>
        @if (canManage()) {
          <button mat-icon-button type="button" (click)="configure(a)" [attr.aria-label]="'Editar ramal de ' + users.name(a.userId)" matTooltip="Editar ramal"><mat-icon aria-hidden="true">edit</mat-icon></button>
        }
        @if (a.status !== 'OFFLINE') {
          <button mat-icon-button type="button" (click)="forceLogout(a)" [attr.aria-label]="'Tirar ' + users.name(a.userId) + ' da telefonia'" matTooltip="Tirar da telefonia (auditado)"><mat-icon aria-hidden="true">logout</mat-icon></button>
        }
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .al-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class AgentList {
  private readonly api = inject(AgentsApi);
  private readonly campaignsApi = inject(DialerCampaignsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly pauses = PAUSE_REASON_LABELS;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.TELEPHONY_MANAGE));

  protected readonly list = rxResource({ stream: () => this.api.list() });
  private readonly values = valueOr(this.list, []);
  private readonly order: Record<string, number> = { ON_CALL: 0, WRAP_UP: 1, AVAILABLE: 2, PAUSED: 3, OFFLINE: 4 };
  protected readonly rows = computed(() => [...this.values()].sort((a, b) => (this.order[a.status] ?? 9) - (this.order[b.status] ?? 9)));
  private readonly campaignList = rxResource({ stream: () => this.campaignsApi.list() });
  private readonly campaigns = valueOr(this.campaignList, []);
  protected readonly users = nameMap(inject(UserNames), () => this.rows().map((a) => a.userId), { batch: USER_NAMES_BATCH });
  private readonly now = signal(Date.now());

  protected readonly columns: TableColumn[] = [
    { key: 'user', header: 'Operador' },
    { key: 'status', header: 'Presença' },
    { key: 'since', header: 'Neste estado' },
    { key: 'campaign', header: 'Campanha' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  constructor() {
    const clock = setInterval(() => this.now.set(Date.now()), 1000);
    const poll = setInterval(() => this.list.reload(), SUPERVISION_POLL_MS * 2);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(clock);
      clearInterval(poll);
    });
  }

  protected since(at: string | null): string {
    return at ? clockText(this.now() - new Date(at).getTime()) : '—';
  }

  protected campaignName(id: string): string {
    return this.campaigns().find((c) => c.id === id)?.name ?? 'campanha';
  }

  protected configure(agent: AgentView | null): void {
    this.dialog
      .open<AgentDialog, AgentDialogData, AgentView>(AgentDialog, { data: { agent, userName: agent ? this.users.name(agent.userId) : null }, width: '520px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success('Ramal salvo.');
          this.list.reload();
        }
      });
  }

  protected forceLogout(a: AgentView): void {
    this.confirm
      .confirm({
        title: 'Tirar da telefonia',
        message: 'O operador sai da telefonia e deixa de receber chamadas. Chamada em andamento segue até o fim. A ação fica na auditoria.',
        confirmLabel: 'Tirar da telefonia',
        destructive: true,
        reason: 'required',
        reasonMaxLength: 300,
        details: [
          { label: 'Operador', value: this.users.name(a.userId) },
          { label: 'Presença', value: describeStatus('agentPresence', a.status).label },
        ],
      })
      .subscribe((reason) =>
        this.api.forceLogout(a.userId, reason).subscribe({
          next: () => {
            this.notifications.success('Operador retirado da telefonia.');
            this.list.reload();
          },
          error: (err: unknown) => {
            const e = toApiError(err);
            this.notifications.error((e.code && TELEPHONY_ERROR_LABELS[e.code]) || userMessage(e));
          },
        }),
      );
  }
}
