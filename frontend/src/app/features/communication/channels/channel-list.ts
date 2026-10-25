import { Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NgbDropdownModule } from '@ng-bootstrap/ng-bootstrap';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { ChannelsApi } from '../data/communication.api';
import { CHANNEL_ICONS, CHANNEL_LABELS, CREDENTIAL_LABELS, PROVIDER_CREDENTIALS, PROVIDER_LABELS } from '../data/communication.labels';
import { AccountView } from '../data/communication.models';
import { AccountDialog, AccountDialogData, CredentialsDialog } from './channel-dialogs';

/**
 * Contas de canal (WhatsApp, e-mail, SMS) da assessoria e por credor. Credenciais nunca são
 * exibidas: a tela mostra só os nomes das chaves configuradas e quando foram trocadas.
 */
@Component({
  selector: 'app-channel-list',
  imports: [NgbDropdownModule, Icon, CellDef, DataTable, PageHeader, StatusChip, AppDatePipe],
  template: `
    <app-page-header title="Canais de comunicação" subtitle="Contas de envio por canal. O credor usa a própria conta ativa; sem ela, a conta padrão da assessoria.">
      <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="edit(null)">
        <app-icon name="plus-lg" />
        Nova conta
      </button>
    </app-page-header>

    <app-data-table
      caption="Contas de canal"
      [rows]="rows()"
      [columns]="columns"
      [loading]="accounts.isLoading()"
      [failed]="accounts.status() === 'error'"
      (retry)="accounts.reload()"
      emptyIcon="broadcast-pin"
      emptyMessage="Nenhuma conta de canal cadastrada."
      emptyHint="Sem conta ativa no canal, o envio é recusado."
    >
      <ng-template appCell="name" [appCellOf]="rows()" let-a>
        <strong>{{ a.name }}</strong>
        <div class="app-muted">{{ a.creditorId ? creditors.name(a.creditorId) : 'Padrão da assessoria' }}</div>
      </ng-template>
      <ng-template appCell="channel" [appCellOf]="rows()" let-a>
        <span class="d-inline-flex align-items-center gap-1"><app-icon [name]="icons[a.channel]" />{{ channels[a.channel] }}</span>
        <div class="app-muted">{{ providers[a.provider] }}</div>
      </ng-template>
      <ng-template appCell="sender" [appCellOf]="rows()" let-a>
        {{ a.senderAddress }}
        @if (a.senderName) {
          <div class="app-muted">{{ a.senderName }}</div>
        }
      </ng-template>
      <ng-template appCell="credentials" [appCellOf]="rows()" let-a>
        @if (a.credentialKeys.length) {
          <span>{{ keyText(a) }}</span>
          <div class="app-muted">trocadas em {{ a.credentialsUpdatedAt | appDate }}</div>
        } @else if (required(a).length) {
          <span class="fw-semibold text-danger-emphasis">Não configuradas</span>
        } @else {
          <span class="app-muted">Não exigidas</span>
        }
      </ng-template>
      <ng-template appCell="status" [appCellOf]="rows()" let-a>
        <app-status-chip domain="active" [status]="a.active" />
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-a>
        <div ngbDropdown placement="bottom-end" class="d-inline-flex">
          <button class="btn btn-link" type="button" ngbDropdownToggle [attr.aria-label]="'Ações da conta ' + a.name">
            <app-icon name="three-dots-vertical" />
          </button>
          <div ngbDropdownMenu class="shadow app-menu" [attr.aria-label]="'Ações da conta ' + a.name">
            <button ngbDropdownItem type="button" (click)="edit(a)"><app-icon name="pencil" />Editar</button>
            @if (required(a).length) {
              <button ngbDropdownItem type="button" (click)="rotate(a)"><app-icon name="key" />Trocar credenciais</button>
            }
            @if (a.webhookPath) {
              <button ngbDropdownItem type="button" (click)="copyWebhook(a)"><app-icon name="link-45deg" />Copiar endereço do webhook</button>
            }
            <button ngbDropdownItem type="button" (click)="toggle(a)">
              <app-icon [name]="a.active ? 'pause-circle' : 'play-circle'" />{{ a.active ? 'Desativar' : 'Ativar' }}
            </button>
          </div>
        </div>
      </ng-template>
    </app-data-table>
  `,
})
export class ChannelList {
  private readonly api = inject(ChannelsApi);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly channels = CHANNEL_LABELS;
  protected readonly icons = CHANNEL_ICONS;
  protected readonly providers = PROVIDER_LABELS;

  protected readonly accounts = rxResource({ stream: () => this.api.list() });
  protected readonly rows = valueOr(this.accounts, []);
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.rows().map((a) => a.creditorId));

  protected readonly columns: TableColumn[] = [
    { key: 'name', header: 'Conta' },
    { key: 'channel', header: 'Canal' },
    { key: 'sender', header: 'Remetente' },
    { key: 'credentials', header: 'Credenciais' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected required(a: AccountView): readonly string[] {
    return PROVIDER_CREDENTIALS[a.provider] ?? [];
  }

  protected keyText(a: AccountView): string {
    return a.credentialKeys.map((k) => CREDENTIAL_LABELS[k] ?? k).join(', ');
  }

  protected edit(account: AccountView | null): void {
    this.dialog
      .open<AccountDialog, AccountDialogData, AccountView>(AccountDialog, { data: { account } })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success(account ? 'Conta atualizada.' : 'Conta cadastrada. Ative-a para começar a enviar.');
          this.accounts.reload();
        }
      });
  }

  protected rotate(account: AccountView): void {
    this.dialog
      .open<CredentialsDialog, AccountDialogData, AccountView>(CredentialsDialog, { data: { account } })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success('Credenciais trocadas.');
          this.accounts.reload();
        }
      });
  }

  protected toggle(a: AccountView): void {
    this.confirm
      .confirm({
        title: a.active ? 'Desativar conta' : 'Ativar conta',
        message: a.active
          ? 'Os envios deste canal passam a usar a conta padrão da assessoria (se houver). Mensagens na fila podem falhar.'
          : 'Só uma conta pode ficar ativa por canal e credor.',
        confirmLabel: a.active ? 'Desativar' : 'Ativar',
        destructive: a.active,
        details: [
          { label: 'Conta', value: a.name },
          { label: 'Canal', value: CHANNEL_LABELS[a.channel] },
        ],
      })
      .subscribe(() =>
        (a.active ? this.api.deactivate(a.id) : this.api.activate(a.id)).subscribe(() => {
          this.notifications.success(a.active ? 'Conta desativada.' : 'Conta ativada.');
          this.accounts.reload();
        }),
      );
  }

  protected copyWebhook(a: AccountView): void {
    const url = new URL(a.webhookPath!, globalThis.location?.origin ?? 'https://localhost').toString();
    void globalThis.navigator?.clipboard?.writeText(url).then(() => this.notifications.success('Endereço do webhook copiado.'));
  }
}
