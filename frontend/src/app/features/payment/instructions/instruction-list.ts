import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { debounceTime, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../shared/status/status';
import { DebtorLookup, DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { PaymentLauncher } from '../data/payment-launcher';
import { InstructionsApi } from '../data/payment.api';
import { INSTRUCTION_TYPE_LABELS } from '../data/payment.labels';
import { InstructionFilter } from '../data/payment.models';
import { expirationText, instructionTarget } from '../data/payment.rules';

/** Instruções de pagamento (Pix) geradas, com a situação no PSP e a validade. */
@Component({
  selector: 'app-instruction-list',
  imports: [ReactiveFormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatSelectModule, MatTooltipModule, Amount, AppDatePipe, CellDef, DataTable, EntityPicker, PageHeader, StatusChip],
  template: `
    <app-page-header title="Instruções de pagamento" subtitle="Cobranças Pix enviadas aos devedores: QR Code, validade e se já foram pagas.">
      @if (canCreate()) {
        <button mat-flat-button type="button" (click)="create()">
          <mat-icon aria-hidden="true">qr_code_2</mat-icon>
          Gerar Pix
        </button>
      }
    </app-page-header>
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar instruções">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      @if (canSearchDebtors()) {
        <div class="app-filters__wide">
          <app-entity-picker [control]="filters.controls.debtor" [source]="debtorSource" label="Devedor" [single]="true" />
        </div>
      }
      <mat-form-field class="app-compact">
        <mat-label>Situação</mat-label>
        <mat-select formControlName="status">
          <mat-option value="">Todas</mat-option>
          @for (s of statuses; track s.value) {
            <mat-option [value]="s.value">{{ s.label }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
    </form>
    <app-data-table
      caption="Instruções de pagamento"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="qr_code_2"
      emptyMessage="Nenhuma instrução encontrada."
      emptyHint="Gere o Pix pela ficha do acordo, pela estação de cobrança ou aqui."
    >
      @if (canCreate()) {
        <button mat-stroked-button appEmptyAction type="button" (click)="create()">Gerar Pix</button>
      }
      <ng-template appCell="createdAt" [appCellOf]="query.rows()" let-i>{{ i.createdAt | appDate }}</ng-template>
      <ng-template appCell="debtor" [appCellOf]="query.rows()" let-i>
        {{ debtors.name(i.debtorId) }}
        <div class="app-muted il-small">{{ creditors.name(i.creditorId) }}</div>
      </ng-template>
      <ng-template appCell="target" [appCellOf]="query.rows()" let-i>
        @if (i.agreementId && canViewAgreements()) {
          <a [routerLink]="['/negociacao/acordos', i.agreementId]">{{ target(i) }}</a>
        } @else {
          {{ target(i) }}
        }
        <div class="app-muted il-small">{{ types[i.type] }}</div>
      </ng-template>
      <ng-template appCell="amount" [appCellOf]="query.rows()" let-i><app-amount [value]="i.amount" /></ng-template>
      <ng-template appCell="expiresAt" [appCellOf]="query.rows()" let-i>
        {{ i.expiresAt ? (i.expiresAt | appDate) : '—' }}
        <div class="app-muted il-small">{{ expiry(i) }}</div>
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-i><app-status-chip domain="paymentInstruction" [status]="i.status" /></ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-i>
        <a mat-icon-button [routerLink]="['/pagamentos/instrucoes', i.id]" aria-label="Abrir instrução" matTooltip="Abrir">
          <mat-icon aria-hidden="true">chevron_right</mat-icon>
        </a>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .il-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class InstructionList {
  private readonly api = inject(InstructionsApi);
  private readonly auth = inject(AuthService);
  private readonly launcher = inject(PaymentLauncher);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly debtorSource = inject(DebtorLookup);
  protected readonly statuses = statusOptions('paymentInstruction');
  protected readonly types = INSTRUCTION_TYPE_LABELS;
  protected readonly target = instructionTarget;
  protected readonly canCreate = computed(() => this.auth.hasPermission(Permission.PAYMENT_INSTRUCTION_CREATE));
  protected readonly canSearchDebtors = computed(() => this.auth.hasPermission(Permission.DEBTOR_VIEW));
  protected readonly canViewAgreements = computed(() => this.auth.hasPermission(Permission.AGREEMENT_VIEW));

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ creditor: this.fb.control<string[]>([]), debtor: this.fb.control<string[]>([]), status: [''] });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map((): InstructionFilter => {
        const v = this.filters.getRawValue();
        return { creditorId: v.creditor[0] ?? null, debtorId: v.debtor[0] ?? null, agreementId: null, titleId: null, status: v.status || null };
      }),
    ),
    { initialValue: { creditorId: null, debtorId: null, agreementId: null, titleId: null, status: null } as InstructionFilter, equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.search(f, page));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((i) => i.creditorId));
  protected readonly debtors = nameMap(inject(DebtorNames), () => this.query.rows().map((i) => i.debtorId), { batch: 100 });

  protected readonly columns: TableColumn[] = [
    { key: 'createdAt', header: 'Gerada em' },
    { key: 'debtor', header: 'Devedor e credor' },
    { key: 'target', header: 'Destino' },
    { key: 'amount', header: 'Valor', align: 'end' },
    { key: 'expiresAt', header: 'Validade' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected expiry(i: Parameters<typeof expirationText>[0]): string {
    return expirationText(i);
  }

  protected create(): void {
    this.launcher.instruction({}).subscribe((created) => {
      if (created) {
        this.query.reload();
      }
    });
  }
}
