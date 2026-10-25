import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DialogRef } from '../../../shared/dialog/dialog-ref';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
import { toDateStruct, toLocalDate } from '../../../shared/models/dates';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { UserLookup } from '../../admin/data/user.lookup';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { zonedNow } from '../../collection/data/collection.rules';
import { ReasonDialog, ReasonDialogData } from '../components/reason-dialog';
import { QualityApi } from '../data/incentive.api';
import { OCCURRENCE_TYPE_LABELS, REFERENCE_TYPE_LABELS } from '../data/incentive.labels';
import { OccurrenceType, OccurrenceView, ReferenceType } from '../data/incentive.models';
import { REASON_MAX, REASON_MIN } from '../data/incentive.rules';
import { incentiveError } from '../statements/statement-dialogs';
import { personRangeFilter } from './person-range-filter';
import { QualityTabs } from './quality-tabs';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Registro de ocorrência de conduta (violação, reclamação, contestação procedente). */
@Component({
  selector: 'app-incentive-occurrence-dialog',
  imports: [ReactiveFormsModule, NgbInputDatepicker, ...DIALOG_DIRECTIVES, ...FORM_FIELD, EntityPicker, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Registrar ocorrência de conduta</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <div class="app-form-grid">
          <app-entity-picker class="app-span-all" [control]="form.controls.user" [source]="users" label="Negociador" [single]="true" />
          <app-field label="Tipo">
            <select appInput ngbAutofocus formControlName="type">
              @for (t of types; track t[0]) {
                <option [ngValue]="t[0]">{{ t[1] }}</option>
              }
            </select>
          </app-field>
          <app-field label="Data da ocorrência">
            <div class="input-group">
              <input appInput ngbDatepicker #d="ngbDatepicker" formControlName="occurredOn" placeholder="dd/mm/aaaa" [maxDate]="today" />
              <button type="button" class="btn btn-outline-secondary" (click)="d.toggle()" aria-label="Abrir calendário"><app-icon name="calendar-event" /></button>
            </div>
          </app-field>
          <app-field label="Referência (opcional)">
            <select appInput formControlName="referenceType">
              <option [ngValue]="null">Sem referência</option>
              @for (r of references; track r[0]) {
                <option [ngValue]="r[0]">{{ r[1] }}</option>
              }
            </select>
          </app-field>
          <app-field label="Identificador da referência" error="Identificador completo (UUID).">
            <input appInput class="font-monospace" formControlName="referenceId" autocomplete="off" spellcheck="false" />
          </app-field>
          <app-field class="app-span-all" label="Descrição" [hint]="'Mínimo ' + min + ' caracteres; sem dados pessoais do devedor'" [error]="'Descreva a ocorrência (mínimo ' + min + ' caracteres).'">
            <textarea appInput formControlName="description" rows="3" [attr.maxlength]="max"></textarea>
          </app-field>
        </div>
        @if (severe()) {
          <div class="alert alert-warning d-flex align-items-start gap-2" role="note">
            <app-icon name="exclamation-triangle" />
            <span>Violação grave confirmada zera o variável do período quando o plano usa o zerador. O negociador pode contestar; a gestão pode dispensar a ocorrência.</span>
          </div>
        }
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="sending()">Registrar</button>
      </div>
    </form>
  `,
})
export class OccurrenceDialog {
  private readonly ref = inject<DialogRef<OccurrenceView>>(DialogRef);
  private readonly api = inject(QualityApi);
  protected readonly users = inject(UserLookup);
  protected readonly types = Object.entries(OCCURRENCE_TYPE_LABELS) as [OccurrenceType, string][];
  protected readonly references = Object.entries(REFERENCE_TYPE_LABELS) as [ReferenceType, string][];
  protected readonly today = toDateStruct(zonedNow().date);
  protected readonly min = REASON_MIN;
  protected readonly max = REASON_MAX;
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    user: this.fb.control<string[]>([]),
    type: this.fb.control<OccurrenceType>('COMPLIANCE_VIOLATION'),
    occurredOn: this.fb.control<string | null>(toLocalDate(new Date()), [Validators.required]),
    referenceType: this.fb.control<ReferenceType | null>(null),
    referenceId: ['', [(c: { value: unknown }) => (!c.value || UUID.test(String(c.value).trim()) ? null : { pattern: true })]],
    description: ['', [Validators.required, Validators.maxLength(REASON_MAX), (c: { value: unknown }) => (String(c.value).trim().length < REASON_MIN ? { minlength: true } : null)]],
  });
  protected readonly severe = signal(false);

  constructor() {
    this.form.controls.type.valueChanges.subscribe((t) => this.severe.set(t === 'SEVERE_COMPLIANCE_VIOLATION'));
  }

  protected save(): void {
    const v = this.form.getRawValue();
    if (this.form.invalid || !v.user[0] || this.sending()) {
      this.form.markAllAsTouched();
      if (!v.user[0]) {
        this.error.set('Escolha o negociador.');
      }
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    this.api
      .register({ userId: v.user[0], type: v.type, occurredOn: v.occurredOn!, description: v.description.trim(), referenceType: v.referenceType, referenceId: v.referenceId.trim() || null })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({ next: (o) => this.ref.close(o), error: (err: unknown) => this.error.set(incentiveError(err)) });
  }
}

/** Ocorrências de conduta (insumo do zerador de compliance), com dispensa por contestação procedente. */
@Component({
  selector: 'app-incentive-occurrence-list',
  imports: [ReactiveFormsModule, NgbInputDatepicker, ...FORM_FIELD, AppDatePipe, CellDef, DataTable, EntityPicker, Icon, PageHeader, QualityTabs, StatusChip],
  template: `
    <app-page-header title="Ocorrências de conduta" subtitle="Violações de compliance, reclamações e contestações procedentes atribuídas aos negociadores.">
      @if (canEvaluate()) {
        <button class="btn btn-primary" type="button" (click)="create()" data-tour="occurrence-new">
          <app-icon name="plus-lg" />
          Registrar ocorrência
        </button>
      }
    </app-page-header>
    <app-quality-tabs />
    <form class="app-filters" data-tour="filters" [formGroup]="range.form" role="search" aria-label="Filtrar ocorrências">
      @if (canPickPeople()) {
        <div class="app-filters__wide">
          <app-entity-picker [control]="range.form.controls.user" [source]="users" label="Negociador" [single]="true" />
        </div>
      }
      <app-field label="De" hint="Padrão: últimos 90 dias">
        <div class="input-group">
          <input appInput ngbDatepicker #df="ngbDatepicker" formControlName="from" placeholder="dd/mm/aaaa" />
          <button type="button" class="btn btn-outline-secondary" (click)="df.toggle()" aria-label="Abrir calendário (de)"><app-icon name="calendar-event" /></button>
        </div>
      </app-field>
      <app-field label="Até">
        <div class="input-group">
          <input appInput ngbDatepicker #dt="ngbDatepicker" formControlName="to" placeholder="dd/mm/aaaa" />
          <button type="button" class="btn btn-outline-secondary" (click)="dt.toggle()" aria-label="Abrir calendário (até)"><app-icon name="calendar-event" /></button>
        </div>
      </app-field>
    </form>
    @if (error()) {
      <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
    }
    <app-data-table
      caption="Ocorrências de conduta"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="shield-check"
      emptyMessage="Nenhuma ocorrência no período."
    >
      <ng-template appCell="date" [appCellOf]="query.rows()" let-o>{{ o.occurredOn | appDate: 'date' }}</ng-template>
      <ng-template appCell="user" [appCellOf]="query.rows()" let-o>
        {{ names.name(o.userId) }}
        <div class="app-muted in-small">registrada por {{ names.name(o.registeredBy) }}</div>
      </ng-template>
      <ng-template appCell="type" [appCellOf]="query.rows()" let-o>
        {{ types[o.type] }}
        @if (o.referenceType) {
          <div class="app-muted in-small">{{ refs[o.referenceType] }}{{ o.referenceId ? ' ' + o.referenceId.slice(0, 8) : '' }}</div>
        }
      </ng-template>
      <ng-template appCell="description" [appCellOf]="query.rows()" let-o>
        {{ o.description }}
        @if (o.dismissalReason) {
          <div class="app-muted in-small">Dispensada por {{ names.name(o.dismissedBy) }}: {{ o.dismissalReason }}</div>
        }
      </ng-template>
      <ng-template appCell="status" [appCellOf]="query.rows()" let-o><app-status-chip domain="conductOccurrence" [status]="o.status" /></ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-o>
        @if (canDismiss(o)) {
          <button type="button" class="btn btn-sm btn-outline-secondary" (click)="dismiss(o)" [disabled]="busy()">Dispensar</button>
        }
      </ng-template>
    </app-data-table>
  `,
  styleUrl: '../incentive.scss',
})
export class OccurrenceList {
  private readonly api = inject(QualityApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly notifications = inject(NotificationService);
  protected readonly users = inject(UserLookup);
  protected readonly types = OCCURRENCE_TYPE_LABELS;
  protected readonly refs = REFERENCE_TYPE_LABELS;
  protected readonly canEvaluate = computed(() => this.auth.hasPermission(Permission.QUALITY_EVALUATE));
  private readonly canManage = computed(() => this.auth.hasPermission(Permission.COMMISSION_STATEMENT_MANAGE));
  protected readonly canPickPeople = computed(
    () => this.auth.hasPermission(Permission.USER_VIEW) && this.auth.hasAnyPermission([Permission.PERFORMANCE_VIEW_TEAM, Permission.PERFORMANCE_VIEW_ALL, Permission.QUALITY_EVALUATE]),
  );
  protected readonly range = personRangeFilter(inject(FormBuilder));
  protected readonly query = pagedQuery(this.range.filter, (f, page) => this.api.occurrences(f, page));
  protected readonly names = nameMap(inject(UserNames), () => this.query.rows().flatMap((o) => [o.userId, o.registeredBy, o.dismissedBy]), { batch: USER_NAMES_BATCH });
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly columns: TableColumn[] = [
    { key: 'date', header: 'Data' },
    { key: 'user', header: 'Negociador' },
    { key: 'type', header: 'Tipo' },
    { key: 'description', header: 'Descrição' },
    { key: 'status', header: 'Situação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  /** Dispensa: gestão, nunca o próprio negociador nem quem registrou (segregação do servidor). */
  protected canDismiss(o: OccurrenceView): boolean {
    const me = this.auth.user()?.id;
    return this.canManage() && o.status === 'CONFIRMED' && o.userId !== me && o.registeredBy !== me;
  }

  protected create(): void {
    this.dialog
      .open<OccurrenceDialog, void, OccurrenceView>(OccurrenceDialog, { size: 'lg' })
      .afterClosed()
      .subscribe((o) => {
        if (o) {
          this.notifications.success('Ocorrência registrada.');
          this.query.reload();
        }
      });
  }

  protected dismiss(o: OccurrenceView): void {
    const data: ReasonDialogData = {
      title: 'Dispensar ocorrência',
      message: 'A ocorrência deixa de valer para o zerador e os indicadores (contestação procedente). Extratos ainda não aprovados podem ser recalculados.',
      label: 'Motivo da dispensa',
      confirmLabel: 'Dispensar',
      details: [
        { label: 'Negociador', value: this.names.name(o.userId) },
        { label: 'Tipo', value: OCCURRENCE_TYPE_LABELS[o.type] },
        { label: 'Data', value: o.occurredOn.split('-').reverse().join('/') },
      ],
    };
    this.dialog
      .open<ReasonDialog, ReasonDialogData, string>(ReasonDialog, { data })
      .afterClosed()
      .subscribe((reason) => {
        if (reason === undefined) {
          return;
        }
        this.busy.set(true);
        this.error.set(null);
        this.api
          .dismiss(o.id, reason)
          .pipe(finalize(() => this.busy.set(false)))
          .subscribe({
            next: () => {
              this.notifications.success('Ocorrência dispensada.');
              this.query.reload();
            },
            error: (err: unknown) => this.error.set(incentiveError(err)),
          });
      });
  }
}
