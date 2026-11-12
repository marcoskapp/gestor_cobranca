import { FilterBar } from '../../../../shared/components/filter-bar/filter-bar';
import { activeFilterCount } from '../../../../shared/data/filter-count';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Observable, catchError, debounceTime, map, of, startWith, throwError } from 'rxjs';
import { Permission } from '../../../../core/auth/permission';
import { AuthService } from '../../../../core/auth/auth.service';
import { toApiError, userMessage } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../../shared/components/page-header/page-header';
import { DocumentDisplay } from '../../../../shared/components/document-display/document-display';
import { StatusChip } from '../../../../shared/components/status-chip/status-chip';
import { sameValue } from '../../../../shared/data/same-value';
import { pagedQuery } from '../../../../shared/data/paged-query';
import { syncFormWithUrl, urlParam } from '../../../../shared/data/url-state';
import { HasPermission } from '../../../../shared/directives/has-permission';
import { PageRequest, PageResponse, emptyPage } from '../../../../shared/models/page';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { isValidDocument, onlyDigits } from '../../../../shared/validation/document';
import { CreditorLookup, PortfolioLookup } from '../../../organization/data/organization.lookups';
import { DebtorsApi } from '../../data/debt.api';
import { PERSON_TYPE_LABELS } from '../../data/debt.labels';
import { DebtorView } from '../../data/debt.models';
import { DebtorForm } from '../debtor-form/debtor-form';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { DialogService } from '../../../../shared/dialog/dialog.service';

const DOCUMENT_LIKE = /^[\d.\-/\s]+$/;

/** Busca por nome (GET com filtros) ou por documento completo (POST, fora da URL). */
export type DebtorQuery =
  | {
      mode: 'name';
      q: string | null;
      creditorId: string | null;
      portfolioId: string | null;
      /** Só devedores com contato novo ainda sem tentativa. */
      newContact: boolean;
      /** Devedores que receberam contato novo nesta carga (link do relatório da carga). */
      newContactBatchId: string | null;
    }
  | { mode: 'document'; document: string }
  | { mode: 'incomplete' }
  /** "Outras unidades da empresa" (`?mesmaEmpresa=<id do devedor>`): matriz e filiais pela raiz do CNPJ. */
  | { mode: 'company'; debtorId: string };

/**
 * Interpreta o texto digitado: só dígitos/pontuação de documento → busca por documento (exige
 * CPF/CNPJ completo e válido, para nunca mandar documento parcial na URL); senão → nome.
 */
export function parseDebtorQuery(
  text: string,
  creditorId: string | null,
  portfolioId: string | null,
  newContact = false,
  newContactBatchId: string | null = null,
): DebtorQuery {
  const value = text.trim();
  if (value && DOCUMENT_LIKE.test(value)) {
    const digits = onlyDigits(value);
    return (digits.length === 11 || digits.length === 14) && isValidDocument(digits)
      ? { mode: 'document', document: digits }
      : { mode: 'incomplete' };
  }
  return { mode: 'name', q: value || null, creditorId, portfolioId, newContact, newContactBatchId };
}

@Component({
  selector: 'app-debtor-list',
  styles: `
    .dl-toggles {
      display: flex;
      align-items: flex-end;
      padding-bottom: 6px;
    }
  `,
  imports: [FilterBar, ReactiveFormsModule, RouterLink, DataTable, CellDef, EntityPicker, PageHeader, StatusChip, DocumentDisplay, HasPermission, AppDatePipe, Icon, NgbTooltip, ...FORM_FIELD],
  template: `
    <app-page-header title="Devedores" subtitle="Pessoas e empresas com títulos em cobrança nas carteiras do seu escopo.">
      <button class="btn btn-primary" type="button" *appHasPermission="permission.DEBTOR_MANAGE" (click)="create()">
        <app-icon name="person-plus" />
        Cadastrar devedor
      </button>
    </app-page-header>

    @if (sameCompany(); as ref) {
      <div class="alert alert-info d-flex flex-wrap gap-2 align-items-center" role="status">
        <app-icon name="buildings" />
        <span>Outras unidades da empresa (matriz e filiais com a mesma raiz do CNPJ).</span>
        <a class="btn btn-link p-0" [routerLink]="['/devedores', ref]">Voltar para a ficha</a>
        <button class="btn btn-link p-0" type="button" (click)="clearSameCompany()">Ver todos os devedores</button>
      </div>
    }

    <app-filter-bar [activeCount]="activeFilters()">
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Buscar devedores" (ngSubmit)="query.reload()">
      <app-field class="app-filters__wide" label="Nome, CPF ou CNPJ" [hint]="searchHint()">
        <div class="input-group">
          <input appInput formControlName="q" type="search" autocomplete="off" />
          <span class="input-group-text" aria-hidden="true"><app-icon name="search" /></span>
        </div>
      </app-field>
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.portfolio" [source]="portfolioSource" label="Carteira" [single]="true" />
      </div>
      <div class="dl-toggles" data-tour="debtor-new-contact-filter">
        <div class="form-check form-switch">
          <input class="form-check-input" type="checkbox" role="switch" id="dl-new-contact" formControlName="newContact" aria-describedby="dl-new-contact-hint" />
          <label class="form-check-label" for="dl-new-contact">Com contato novo</label>
        </div>
        <span id="dl-new-contact-hint" class="visually-hidden">Contato recebido numa nova carga, ainda sem tentativa.</span>
      </div>
    </form>
    </app-filter-bar>

    @if (filters.controls.batch.value) {
      <div class="alert alert-info d-flex flex-wrap gap-2 align-items-center" role="status">
        <app-icon name="stars" />
        <span>Mostrando os devedores que receberam contato novo nesta carga.</span>
        @if (canSeeImports) {
          <a [routerLink]="['/importacao', filters.controls.batch.value]">Ver a carga</a>
        }
        <button type="button" class="btn btn-sm btn-outline-primary ms-auto" (click)="clearBatch()">Ver todos os devedores</button>
      </div>
    }

    <app-data-table
      caption="Devedores"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="searchMode() === 'name' || searchMode() === 'company' ? query.tablePage() : null"
      [sort]="query.sort()"
      (pageChange)="query.setPage($event)"
      (sortChange)="query.setSort($event)"
      (retry)="query.reload()"
      [emptyMessage]="emptyMessage()"
      emptyIcon="person-bounding-box"
      [emptyHint]="emptyHint()"
    >
      <ng-template appCell="name" [appCellOf]="query.rows()" let-d>
        <a [routerLink]="['/devedores', d.id]">{{ d.name }}</a>
        @if (d.establishment) {
          <small class="d-block app-muted">{{ d.tradeName ? d.tradeName + ' · ' : '' }}{{ d.establishment }}</small>
        }
      </ng-template>
      <ng-template appCell="maskedDocument" [appCellOf]="query.rows()" let-d>
        <app-document [value]="d.maskedDocument" />
      </ng-template>
      <ng-template appCell="personType" [appCellOf]="query.rows()" let-d>{{ personTypes[d.personType] }}</ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-d><app-status-chip domain="debtor" [status]="d.status" /></ng-template>
      <ng-template appCell="createdAt" [appCellOf]="query.rows()" let-d>{{ d.createdAt | appDate: 'date' }}</ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-d>
        <a class="btn btn-sm btn-outline-secondary border-0" [routerLink]="['/devedores', d.id]" [attr.aria-label]="'Abrir ficha de ' + d.name" ngbTooltip="Abrir ficha">
          <app-icon name="chevron-right" />
        </a>
      </ng-template>
    </app-data-table>
  `,
})
export class DebtorList {
  private readonly api = inject(DebtorsApi);
  private readonly dialog = inject(DialogService);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);

  protected readonly permission = Permission;
  protected readonly personTypes = PERSON_TYPE_LABELS;

  private readonly fb = inject(FormBuilder).nonNullable;
  /** Filtros na URL (`?credor=&carteira=&contato-novo=1&carga=`). A busca (nome, documento) nunca vai para a URL: é dado pessoal (ux.md §16). */
  protected readonly filters = syncFormWithUrl(
    this.fb.group({
      q: [''],
      creditor: this.fb.control<string[]>([]),
      portfolio: this.fb.control<string[]>([]),
      sameCompany: [''],
      newContact: [false],
      batch: [''],
    }),
    {
      creditor: urlParam.ids('credor'),
      portfolio: urlParam.ids('carteira'),
      sameCompany: urlParam.id('mesmaEmpresa'),
      newContact: urlParam.flag('contato-novo'),
      batch: urlParam.id('carga'),
    },
  );
  /** Devedor de referência de "Outras unidades da empresa" (só o id na URL, nunca o CNPJ). */
  protected readonly sameCompany = computed(() => (this.filter().mode === 'company' ? this.filters.controls.sameCompany.value : null));
  protected readonly canSeeImports = inject(AuthService).hasPermission(Permission.IMPORT_VIEW);
  protected readonly portfolioSource = inject(PortfolioLookup).source(() => this.filters.controls.creditor.value[0] ?? null);

  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(300),
      map(() => this.current()),
    ),
    { initialValue: this.current(), equal: sameValue },
  );
  protected readonly searchMode = computed(() => this.filter().mode);
  protected readonly searchHint = computed(() =>
    this.searchMode() === 'incomplete' ? 'Informe o CPF ou CNPJ completo e válido.' : this.searchMode() === 'document' ? 'Busca exata pelo documento.' : undefined,
  );
  protected readonly query = pagedQuery(this.filter, (filter, page) => this.load(filter, page), {
    url: true,
    sortFields: { name: 'name', createdAt: 'createdAt' },
  });

  protected readonly emptyMessage = computed(() =>
    this.searchMode() === 'incomplete'
      ? 'Digite o CPF ou CNPJ completo para buscar pelo documento.'
      : this.searchMode() === 'document'
        ? 'Nenhum devedor com este documento no seu escopo.'
        : this.searchMode() === 'company'
          ? 'Nenhuma outra unidade desta empresa no seu escopo.'
          : 'Nenhum devedor encontrado no seu escopo.',
  );

  protected readonly emptyHint = computed(() =>
    this.searchMode() === 'name' ? 'Confira a grafia ou busque pelo CPF/CNPJ completo.' : undefined,
  );

  protected readonly columns: TableColumn[] = [
    { key: 'name', header: 'Nome', sortable: true, mobile: 'title' },
    { key: 'maskedDocument', header: 'CPF/CNPJ', mobile: 'meta' },
    { key: 'personType', header: 'Tipo', mobile: 'hidden' },
    { key: 'status', header: 'Situação', mobile: 'badge' },
    { key: 'createdAt', header: 'Cadastrado em', sortable: true, mobile: 'meta' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  private current(): DebtorQuery {
    const v = this.filters.getRawValue();
    if (v.sameCompany && !v.q.trim()) {
      return { mode: 'company', debtorId: v.sameCompany };
    }
    return parseDebtorQuery(v.q, v.creditor[0] ?? null, v.portfolio[0] ?? null, v.newContact, v.batch || null);
  }

  /** Sai do filtro da carga (link do relatório) e volta à lista completa. */
  protected clearBatch(): void {
    this.filters.controls.batch.setValue('');
  }

  protected clearSameCompany(): void {
    this.filters.controls.sameCompany.setValue('');
  }

  protected create(): void {
    this.dialog
      .open<DebtorForm, DebtorView | null, DebtorView>(DebtorForm, { data: null, size: 'lg' })
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
      case 'company':
        return this.api.search({ sameCompanyAs: filter.debtorId }, page);
      default:
        return this.api.search(
          {
            q: filter.q,
            creditorId: filter.creditorId,
            portfolioId: filter.portfolioId,
            newContact: filter.newContact || null,
            newContactBatchId: filter.newContactBatchId,
          },
          page,
        );
    }
  }

  /** "Filtros (n)" do `app-filter-bar` no celular (ux.md §12.3). */
  protected readonly activeFilters = activeFilterCount(this.filters);
}
