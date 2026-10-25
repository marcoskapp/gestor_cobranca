import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { CreditorLookup, CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { FormDialogService } from '../components/form-dialog';
import { LegalSetupApi } from '../data/legal.api';
import { RuleRequest, RuleView } from '../data/legal.models';
import { Icon } from '../../../shared/components/icon/icon';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';

/** Critérios da regra em texto curto (combinados por "e"). */
export function ruleCriteria(r: Pick<RuleView, 'minDaysOverdue' | 'minOpenBalance' | 'requireCollateral' | 'requireExecutiveTitle' | 'minBrokenAgreements' | 'prescriptionWithinDays'>): string {
  const parts: string[] = [];
  if (r.minDaysOverdue !== null) parts.push(`atraso ≥ ${r.minDaysOverdue} dias`);
  if (r.minOpenBalance !== null) parts.push(`saldo ≥ ${formatAmount(r.minOpenBalance)}`);
  if (r.requireCollateral) parts.push('com garantia');
  if (r.requireExecutiveTitle) parts.push('título executivo vigente');
  if (r.minBrokenAgreements !== null) parts.push(`${r.minBrokenAgreements}+ acordo(s) quebrado(s)`);
  if (r.prescriptionWithinDays !== null) parts.push(`prescrição em até ${r.prescriptionWithinDays} dias`);
  return parts.length ? parts.join(' e ') : 'Sem critérios';
}

/**
 * Regras de elegibilidade ao jurídico por credor (e carteira opcional). Critérios de uma regra
 * combinam por "e"; regras do mesmo credor somam-se por "ou".
 */
@Component({
  selector: 'app-eligibility-rules',
  imports: [NgbTooltip, Icon, Amount, CellDef, DataTable, PageHeader, StatusChip],
  template: `
    <app-page-header title="Regras de elegibilidade" subtitle="Quando um caso de cobrança é indicado ao jurídico. Critérios combinam por “e”; regras somam-se por “ou”.">
      @if (canManage()) {
        <button class="btn btn-primary" type="button" (click)="edit(null)"><app-icon name="plus-lg" /> Nova regra</button>
      }
    </app-page-header>
    <app-data-table
      caption="Regras de elegibilidade"
      [rows]="rows()"
      [columns]="columns"
      [loading]="rules.isLoading()"
      [failed]="rules.status() === 'error'"
      (retry)="rules.reload()"
      emptyIcon="list-check"
      emptyMessage="Nenhuma regra cadastrada."
      emptyHint="Sem regras, os casos chegam ao jurídico só por indicação manual ou alerta de prescrição."
    >
      <ng-template appCell="name" [appCellOf]="rows()" let-r>
        <strong>{{ r.name }}</strong>
        <div class="app-muted">{{ creditors.name(r.creditorId) }}{{ r.portfolioId ? ' · ' + portfolios.name(r.portfolioId) : '' }}</div>
      </ng-template>
      <ng-template appCell="criteria" [appCellOf]="rows()" let-r>{{ criteria(r) }}</ng-template>
      <ng-template appCell="balance" [appCellOf]="rows()" let-r>
        @if (r.minOpenBalance !== null) {
          <app-amount [value]="r.minOpenBalance" />
        } @else {
          —
        }
      </ng-template>
      <ng-template appCell="status" [appCellOf]="rows()" let-r><app-status-chip domain="active" [status]="r.active" /></ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-r>
        @if (canManage()) {
          <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="edit(r)" [attr.aria-label]="'Editar regra ' + r.name" ngbTooltip="Editar"><app-icon name="pencil" /></button>
          <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="toggle(r)" [attr.aria-label]="(r.active ? 'Desativar' : 'Ativar') + ' regra ' + r.name" [ngbTooltip]="r.active ? 'Desativar' : 'Ativar'">
            <app-icon [name]="r.active ? 'pause-circle' : 'play-circle'" />
          </button>
        }
      </ng-template>
    </app-data-table>
  `,
})
export class EligibilityRules {
  private readonly api = inject(LegalSetupApi);
  private readonly auth = inject(AuthService);
  private readonly forms = inject(FormDialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly creditorLookup = inject(CreditorLookup);
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.LEGAL_MANAGE));
  protected readonly criteria = ruleCriteria;

  protected readonly rules = rxResource({ stream: () => this.api.rules() });
  protected readonly rows = valueOr(this.rules, []);
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.rows().map((r) => r.creditorId));
  protected readonly portfolios = nameMap(inject(PortfolioNames), () => this.rows().map((r) => r.portfolioId));

  protected readonly columns: TableColumn[] = [
    { key: 'name', header: 'Regra' },
    { key: 'criteria', header: 'Critérios' },
    { key: 'balance', header: 'Saldo mínimo', align: 'end' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected edit(rule: RuleView | null): void {
    this.forms
      .open<RuleView>({
        title: rule ? 'Editar regra' : 'Nova regra de elegibilidade',
        message: rule ? 'Credor e carteira não mudam depois de criada a regra.' : 'Informe ao menos um critério. Títulos prescritos e contestados ficam fora conforme os parâmetros.',
        submitLabel: rule ? 'Salvar' : 'Cadastrar regra',
        fields: [
          ...(rule ? [] : [{ key: 'creditorId', label: 'Credor', type: 'entity' as const, required: true, source: this.creditorLookup }]),
          { key: 'name', label: 'Nome', type: 'text', required: true, maxLength: 120, value: rule?.name, wide: true },
          { key: 'minDaysOverdue', label: 'Atraso mínimo (dias)', type: 'number', min: 0, max: 36500, value: rule?.minDaysOverdue },
          { key: 'minOpenBalance', label: 'Saldo mínimo', type: 'money', min: 0, value: rule?.minOpenBalance },
          { key: 'minBrokenAgreements', label: 'Acordos quebrados (mínimo)', type: 'number', min: 1, max: 100, value: rule?.minBrokenAgreements },
          { key: 'prescriptionWithinDays', label: 'Prescrição em até (dias)', type: 'number', min: 1, max: 3650, value: rule?.prescriptionWithinDays },
          { key: 'requireCollateral', label: 'Exige garantia', type: 'checkbox', value: rule?.requireCollateral },
          { key: 'requireExecutiveTitle', label: 'Exige título executivo com pretensão vigente', type: 'checkbox', value: rule?.requireExecutiveTitle },
        ],
        submit: (v) => {
          const request = { ...v, creditorId: rule?.creditorId ?? v['creditorId'], portfolioId: rule?.portfolioId ?? null } as unknown as RuleRequest;
          return rule ? this.api.updateRule(rule.id, request) : this.api.createRule(request);
        },
      })
      .subscribe(() => {
        this.notifications.success(rule ? 'Regra atualizada.' : 'Regra cadastrada.');
        this.rules.reload();
      });
  }

  protected toggle(r: RuleView): void {
    this.confirm
      .confirm({
        title: r.active ? 'Desativar regra' : 'Ativar regra',
        message: r.active ? 'Novos casos deixam de ser indicados por esta regra; os já indicados continuam.' : 'A varredura passa a indicar os casos que atendem aos critérios.',
        confirmLabel: r.active ? 'Desativar' : 'Ativar',
        details: [
          { label: 'Regra', value: r.name },
          { label: 'Critérios', value: ruleCriteria(r) },
        ],
      })
      .subscribe(() =>
        this.api.setRuleActive(r.id, !r.active).subscribe(() => {
          this.notifications.success(r.active ? 'Regra desativada.' : 'Regra ativada.');
          this.rules.reload();
        }),
      );
  }
}
