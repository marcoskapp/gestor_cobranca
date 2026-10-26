import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { describeStatus } from '../../../shared/status/status';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { UserLookup } from '../../admin/data/user.lookup';
import { AgentsApi, DialerCampaignsApi, TelephonyAccountsApi } from '../data/telephony.api';
import { AgentEndpointsDialog, AgentEndpointsDialogData } from './agent-endpoints-dialog';
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
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, EntityPicker, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.agent ? 'Ramal de ' + data.userName : 'Habilitar operador na telefonia' }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body d-grid gap-3">
        @if (!data.agent) {
          <app-entity-picker [control]="form.controls.user" [source]="users" label="Usuário" [single]="true" />
        }
        <app-field label="Ramal padrão ou usuário SIP" hint="Vale nas plataformas em que o operador não tem ramal próprio (configure em &quot;Ramais por plataforma&quot;)" error="Informe o ramal.">
          <input appInput formControlName="extension" maxlength="64" required autocomplete="off" />
        </app-field>
        <div class="form-check">
          <input class="form-check-input" type="checkbox" id="ag-active" formControlName="active" />
          <label class="form-check-label" for="ag-active">Habilitado (pode entrar na telefonia)</label>
        </div>
        @if (error()) {
          <p class="alert alert-danger d-flex gap-2 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></p>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving() || (!data.agent && !form.controls.user.value.length)">Salvar</button>
      </div>
    </form>
  `,
})
export class AgentDialog {
  protected readonly data = inject<AgentDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<AgentView>>(DialogRef);
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
  imports: [NgbTooltip, Icon, AppDatePipe, CellDef, DataTable, PageHeader, StatusChip],
  template: `
    <app-page-header title="Operadores e ramais" subtitle="Quem está na telefonia agora, em que estado e há quanto tempo; ramais do provedor por usuário.">
      @if (canManage()) {
        <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="configure(null)">
          <app-icon name="person-plus" />
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
      emptyIcon="headset"
      emptyMessage="Nenhum operador habilitado na telefonia."
      emptyHint="Habilite os usuários com o ramal do provedor para usar o clique para ligar e o discador."
    >
      <ng-template appCell="user" [appCellOf]="rows()" let-a>
        {{ users.name(a.userId) }}
        <div class="app-muted small">ramal {{ a.extension }}{{ a.active ? '' : ' · desabilitado' }}{{ platformText(a) }}</div>
      </ng-template>
      <ng-template appCell="status" [appCellOf]="rows()" let-a>
        <app-status-chip domain="agentPresence" [status]="a.status" />
        @if (a.pauseReason) {
          <div class="app-muted small">{{ pauses[a.pauseReason] }}</div>
        }
      </ng-template>
      <ng-template appCell="since" [appCellOf]="rows()" let-a>
        <span class="app-numeric">{{ a.status === 'OFFLINE' ? '—' : since(a.statusSince) }}</span>
        <div class="app-muted small">sinal {{ a.lastHeartbeatAt | appDate: 'time' }}</div>
      </ng-template>
      <ng-template appCell="campaign" [appCellOf]="rows()" let-a>{{ a.campaignId ? campaignName(a.campaignId) : 'clique para ligar' }}</ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-a>
        @if (canManage()) {
          <button class="btn btn-link" type="button" (click)="configure(a)" [attr.aria-label]="'Editar ramal de ' + users.name(a.userId)" ngbTooltip="Editar ramal"><app-icon name="pencil" /></button>
          @if (accountRows().length > 1) {
            <button class="btn btn-link" type="button" (click)="platforms(a)" [attr.aria-label]="'Ramais por plataforma de ' + users.name(a.userId)" ngbTooltip="Ramais por plataforma"><app-icon name="diagram-3" /></button>
          }
        }
        @if (a.status !== 'OFFLINE') {
          <button class="btn btn-link" type="button" (click)="forceLogout(a)" [attr.aria-label]="'Tirar ' + users.name(a.userId) + ' da telefonia'" ngbTooltip="Tirar da telefonia (auditado)"><app-icon name="box-arrow-right" /></button>
        }
      </ng-template>
    </app-data-table>
  `,
})
export class AgentList {
  private readonly api = inject(AgentsApi);
  private readonly campaignsApi = inject(DialerCampaignsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly pauses = PAUSE_REASON_LABELS;
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.TELEPHONY_MANAGE));

  protected readonly list = rxResource({ stream: () => this.api.list() });
  private readonly values = valueOr(this.list, []);
  private readonly order: Record<string, number> = { ON_CALL: 0, WRAP_UP: 1, AVAILABLE: 2, PAUSED: 3, OFFLINE: 4 };
  protected readonly rows = computed(() => [...this.values()].sort((a, b) => (this.order[a.status] ?? 9) - (this.order[b.status] ?? 9)));
  private readonly accountsApi = inject(TelephonyAccountsApi);
  /** Contas e ramais por plataforma: só para quem gerencia (a supervisão vê a presença). */
  private readonly accountList = rxResource({ params: () => this.canManage() || undefined, stream: () => this.accountsApi.list() });
  protected readonly accountRows = valueOr(this.accountList, []);
  private readonly endpointList = rxResource({ params: () => this.canManage() || undefined, stream: () => this.api.endpoints() });
  private readonly endpoints = valueOr(this.endpointList, []);
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

  protected platformText(a: AgentView): string {
    const own = this.endpoints().filter((e) => e.userId === a.userId);
    if (!own.length) {
      return '';
    }
    const on = own.filter((e) => e.active).length;
    const off = own.length - on;
    return [on ? ` · ${on} ramal(is) por plataforma` : '', off ? ` · fora de ${off} plataforma(s)` : ''].join('');
  }

  protected platforms(agent: AgentView): void {
    this.dialog
      .open<AgentEndpointsDialog, AgentEndpointsDialogData, boolean>(AgentEndpointsDialog, {
        data: { agent, userName: this.users.name(agent.userId), accounts: this.accountRows(), endpoints: this.endpoints() },
      })
      .afterClosed()
      .subscribe((changed) => {
        if (changed) {
          this.notifications.success('Ramais por plataforma salvos.');
          this.endpointList.reload();
        }
      });
  }

  protected configure(agent: AgentView | null): void {
    this.dialog
      .open<AgentDialog, AgentDialogData, AgentView>(AgentDialog, { data: { agent, userName: agent ? this.users.name(agent.userId) : null } })
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
