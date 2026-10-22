import { ComponentType } from '@angular/cdk/portal';
import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../../core/auth/auth.service';
import { Permission } from '../../../../core/auth/permission';
import { NotificationService } from '../../../../core/notification/notification.service';
import { ConfirmService } from '../../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../../shared/data/resource-value';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { DocumentPipe } from '../../../../shared/pipes/document.pipe';
import { ContractForm, ContractFormData } from '../../contracts/contract-form/contract-form';
import { ContractTerminate } from '../../contracts/contract-terminate/contract-terminate';
import { ContractsApi, CreditorsApi } from '../../data/organization.api';
import { ContractView, CreditorView } from '../../data/organization.models';
import { CreditorForm } from '../creditor-form/creditor-form';

@Component({
  selector: 'app-creditor-detail',
  imports: [
    RouterLink,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
    PageHeader,
    StatusChip,
    DataTable,
    CellDef,
    AppDatePipe,
    DocumentPipe,
  ],
  templateUrl: './creditor-detail.html',
})
export class CreditorDetail {
  readonly id = input.required<string>();

  private readonly api = inject(CreditorsApi);
  private readonly contractsApi = inject(ContractsApi);
  private readonly dialog = inject(MatDialog);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly auth = inject(AuthService);

  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.CREDITOR_MANAGE));
  protected readonly canManageContracts = computed(() => this.auth.hasPermission(Permission.CONTRACT_MANAGE));
  protected readonly canViewPortfolios = computed(() => this.auth.hasPermission(Permission.PORTFOLIO_VIEW));

  protected readonly creditor = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.get(params) });
  protected readonly current = valueOr(this.creditor, undefined);
  protected readonly contracts = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.contractsApi.listByCreditor(params),
  });
  protected readonly contractRows = valueOr(this.contracts, []);

  protected readonly contractColumns: TableColumn[] = [
    { key: 'number', header: 'Número' },
    { key: 'startDate', header: 'Início' },
    { key: 'endDate', header: 'Fim' },
    { key: 'returnAfterDays', header: 'Devolução (dias)', align: 'end' },
    { key: 'settlementDay', header: 'Dia do repasse', align: 'end' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected edit(creditor: CreditorView): void {
    this.dialog
      .open<CreditorForm, CreditorView, CreditorView>(CreditorForm, { data: creditor, width: '640px' })
      .afterClosed()
      .subscribe((saved) => saved && this.creditor.set(saved));
  }

  protected setActive(creditor: CreditorView, active: boolean): void {
    this.confirm
      .confirm({
        title: active ? 'Ativar credor' : 'Inativar credor',
        message: active
          ? `Ativar o credor ${creditor.legalName}?`
          : `O credor ${creditor.legalName} deixará de aceitar novas carteiras.`,
        confirmLabel: active ? 'Ativar' : 'Inativar',
        destructive: !active,
      })
      .subscribe(() =>
        this.api.setActive(creditor.id, active).subscribe((updated) => {
          this.notifications.success(active ? 'Credor ativado.' : 'Credor inativado.');
          this.creditor.set(updated);
        }),
      );
  }

  protected newContract(): void {
    this.openContractDialog(ContractForm, { creditorId: this.id(), contract: null } satisfies ContractFormData);
  }

  protected editTerms(contract: ContractView): void {
    this.openContractDialog(ContractForm, { creditorId: this.id(), contract } satisfies ContractFormData);
  }

  protected terminate(contract: ContractView): void {
    this.openContractDialog(ContractTerminate, contract);
  }

  private openContractDialog<C, D>(component: ComponentType<C>, data: D): void {
    this.dialog
      .open<C, D, ContractView>(component, { data, width: '640px' })
      .afterClosed()
      .subscribe((saved) => saved && this.contracts.reload());
  }
}
