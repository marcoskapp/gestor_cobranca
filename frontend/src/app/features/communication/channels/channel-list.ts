import { Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
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
  imports: [MatButtonModule, MatIconModule, MatMenuModule, MatTooltipModule, CellDef, DataTable, PageHeader, StatusChip, AppDatePipe],
  template: `
    <app-page-header title="Canais de comunicação" subtitle="Contas de envio por canal. O credor usa a própria conta ativa; sem ela, a conta padrão da assessoria.">
      <button mat-flat-button type="button" (click)="edit(null)">
        <mat-icon aria-hidden="true">add</mat-icon>
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
      emptyIcon="settings_input_antenna"
      emptyMessage="Nenhuma conta de canal cadastrada."
      emptyHint="Sem conta ativa no canal, o envio é recusado."
    >
      <ng-template appCell="name" [appCellOf]="rows()" let-a>
        <strong>{{ a.name }}</strong>
        <div class="app-muted">{{ a.creditorId ? creditors.name(a.creditorId) : 'Padrão da assessoria' }}</div>
      </ng-template>
      <ng-template appCell="channel" [appCellOf]="rows()" let-a>
        <span class="chl-chan"><mat-icon aria-hidden="true">{{ icons[a.channel] }}</mat-icon>{{ channels[a.channel] }}</span>
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
          <span class="chl-missing">Não configuradas</span>
        } @else {
          <span class="app-muted">Não exigidas</span>
        }
      </ng-template>
      <ng-template appCell="status" [appCellOf]="rows()" let-a>
        <app-status-chip domain="active" [status]="a.active" />
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-a>
        <button mat-icon-button type="button" [matMenuTriggerFor]="menu" [attr.aria-label]="'Ações da conta ' + a.name">
          <mat-icon aria-hidden="true">more_vert</mat-icon>
        </button>
        <mat-menu #menu="matMenu">
          <button mat-menu-item type="button" (click)="edit(a)"><mat-icon aria-hidden="true">edit</mat-icon>Editar</button>
          @if (required(a).length) {
            <button mat-menu-item type="button" (click)="rotate(a)"><mat-icon aria-hidden="true">key</mat-icon>Trocar credenciais</button>
          }
          @if (a.webhookPath) {
            <button mat-menu-item type="button" (click)="copyWebhook(a)"><mat-icon aria-hidden="true">link</mat-icon>Copiar endereço do webhook</button>
          }
          <button mat-menu-item type="button" (click)="toggle(a)">
            <mat-icon aria-hidden="true">{{ a.active ? 'pause_circle' : 'play_circle' }}</mat-icon>{{ a.active ? 'Desativar' : 'Ativar' }}
          </button>
        </mat-menu>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .chl-chan {
      display: inline-flex;
      align-items: center;
      gap: 4px;
    }
    .chl-missing {
      font-weight: 600;
      color: var(--app-danger-fg);
    }
  `,
})
export class ChannelList {
  private readonly api = inject(ChannelsApi);
  private readonly dialog = inject(MatDialog);
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
      .open<AccountDialog, AccountDialogData, AccountView>(AccountDialog, { data: { account }, width: '560px' })
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
      .open<CredentialsDialog, AccountDialogData, AccountView>(CredentialsDialog, { data: { account }, width: '480px' })
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
