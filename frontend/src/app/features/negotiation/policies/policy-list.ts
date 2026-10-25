import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { debounceTime, finalize, map, startWith, switchMap } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { toApiError } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { statusOptions } from '../../../shared/status/status';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { AppValidators } from '../../../shared/validation/validators';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { PoliciesApi } from '../data/negotiation.api';
import { PolicyView } from '../data/negotiation.models';

interface PolicyFilter {
  creditorId: string | null;
  status: string | null;
}

/** Primeira versão da política do credor, a partir do modelo de mercado. */
@Component({
  selector: 'app-policy-create',
  imports: [...FORM_FIELD, ...DIALOG_DIRECTIVES, ReactiveFormsModule, EntityPicker],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0" tabindex="-1" ngbAutofocus>Nova política de cobrança</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @for (e of errors(); track e) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert">{{ e }}</div>
        }
        <p class="app-dialog-message">
          A política começa como rascunho com o <strong>modelo de mercado</strong> (juros 1% a.m., multa 2%, honorários 10%, até 12 pagamentos,
          escada de 4 níveis). Ajuste os parâmetros às condições do credor antes de publicar.
        </p>
        <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor" [single]="true" hint="Cada credor tem uma única linha de versões." />
        @if (form.controls.creditor.touched && form.controls.creditor.invalid) {
          <div class="invalid-feedback d-block" role="alert">Escolha o credor.</div>
        }
        <app-field class="mb-3" label="Nome">
          <input appInput formControlName="name" maxlength="120" required />
        </app-field>
        <app-field class="mb-3" label="Descrição">
          <textarea appInput formControlName="description" rows="2" maxlength="1000"></textarea>
        </app-field>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Criar rascunho</button>
      </div>
    </form>
  `,
})
export class PolicyCreate {
  private readonly ref = inject<DialogRef<PolicyView>>(DialogRef);
  private readonly api = inject(PoliciesApi);
  protected readonly creditors = inject(CreditorLookup);
  protected readonly saving = signal(false);
  protected readonly errors = signal<string[]>([]);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    creditor: this.fb.control<string[]>([], [AppValidators.notBlank]),
    name: ['Política de cobrança e negociação', [AppValidators.notBlank, Validators.maxLength(120)]],
    description: ['', [Validators.maxLength(1000)]],
  });

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.api
      .template()
      .pipe(
        switchMap((terms) => this.api.create({ creditorId: v.creditor[0], name: v.name.trim(), description: v.description.trim() || null, terms })),
        finalize(() => this.saving.set(false)),
      )
      .subscribe({
        next: (created) => this.ref.close(created),
        error: (err: unknown) => this.errors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}

/** Políticas de cobrança e negociação por credor, com versões, vigência e situação. */
@Component({
  selector: 'app-policy-list',
  imports: [Icon, NgbTooltip, ...FORM_FIELD, ReactiveFormsModule, RouterLink, CellDef, DataTable, EntityPicker, PageHeader, StatusChip, AppDatePipe],
  template: `
    <app-page-header title="Políticas de cobrança" subtitle="Encargos, descontos máximos, parcelamento e escada de concessões de cada credor, em versões imutáveis após a publicação.">
      @if (canManage()) {
        <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" (click)="create()">
          <app-icon name="plus-lg" />
          Nova política
        </button>
      }
    </app-page-header>
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar políticas">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <app-field label="Situação">
        <select appInput formControlName="status">
          <option value="">Todas</option>
          @for (s of statuses; track s.value) {
            <option [ngValue]="s.value">{{ s.label }}</option>
          }
        </select>
      </app-field>
    </form>
    <app-data-table
      caption="Versões de políticas"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="shield-check"
      emptyMessage="Nenhuma política encontrada."
      emptyHint="Sem política publicada, o credor não pode ser negociado (simulações retornam erro)."
    >
      <ng-template appCell="creditor" [appCellOf]="query.rows()" let-p>{{ creditors.name(p.creditorId) }}</ng-template>
      <ng-template appCell="version" [appCellOf]="query.rows()" let-p>
        <a [routerLink]="['/negociacao/politicas', p.id]">v{{ p.version }} · {{ p.name }}</a>
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-p><app-status-chip domain="collectionPolicy" [status]="p.status" /></ng-template>
      <ng-template appCell="period" [appCellOf]="query.rows()" let-p>
        @if (p.effectiveFrom) {
          {{ p.effectiveFrom | appDate: 'date' }} a {{ p.effectiveTo ? (p.effectiveTo | appDate: 'date') : 'em aberto' }}
        } @else {
          <span class="app-muted">não publicada</span>
        }
      </ng-template>
      <ng-template appCell="overrides" [appCellOf]="query.rows()" let-p><span class="app-numeric">{{ p.terms.overrides.length }}</span></ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-p>
        <a class="btn btn-sm btn-link" [routerLink]="['/negociacao/politicas', p.id]" aria-label="Abrir versão" ngbTooltip="Abrir">
          <app-icon name="chevron-right" />
        </a>
      </ng-template>
    </app-data-table>
  `,
})
export class PolicyList {
  private readonly api = inject(PoliciesApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly router = inject(Router);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly statuses = statusOptions('collectionPolicy');
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.POLICY_MANAGE));

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({ creditor: this.fb.control<string[]>([]), status: [''] });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map((): PolicyFilter => {
        const v = this.filters.getRawValue();
        return { creditorId: v.creditor[0] ?? null, status: v.status || null };
      }),
    ),
    { initialValue: { creditorId: null, status: null } as PolicyFilter, equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.search(f, page));
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((p) => p.creditorId));

  protected readonly columns: TableColumn[] = [
    { key: 'creditor', header: 'Credor' },
    { key: 'version', header: 'Versão' },
    { key: 'status', header: 'Situação' },
    { key: 'period', header: 'Vigência' },
    { key: 'overrides', header: 'Sobreposições', align: 'end' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected create(): void {
    this.dialog
      .open<PolicyCreate, void, PolicyView>(PolicyCreate)
      .afterClosed()
      .subscribe((created) => {
        if (created) {
          this.notifications.success('Rascunho criado a partir do modelo de mercado.');
          void this.router.navigate(['/negociacao/politicas', created.id]);
        }
      });
  }
}
