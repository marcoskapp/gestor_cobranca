import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { Observable, catchError, debounceTime, map, of, startWith, throwError } from 'rxjs';
import { Permission } from '../../../../core/auth/permission';
import { toApiError, userMessage } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { DocumentDisplay } from '../../../../shared/components/document-display/document-display';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { pagedQuery } from '../../../../shared/data/paged-query';
import { HasPermission } from '../../../../shared/directives/has-permission';
import { PageRequest, PageResponse, emptyPage } from '../../../../shared/models/page';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { isValidDocument, onlyDigits } from '../../../../shared/validation/document';
import { CreditorLookup, PortfolioLookup } from '../../../organization/data/organization.lookups';
import { DebtorsApi } from '../../data/debt.api';
import { PERSON_TYPE_LABELS } from '../../data/debt.labels';
import { DebtorView } from '../../data/debt.models';
import { DebtorForm } from '../debtor-form/debtor-form';

/** Busca por nome (GET com filtros) ou por documento completo (POST, fora da URL). */
export type DebtorQuery =
  | { mode: 'name'; q: string | null; creditorId: string | null; portfolioId: string | null }
  | { mode: 'document'; document: string }
  | { mode: 'incomplete' };

/**
 * Interpreta o texto digitado: só dígitos/pontuação de documento → busca por documento (exige
 * CPF/CNPJ completo e válido, para nunca mandar documento parcial na URL); senão → nome.
 */
export function parseDebtorQuery(text: string, creditorId: string | null, portfolioId: string | null): DebtorQuery {
  const value = text.trim();
  if (value && /^[\d.\-/\s]+$/.test(value)) {
    const digits = onlyDigits(value);
    return (digits.length === 11 || digits.length === 14) && isValidDocument(digits)
      ? { mode: 'document', document: digits }
      : { mode: 'incomplete' };
  }
  return { mode: 'name', q: value || null, creditorId, portfolioId };
}

@Component({
  selector: 'app-debtor-list',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatTooltipModule,
    DataTable,
    CellDef,
    EntityPicker,
    PageHeader,
    StatusChip,
    DocumentDisplay,
    HasPermission,
    AppDatePipe,
  ],
  template: `
    <app-page-header title="Devedores" subtitle="Pessoas e empresas com títulos em cobrança nas carteiras do seu escopo.">
      <button mat-flat-button type="button" *appHasPermission="permission.DEBTOR_MANAGE" (click)="create()">
        <mat-icon aria-hidden="true">person_add</mat-icon>
        Cadastrar devedor
      </button>
    </app-page-header>

    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Buscar devedores" (ngSubmit)="query.reload()">
      <mat-form-field class="app-filters__wide app-compact">
        <mat-label>Nome, CPF ou CNPJ</mat-label>
        <input matInput formControlName="q" type="search" autocomplete="off" />
        <mat-icon matSuffix aria-hidden="true">search</mat-icon>
        @if (searchMode() === 'incomplete') {
          <mat-hint class="app-hint-warning">Informe o CPF ou CNPJ completo e válido.</mat-hint>
        } @else if (searchMode() === 'document') {
          <mat-hint>Busca exata pelo documento.</mat-hint>
        }
      </mat-form-field>
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.portfolio" [source]="portfolioSource" label="Carteira" [single]="true" />
      </div>
    </form>

    <app-data-table
      caption="Devedores"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="searchMode() === 'name' ? query.tablePage() : null"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      [emptyMessage]="emptyMessage()"
      emptyIcon="person_search"
      [emptyHint]="emptyHint()"
    >
      <ng-template appCell="name" [appCellOf]="query.rows()" let-d>
        <a [routerLink]="['/devedores', d.id]">{{ d.name }}</a>
      </ng-template>
      <ng-template appCell="maskedDocument" [appCellOf]="query.rows()" let-d>
        <app-document [value]="d.maskedDocument" />
      </ng-template>
      <ng-template appCell="personType" [appCellOf]="query.rows()" let-d>{{ personTypes[d.personType] }}</ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-d><app-status-chip domain="debtor" [status]="d.status" /></ng-template>
      <ng-template appCell="createdAt" [appCellOf]="query.rows()" let-d>{{ d.createdAt | appDate: 'date' }}</ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-d>
        <a mat-icon-button [routerLink]="['/devedores', d.id]" [attr.aria-label]="'Abrir ficha de ' + d.name" matTooltip="Abrir ficha">
          <mat-icon aria-hidden="true">chevron_right</mat-icon>
        </a>
      </ng-template>
    </app-data-table>
  `,
})
export class DebtorList {
  private readonly api = inject(DebtorsApi);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);

  protected readonly permission = Permission;
  protected readonly personTypes = PERSON_TYPE_LABELS;

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({
    q: [''],
    creditor: this.fb.control<string[]>([]),
    portfolio: this.fb.control<string[]>([]),
  });
  protected readonly portfolioSource = inject(PortfolioLookup).source(() => this.filters.controls.creditor.value[0] ?? null);

  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(300),
      map((): DebtorQuery => {
        const v = this.filters.getRawValue();
        return parseDebtorQuery(v.q, v.creditor[0] ?? null, v.portfolio[0] ?? null);
      }),
    ),
    { initialValue: { mode: 'name', q: null, creditorId: null, portfolioId: null } as DebtorQuery },
  );
  protected readonly searchMode = computed(() => this.filter().mode);
  protected readonly query = pagedQuery(this.filter, (filter, page) => this.load(filter, page));

  protected readonly emptyMessage = computed(() =>
    this.searchMode() === 'incomplete'
      ? 'Digite o CPF ou CNPJ completo para buscar pelo documento.'
      : this.searchMode() === 'document'
        ? 'Nenhum devedor com este documento no seu escopo.'
        : 'Nenhum devedor encontrado no seu escopo.',
  );

  protected readonly emptyHint = computed(() =>
    this.searchMode() === 'name' ? 'Confira a grafia ou busque pelo CPF/CNPJ completo.' : undefined,
  );

  protected readonly columns: TableColumn[] = [
    { key: 'name', header: 'Nome' },
    { key: 'maskedDocument', header: 'CPF/CNPJ' },
    { key: 'personType', header: 'Tipo' },
    { key: 'status', header: 'Situação' },
    { key: 'createdAt', header: 'Cadastrado em' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected create(): void {
    this.dialog
      .open<DebtorForm, DebtorView | null, DebtorView>(DebtorForm, { data: null, width: '640px' })
      .afterClosed()
      .subscribe((created) => created && void this.router.navigate(['/devedores', created.id]));
  }

  private load(filter: DebtorQuery, page: PageRequest): Observable<PageResponse<DebtorView>> {
    switch (filter.mode) {
      case 'incomplete':
        return of(emptyPage<DebtorView>(page.size));
      case 'document':
        return this.api.lookup(filter.document).pipe(
          map((debtor) => ({ content: [debtor], page: 0, size: page.size, totalElements: 1, totalPages: 1 })),
          catchError((error: unknown) => {
            if (error instanceof HttpErrorResponse && error.status === 404) {
              return of(emptyPage<DebtorView>(page.size));
            }
            if (!(error instanceof HttpErrorResponse) || error.status !== 401) {
              this.notifications.error(userMessage(toApiError(error)));
            }
            return throwError(() => error);
          }),
        );
      default:
        return this.api.search({ q: filter.q, creditorId: filter.creditorId, portfolioId: filter.portfolioId }, page);
    }
  }
}
