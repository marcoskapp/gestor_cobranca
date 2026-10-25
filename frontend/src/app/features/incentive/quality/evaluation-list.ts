import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { finalize, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { valueOr } from '../../../shared/data/resource-value';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
import { toDateStruct, toLocalDate } from '../../../shared/models/dates';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { UserLookup } from '../../admin/data/user.lookup';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { zonedNow } from '../../collection/data/collection.rules';
import { QualityApi } from '../data/incentive.api';
import { REFERENCE_TYPE_LABELS } from '../data/incentive.labels';
import { EvaluationView, QualityForm, ReferenceType } from '../data/incentive.models';
import { parseDecimal, score } from '../data/incentive.rules';
import { incentiveError } from '../statements/statement-dialogs';
import { personRangeFilter } from './person-range-filter';
import { QualityTabs } from './quality-tabs';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const referenceTypes = Object.entries(REFERENCE_TYPE_LABELS) as [ReferenceType, string][];

/** Nota ponderada (0–10) e falha crítica, como `QualityForm.score`: nota 0 em critério crítico zera a avaliação. */
export function weightedScore(form: QualityForm, scores: Record<string, number | null>): { score: number | null; critical: boolean } {
  let total = 0;
  let weights = 0;
  let critical = false;
  for (const c of form.criteria) {
    const s = scores[c.code];
    if (s === null || s === undefined) {
      return { score: null, critical: false };
    }
    total += s * c.weight;
    weights += c.weight;
    critical ||= c.critical && s === 0;
  }
  if (critical) {
    return { score: 0, critical };
  }
  return { score: weights ? Math.round((total / weights) * 100) / 100 : null, critical };
}

/** Registro de monitoria: formulário, negociador avaliado, atendimento de referência e nota por critério. */
@Component({
  selector: 'app-incentive-evaluation-dialog',
  imports: [ReactiveFormsModule, NgbInputDatepicker, ...DIALOG_DIRECTIVES, ...FORM_FIELD, EntityPicker, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Registrar monitoria</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <div class="app-form-grid">
          <app-field class="app-span-all" label="Formulário">
            <select appInput ngbAutofocus formControlName="formId" (change)="pickForm()">
              @for (f of forms; track f.id) {
                <option [ngValue]="f.id">{{ f.name }}</option>
              }
            </select>
          </app-field>
          <app-entity-picker class="app-span-all" [control]="form.controls.user" [source]="users" label="Negociador avaliado" [single]="true" />
          <app-field label="Atendimento de referência">
            <select appInput formControlName="referenceType">
              @for (r of references; track r[0]) {
                <option [ngValue]="r[0]">{{ r[1] }}</option>
              }
            </select>
          </app-field>
          <app-field label="Identificador (opcional)" hint="Da chamada, conversa, caso ou acordo" error="Identificador completo (UUID).">
            <input appInput class="font-monospace" formControlName="referenceId" autocomplete="off" spellcheck="false" />
          </app-field>
          <app-field label="Data do atendimento">
            <div class="input-group">
              <input appInput ngbDatepicker #d="ngbDatepicker" formControlName="evaluatedOn" placeholder="dd/mm/aaaa" [maxDate]="today" />
              <button type="button" class="btn btn-outline-secondary" (click)="d.toggle()" aria-label="Abrir calendário"><app-icon name="calendar-event" /></button>
            </div>
          </app-field>
        </div>
        @if (selected(); as f) {
          <fieldset class="mt-2">
            <legend class="form-label">Notas de 0 a 10</legend>
            <div class="app-form-grid">
              @for (c of f.criteria; track c.code) {
                <app-field [label]="c.description + (c.critical ? ' (crítico: nota 0 zera a avaliação)' : '')" [hint]="'Peso ' + c.weight" error="Nota de 0 a 10.">
                  <input appInput [formControl]="scoreControl(c.code)" inputmode="decimal" />
                </app-field>
              }
            </div>
          </fieldset>
          <p class="app-dialog-message" aria-live="polite">
            Nota ponderada: <strong>{{ preview().score === null ? '—' : scoreText(preview().score) }}</strong>
            @if (preview().critical) {
              <span class="text-danger-emphasis"> · falha em critério crítico</span>
            }
          </p>
        }
        <app-field label="Comentários (opcional)" hint="Até 4000 caracteres">
          <textarea appInput formControlName="comments" rows="3" maxlength="4000"></textarea>
        </app-field>
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
export class EvaluationDialog {
  protected readonly forms = inject<QualityForm[]>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<EvaluationView>>(DialogRef);
  private readonly api = inject(QualityApi);
  protected readonly users = inject(UserLookup);
  protected readonly references = referenceTypes;
  protected readonly today = toDateStruct(zonedNow().date);
  protected readonly scoreText = score;
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    formId: [this.forms[0]?.id ?? '', [Validators.required]],
    user: this.fb.control<string[]>([]),
    referenceType: this.fb.control<ReferenceType>('CALL'),
    referenceId: ['', [(c: { value: unknown }) => (!c.value || UUID.test(String(c.value).trim()) ? null : { pattern: true })]],
    evaluatedOn: this.fb.control<string | null>(toLocalDate(new Date()), [Validators.required]),
    comments: ['', [Validators.maxLength(4000)]],
  });
  private readonly scores = new Map<string, FormControl<string>>();
  private readonly formId = toSignal(this.form.controls.formId.valueChanges.pipe(startWith(this.form.controls.formId.value)), { initialValue: '' });
  protected readonly selected = computed(() => this.forms.find((f) => f.id === this.formId()) ?? null);
  private readonly scoreValues = signal(0);
  protected readonly preview = computed(() => {
    this.scoreValues();
    const f = this.selected();
    return f ? weightedScore(f, this.values(f)) : { score: null, critical: false };
  });

  protected pickForm(): void {
    this.scores.clear();
  }

  protected scoreControl(code: string): FormControl<string> {
    let control = this.scores.get(code);
    if (!control) {
      control = new FormControl('', {
        nonNullable: true,
        validators: [(c) => { const n = parseDecimal(c.value as string); return n !== null && n >= 0 && n <= 10 ? null : { range: true }; }],
      });
      control.valueChanges.subscribe(() => this.scoreValues.update((v) => v + 1));
      this.scores.set(code, control);
    }
    return control;
  }

  private values(f: QualityForm): Record<string, number | null> {
    return Object.fromEntries(f.criteria.map((c) => [c.code, parseDecimal(this.scores.get(c.code)?.value ?? '')]));
  }

  protected save(): void {
    const f = this.selected();
    const v = this.form.getRawValue();
    const controls = f ? f.criteria.map((c) => this.scoreControl(c.code)) : [];
    controls.forEach((c) => c.markAsTouched());
    if (!f || this.form.invalid || controls.some((c) => c.invalid) || !v.user[0] || this.sending()) {
      this.form.markAllAsTouched();
      if (!v.user[0]) {
        this.error.set('Escolha o negociador avaliado.');
      }
      return;
    }
    const scores = this.values(f) as Record<string, number>;
    this.sending.set(true);
    this.error.set(null);
    this.api
      .evaluate({ formId: f.id, userId: v.user[0], referenceType: v.referenceType, referenceId: v.referenceId.trim() || null, evaluatedOn: v.evaluatedOn!, scores, comments: v.comments.trim() || null })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({ next: (e) => this.ref.close(e), error: (err: unknown) => this.error.set(incentiveError(err)) });
  }
}

/** Monitorias de qualidade registradas (insumo do portão de monitoria do plano). */
@Component({
  selector: 'app-incentive-evaluation-list',
  imports: [ReactiveFormsModule, NgbInputDatepicker, ...FORM_FIELD, AppDatePipe, CellDef, DataTable, EntityPicker, Icon, PageHeader, QualityTabs],
  template: `
    <app-page-header title="Monitoria de qualidade" subtitle="Avaliações de atendimento (0 a 10). A nota média do período pode suspender o variável pelo portão de monitoria do plano.">
      @if (canEvaluate()) {
        <button class="btn btn-primary" type="button" (click)="create()" [disabled]="!activeForms().length" data-tour="quality-evaluate">
          <app-icon name="plus-lg" />
          Registrar monitoria
        </button>
      }
    </app-page-header>
    <app-quality-tabs />
    @if (canEvaluate() && formsLoaded() && !activeForms().length) {
      <div class="alert alert-info d-flex align-items-start gap-2" role="note"><app-icon name="info-circle" /><span>Crie um formulário de monitoria ativo antes de registrar avaliações.</span></div>
    }
    <form class="app-filters" data-tour="filters" [formGroup]="range.form" role="search" aria-label="Filtrar avaliações">
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
    @if (range.invalid()) {
      <div class="alert alert-warning" role="alert">A data final é anterior à inicial.</div>
    }
    <app-data-table
      caption="Avaliações de qualidade"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="clipboard-check"
      emptyMessage="Nenhuma avaliação no período."
      emptyHint="As avaliações registradas pela monitoria aparecem aqui."
    >
      <ng-template appCell="date" [appCellOf]="query.rows()" let-e>
        {{ e.evaluatedOn | appDate: 'date' }}
        <div class="app-muted in-small">{{ refs[e.referenceType] }}{{ e.referenceId ? ' ' + e.referenceId.slice(0, 8) : '' }}</div>
      </ng-template>
      <ng-template appCell="user" [appCellOf]="query.rows()" let-e>
        {{ names.name(e.userId) }}
        <div class="app-muted in-small">por {{ names.name(e.evaluatorId) }}</div>
      </ng-template>
      <ng-template appCell="form" [appCellOf]="query.rows()" let-e>{{ formName(e.formId) }}</ng-template>
      <ng-template appCell="score" [appCellOf]="query.rows()" let-e>
        <strong class="app-numeric">{{ scoreText(e.score) }}</strong>
        @if (e.criticalFailure) {
          <div class="in-small text-danger-emphasis">falha crítica</div>
        }
      </ng-template>
      <ng-template appCell="comments" [appCellOf]="query.rows()" let-e>{{ e.comments ?? '—' }}</ng-template>
    </app-data-table>
  `,
  styleUrl: '../incentive.scss',
})
export class EvaluationList {
  private readonly api = inject(QualityApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly notifications = inject(NotificationService);
  protected readonly users = inject(UserLookup);
  protected readonly refs = REFERENCE_TYPE_LABELS;
  protected readonly scoreText = score;
  protected readonly canEvaluate = computed(() => this.auth.hasPermission(Permission.QUALITY_EVALUATE));
  protected readonly canPickPeople = computed(
    () => this.auth.hasPermission(Permission.USER_VIEW) && this.auth.hasAnyPermission([Permission.PERFORMANCE_VIEW_TEAM, Permission.PERFORMANCE_VIEW_ALL, Permission.QUALITY_EVALUATE]),
  );
  protected readonly range = personRangeFilter(inject(FormBuilder));
  protected readonly query = pagedQuery(this.range.filter, (f, page) => this.api.evaluations(f, page));
  private readonly formList = rxResource({ stream: () => this.api.forms() });
  private readonly forms = valueOr(this.formList, []);
  protected readonly formsLoaded = computed(() => this.formList.hasValue());
  protected readonly activeForms = computed(() => this.forms().filter((f) => f.active));
  protected readonly names = nameMap(inject(UserNames), () => this.query.rows().flatMap((e) => [e.userId, e.evaluatorId]), { batch: USER_NAMES_BATCH });

  protected readonly columns: TableColumn[] = [
    { key: 'date', header: 'Atendimento' },
    { key: 'user', header: 'Negociador' },
    { key: 'form', header: 'Formulário' },
    { key: 'score', header: 'Nota', align: 'end' },
    { key: 'comments', header: 'Comentários' },
  ];

  protected formName(id: string): string {
    return this.forms().find((f) => f.id === id)?.name ?? '—';
  }

  protected create(): void {
    this.dialog
      .open<EvaluationDialog, QualityForm[], EvaluationView>(EvaluationDialog, { data: this.activeForms(), size: 'lg' })
      .afterClosed()
      .subscribe((e) => {
        if (e) {
          this.notifications.success(`Monitoria registrada: nota ${score(e.score)}. Extratos ainda não aprovados podem ser recalculados.`);
          this.query.reload();
        }
      });
  }
}
