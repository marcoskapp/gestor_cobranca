import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { catchError, debounceTime, finalize, map, of, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { ContractsApi } from '../../organization/data/organization.api';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { ContractView } from '../../organization/data/organization.models';
import { CommissionRulesApi } from '../data/settlement.api';
import { SETTLEMENT_ERROR_LABELS } from '../data/settlement.labels';
import { RuleSetView } from '../data/settlement.models';
import { cloneRules, emptyRules, periodText } from '../data/settlement.rules';

/**
 * Versões das regras de comissão da assessoria por contrato de serviço. O rascunho nasce da
 * versão publicada mais recente do contrato (ou do modelo inicial) e é publicado por outra pessoa.
 */
@Component({
  selector: 'app-rule-set-list',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatSelectModule, MatTooltipModule, AppDatePipe, CellDef, DataTable, EntityPicker, PageHeader, StatusChip],
  template: `
    <app-page-header title="Regras de comissão" subtitle="Quanto a assessoria recebe do credor: percentuais por carteira, atraso e forma de pagamento, valor fixo e honorários, por contrato de serviço.">
      @if (canManage()) {
        <button mat-flat-button type="button" (click)="createDraft()" [disabled]="!contractId() || creating() || hasDraft()" [matTooltip]="createHint()" data-tour="rules-new">
          <mat-icon aria-hidden="true">add</mat-icon>
          Novo rascunho
        </button>
      }
    </app-page-header>
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar regras de comissão">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <mat-form-field class="app-compact">
        <mat-label>Contrato de serviço</mat-label>
        <mat-select formControlName="contract">
          <mat-option value="">Todos</mat-option>
          @for (c of contracts(); track c.id) {
            <mat-option [value]="c.id">{{ contractLabel(c) }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
    </form>
    <p class="app-alert app-alert--info" role="note" data-tour="rules-segregation">
      <mat-icon aria-hidden="true">group</mat-icon>
      <span>Transcrever um contrato é ponto sensível: <strong>quem editou as regras por último não publica</strong>. Versões publicadas não mudam; recebimentos já apurados nunca trocam de regra.</span>
    </p>
    <app-data-table
      caption="Versões das regras de comissão"
      [rows]="rows()"
      [columns]="columns"
      [loading]="list.isLoading()"
      [failed]="list.status() === 'error'"
      (retry)="list.reload()"
      emptyIcon="percent"
      emptyMessage="Nenhuma versão de regras encontrada."
      emptyHint="Escolha um credor e um contrato e crie o primeiro rascunho a partir do modelo."
    >
      <ng-template appCell="contract" [appCellOf]="rows()" let-r>
        {{ contractName(r.contractId) }}
        <div class="app-muted rl-small">{{ creditors.name(r.creditorId) }}</div>
      </ng-template>
      <ng-template appCell="version" [appCellOf]="rows()" let-r>v{{ r.ruleVersion }}</ng-template>
      <ng-template appCell="status" [appCellOf]="rows()" let-r><app-status-chip domain="commissionRuleSet" [status]="r.status" /></ng-template>
      <ng-template appCell="period" [appCellOf]="rows()" let-r>
        @if (r.effectiveFrom) {
          {{ r.effectiveTo ? period(r.effectiveFrom, r.effectiveTo) : 'a partir de ' + (r.effectiveFrom | appDate: 'date') }}
        } @else {
          <span class="app-muted">não publicada</span>
        }
      </ng-template>
      <ng-template appCell="edited" [appCellOf]="rows()" let-r>
        {{ userName(r.rulesEditedBy) }}
        <div class="app-muted rl-small">{{ r.rulesEditedAt | appDate }}</div>
      </ng-template>
      <ng-template appCell="published" [appCellOf]="rows()" let-r>
        @if (r.publishedBy) {
          {{ userName(r.publishedBy) }}
          <div class="app-muted rl-small">{{ r.publishedAt | appDate }}</div>
        } @else {
          <span class="app-muted">—</span>
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="rows()" let-r>
        <a mat-icon-button [routerLink]="['/prestacao-de-contas/regras', r.id]" [attr.aria-label]="'Abrir versão ' + r.ruleVersion" matTooltip="Abrir">
          <mat-icon aria-hidden="true">chevron_right</mat-icon>
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .rl-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class RuleSetList {
  private readonly api = inject(CommissionRulesApi);
  private readonly contractsApi = inject(ContractsApi);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.COMMISSION_MANAGE));
  protected readonly creating = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ creditor: this.fb.control<string[]>([]), contract: [''] });
  private readonly value = toSignal(this.filters.valueChanges.pipe(startWith(null), debounceTime(150), map(() => this.filters.getRawValue())), {
    initialValue: this.filters.getRawValue(),
  });
  protected readonly creditorId = computed(() => this.value().creditor[0] ?? null);
  protected readonly contractId = computed(() => this.value().contract || null);

  private readonly contractList = rxResource({
    params: () => this.creditorId() ?? undefined,
    stream: ({ params }) => this.contractsApi.listByCreditor(params).pipe(catchError(() => of<ContractView[]>([]))),
  });
  protected readonly contracts = valueOr(this.contractList, []);

  protected readonly list = rxResource({
    params: () => ({ creditorId: this.creditorId(), contractId: this.contractId() }),
    stream: ({ params }) => this.api.list(params.creditorId, params.contractId),
  });
  protected readonly rows = valueOr(this.list, []);
  protected readonly hasDraft = computed(() => !!this.contractId() && this.rows().some((r) => r.contractId === this.contractId() && r.status === 'DRAFT'));
  protected readonly createHint = computed(() =>
    !this.contractId() ? 'Escolha o credor e o contrato de serviço' : this.hasDraft() ? 'Já existe um rascunho para este contrato' : 'Cria a próxima versão a partir da última publicada',
  );

  protected readonly creditors = nameMap(inject(CreditorNames), () => this.rows().map((r) => r.creditorId));
  protected readonly users = nameMap(inject(UserNames), () => this.rows().flatMap((r) => [r.rulesEditedBy, r.publishedBy]), { batch: USER_NAMES_BATCH });

  protected readonly columns: TableColumn[] = [
    { key: 'contract', header: 'Contrato e credor' },
    { key: 'version', header: 'Versão' },
    { key: 'status', header: 'Situação' },
    { key: 'period', header: 'Vigência' },
    { key: 'edited', header: 'Editado por' },
    { key: 'published', header: 'Publicado por' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected readonly period = periodText;

  protected contractLabel(c: ContractView): string {
    return `${c.number} · fecha dia ${c.settlementDay}${c.status === 'TERMINATED' ? ' (encerrado)' : ''}`;
  }

  protected contractName(id: string): string {
    const c = this.contracts().find((x) => x.id === id);
    return c ? `Contrato ${c.number}` : `Contrato ${id.slice(0, 8)}`;
  }

  protected userName(id: string | null): string {
    if (!id) {
      return '—';
    }
    return id === this.auth.user()?.id ? 'Você' : this.users.name(id);
  }

  protected createDraft(): void {
    const contractId = this.contractId();
    if (!contractId || this.creating()) {
      return;
    }
    const latest = this.rows()
      .filter((r: RuleSetView) => r.contractId === contractId && r.status === 'PUBLISHED')
      .sort((a, b) => b.ruleVersion - a.ruleVersion)[0];
    this.creating.set(true);
    this.api
      .create({ contractId, rules: latest ? cloneRules(latest.rules) : emptyRules(), notes: null })
      .pipe(finalize(() => this.creating.set(false)))
      .subscribe({
        next: (created) => {
          this.notifications.success(`Rascunho da versão ${created.ruleVersion} criado${latest ? ` a partir da versão ${latest.ruleVersion}` : ' com o modelo inicial'}.`);
          void this.router.navigate(['/prestacao-de-contas/regras', created.id]);
        },
        error: (err: unknown) => {
          const e = toApiError(err);
          this.notifications.error((e.code && SETTLEMENT_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }
}
