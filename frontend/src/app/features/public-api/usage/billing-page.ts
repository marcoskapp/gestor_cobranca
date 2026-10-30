import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { debounceTime, finalize, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { valueOr } from '../../../shared/data/resource-value';
import { sameValue } from '../../../shared/data/same-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { PublicApiUsageApi } from '../data/public-api.api';
import { PUBLIC_API_ERROR_LABELS, formatBytes } from '../data/public-api.labels';
import { PricePlanView, StatementView } from '../data/public-api.models';
import { PricePlanDialog, PricePlanDialogData } from './price-plan-dialog';
import { StatementViewPanel } from './statement-view';

function previousMonth(): string {
  const now = new Date();
  const prev = new Date(now.getFullYear(), now.getMonth() - 1, 1);
  return `${prev.getFullYear()}-${String(prev.getMonth() + 1).padStart(2, '0')}`;
}

/**
 * Faturamento da API pública (modelo pronto para faturar, sem cobrança): tabelas de preço versionadas e extratos
 * mensais por credor, com prévia, fechamento (o mês anterior fecha sozinho no dia 1), revisão com motivo e exportação.
 */
@Component({
  selector: 'app-public-api-billing',
  imports: [ReactiveFormsModule, ...FORM_FIELD, Amount, AppDatePipe, CellDef, DataTable, EntityPicker, Icon, PageHeader, StatementViewPanel],
  template: `
    <app-page-header title="Extratos da API pública" subtitle="Tabela de preços por versão e extrato mensal de consumo por credor, reprodutível: o mesmo consumo e a mesma tabela geram sempre o mesmo valor." />

    <section class="app-card" aria-labelledby="bp-plans" data-tour="billing-plans">
      <div class="bp-head">
        <h2 class="app-card__title" id="bp-plans">Tabelas de preço</h2>
        @if (canManage()) {
          <button class="btn btn-sm btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="newPlan()">
            <app-icon name="plus-lg" />
            Nova versão
          </button>
        }
      </div>
      @if (!plans().length) {
        <p class="app-muted">Nenhuma tabela cadastrada. Sem tabela vigente, o extrato mostra o consumo com valor zero.</p>
      } @else {
        <div class="table-responsive">
          <table class="table table-sm mb-0">
            <caption class="visually-hidden">Versões das tabelas de preço</caption>
            <thead><tr><th scope="col">Código</th><th scope="col">Credor</th><th scope="col">Vigência</th><th scope="col" class="text-end">Mensalidade</th><th scope="col">Franquias (req. · MB in/out · reg.)</th><th scope="col">Situação</th><th scope="col"><span class="visually-hidden">Ações</span></th></tr></thead>
            <tbody>
              @for (p of plans(); track p.id) {
                <tr>
                  <td><span class="app-mono">{{ p.code }}</span> v{{ p.version }}<div class="app-muted bp-small">{{ p.name }}</div></td>
                  <td>{{ p.creditorId ? creditors.name(p.creditorId) : 'Padrão da assessoria' }}</td>
                  <td>{{ p.effectiveFrom | appDate }}</td>
                  <td class="text-end"><app-amount [value]="p.monthlyFee" /></td>
                  <td class="bp-small">{{ n(p.includedRequests) }} · {{ n(p.includedMbIn) }}/{{ n(p.includedMbOut) }} · {{ n(p.includedRecords) }}</td>
                  <td>{{ p.status === 'PUBLISHED' ? 'Publicada' : 'Rascunho' }}</td>
                  <td class="text-end text-nowrap">
                    @if (canManage() && p.status === 'DRAFT') {
                      <button class="btn btn-sm btn-link" type="button" (click)="editPlan(p)">Editar</button>
                      <button class="btn btn-sm btn-link" type="button" (click)="publish(p)">Publicar</button>
                    } @else if (canManage()) {
                      <button class="btn btn-sm btn-link" type="button" (click)="newVersion(p)">Nova versão</button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>

    <section class="app-card" aria-labelledby="bp-preview" data-tour="billing-preview">
      <h2 class="app-card__title" id="bp-preview">Prévia e fechamento</h2>
      <form class="app-filters" [formGroup]="previewForm" aria-label="Prévia do extrato">
        <div class="app-filters__wide">
          <app-entity-picker [control]="previewForm.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
        </div>
        <app-field label="Mês"><input appInput type="month" formControlName="month" required /></app-field>
        <div class="app-filters__actions">
          <button class="btn btn-outline-primary" type="button" (click)="loadPreview()" [disabled]="!previewForm.controls.creditor.value.length">Calcular prévia</button>
          @if (canManage() && preview()) {
            <button class="btn btn-primary" type="button" (click)="close()" [disabled]="busy()">Fechar mês</button>
          }
        </div>
      </form>
      @if (preview(); as s) {
        <app-statement-view [statement]="s" [creditorName]="creditors.name(s.creditorId)" />
      }
    </section>

    <section class="app-card" aria-labelledby="bp-statements">
      <h2 class="app-card__title" id="bp-statements">Extratos fechados</h2>
      <form class="app-filters" [formGroup]="filters" role="search" aria-label="Filtrar extratos">
        <div class="app-filters__wide">
          <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
        </div>
        <app-field label="Mês"><input appInput type="month" formControlName="month" /></app-field>
      </form>
      <app-data-table
        caption="Extratos fechados"
        [rows]="query.rows()"
        [columns]="columns"
        [loading]="query.loading()"
        [failed]="query.failed()"
        [page]="query.tablePage()"
        (pageChange)="query.setPage($event)"
        (retry)="query.reload()"
        emptyIcon="receipt"
        emptyMessage="Nenhum extrato fechado."
        emptyHint="O mês anterior é fechado automaticamente no dia 1."
      >
        <ng-template appCell="period" [appCellOf]="query.rows()" let-s>{{ s.periodStart.slice(5, 7) }}/{{ s.periodStart.slice(0, 4) }}<div class="app-muted bp-small">revisão {{ s.revision }}</div></ng-template>
        <ng-template appCell="creditor" [appCellOf]="query.rows()" let-s>{{ creditors.name(s.creditorId) }}</ng-template>
        <ng-template appCell="usage" [appCellOf]="query.rows()" let-s><span class="bp-small">{{ n(s.billableRequests) }} req. · {{ bytes(s.bytesIn + s.bytesOut) }} · {{ n(s.records) }} reg.</span></ng-template>
        <ng-template appCell="plan" [appCellOf]="query.rows()" let-s>{{ s.pricePlanCode ? s.pricePlanCode + ' v' + s.pricePlanVersion : 'Sem tabela' }}</ng-template>
        <ng-template appCell="total" [appCellOf]="query.rows()" let-s><app-amount [value]="s.totalAmount" /></ng-template>
        <ng-template appCell="actions" [appCellOf]="query.rows()" let-s>
          <button class="btn btn-sm btn-link" type="button" (click)="selected.set(s)">Detalhar</button>
          @if (canExport()) {
            <button class="btn btn-sm btn-link" type="button" (click)="exportStatement(s)" aria-label="Exportar CSV"><app-icon name="download" /></button>
          }
        </ng-template>
      </app-data-table>
      @if (selected(); as s) {
        <app-statement-view [statement]="s" [creditorName]="creditors.name(s.creditorId)" />
      }
    </section>

  `,
  styles: `
    .bp-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 8px;
      margin-bottom: 8px;
    }
    .bp-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class BillingPage {
  private readonly api = inject(PublicApiUsageApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.PUBLIC_API_BILLING_MANAGE));
  protected readonly canExport = computed(() => this.auth.hasPermission(Permission.REPORT_EXPORT));
  protected readonly busy = signal(false);
  protected readonly preview = signal<StatementView | null>(null);
  protected readonly selected = signal<StatementView | null>(null);

  private readonly plansResource = rxResource({ stream: () => this.api.plans() });
  protected readonly plans = valueOr(this.plansResource, []);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly previewForm = this.fb.group({ creditor: this.fb.control<string[]>([]), month: [previousMonth()] });
  protected readonly filters = this.fb.group({ creditor: this.fb.control<string[]>([]), month: [''] });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(startWith(null), debounceTime(250), map(() => this.current())),
    { initialValue: this.current(), equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.statements(f.creditorId, f.month, page));
  protected readonly creditors = nameMap(inject(CreditorNames), () => [
    ...this.query.rows().map((s) => s.creditorId),
    ...this.plans().map((p) => p.creditorId).filter((v): v is string => !!v),
    ...(this.preview() ? [this.preview()!.creditorId] : []),
  ]);

  protected readonly columns: TableColumn[] = [
    { key: 'period', header: 'Mês' },
    { key: 'creditor', header: 'Credor' },
    { key: 'usage', header: 'Consumo cobrável' },
    { key: 'plan', header: 'Tabela' },
    { key: 'total', header: 'Total', align: 'end' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected n(value: number): string {
    return new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 6 }).format(value);
  }

  protected bytes(value: number): string {
    return formatBytes(value);
  }

  protected newPlan(): void {
    this.openPlan({});
  }

  protected newVersion(base: PricePlanView): void {
    this.openPlan({ base });
  }

  protected editPlan(plan: PricePlanView): void {
    this.openPlan({ plan });
  }

  protected publish(plan: PricePlanView): void {
    this.confirm
      .confirm({
        title: 'Publicar tabela de preços',
        message: 'Publicada, a versão não muda mais. Extratos de meses a partir da vigência passam a usá-la.',
        confirmLabel: 'Publicar',
        details: [
          { label: 'Tabela', value: `${plan.code} v${plan.version}` },
          { label: 'Vigência', value: plan.effectiveFrom.split('-').reverse().join('/') },
        ],
      })
      .subscribe(() =>
        this.api.publishPlan(plan.id).subscribe(() => {
          this.notifications.success('Tabela publicada.');
          this.plansResource.reload();
        }),
      );
  }

  protected loadPreview(): void {
    const v = this.previewForm.getRawValue();
    if (!v.creditor.length || !v.month) {
      return;
    }
    this.api.preview(v.creditor[0], v.month).subscribe((s) => this.preview.set(s));
  }

  protected close(): void {
    const v = this.previewForm.getRawValue();
    const existing = this.query.rows().some((s) => s.creditorId === v.creditor[0] && s.periodStart.startsWith(v.month));
    this.confirm
      .confirm({
        title: existing ? 'Gerar nova revisão do extrato' : 'Fechar o mês',
        message: existing
          ? 'O extrato deste mês já foi fechado. Uma nova revisão é gravada (a anterior permanece), com o motivo.'
          : 'O extrato é gravado e não muda mais. Novos fechamentos do mesmo mês geram revisões com motivo.',
        confirmLabel: existing ? 'Gerar revisão' : 'Fechar mês',
        reason: existing ? 'required' : 'optional',
        reasonLabel: 'Motivo',
        reasonMaxLength: 500,
      })
      .subscribe((reason) => {
        this.busy.set(true);
        this.api
          .close(v.creditor[0], v.month, reason || null)
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe({
            next: (s) => {
              this.notifications.success(`Extrato fechado (revisão ${s.revision}).`);
              this.preview.set(null);
              this.selected.set(s);
              this.query.reload();
            },
            error: (err: unknown) => {
              const e = toApiError(err);
              this.notifications.error((e.code && PUBLIC_API_ERROR_LABELS[e.code]) || userMessage(e));
            },
          });
      });
  }

  protected exportStatement(s: StatementView): void {
    if (s.id) {
      this.api.exportStatement(s.id).subscribe(() => this.notifications.info('Extrato exportado (registrado no log de acesso).'));
    }
  }

  private openPlan(data: PricePlanDialogData): void {
    this.dialog
      .open<PricePlanDialog, PricePlanDialogData, PricePlanView>(PricePlanDialog, { data, size: 'lg' })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success('Rascunho salvo. Publique para valer nos extratos.');
          this.plansResource.reload();
        }
      });
  }

  private current(): { creditorId: string | null; month: string | null } {
    const v = this.filters.getRawValue();
    return { creditorId: v.creditor[0] ?? null, month: v.month || null };
  }
}
