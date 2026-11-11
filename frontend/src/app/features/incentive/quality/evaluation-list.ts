import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbInputDatepicker, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
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
import { score } from '../data/incentive.rules';
import { incentiveError } from '../statements/statement-dialogs';
import { personRangeFilter } from './person-range-filter';
import { EvaluableUserSource } from './evaluable-user-source';
import type { EvaluationDialogData } from './evaluation-launcher';
import { Router, RouterLink } from '@angular/router';
import { CallsApi } from '../../telephony/data/telephony.api';

/** Tipo do atendimento sem vínculo: a avaliação geral vem primeiro (sem link de volta, o tipo só classifica). */
const referenceTypes: [ReferenceType, string][] = [
  ['OTHER', 'Geral (sem atendimento específico)'],
  ...(Object.entries(REFERENCE_TYPE_LABELS) as [ReferenceType, string][]).filter(([t]) => t !== 'OTHER'),
];

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

/** Escala da nota por critério: um clique por nota inteira, de 0 a 10. */
const SCALE = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10] as const;

const REFERENCE_ICONS: Record<ReferenceType, string> = {
  CALL: 'telephone',
  CONVERSATION: 'chat-left-text',
  CASE: 'folder2-open',
  AGREEMENT: 'file-earmark-check',
  OTHER: 'clipboard-check',
};

let nextDialogId = 0;

/**
 * Registro de monitoria: o atendimento avaliado (já preenchido quando vem de "Avaliar" na chamada, na gravação, na
 * conversa ou no histórico do caso), a nota de cada critério numa escala de 0 a 10 de um clique e a nota ponderada
 * sempre visível no rodapé (ux.md §19).
 */
@Component({
  selector: 'app-incentive-evaluation-dialog',
  styles: `
    .ev-section + .ev-section {
      margin-top: 24px;
    }
    .ev-section__head {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      justify-content: space-between;
      gap: 4px 16px;
      margin-bottom: 12px;
      .app-section-title {
        margin: 0;
      }
    }
    .ev-target {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 12px 16px;
      border: 1px solid var(--bs-border-color);
      border-radius: var(--bs-border-radius-lg);
      background: var(--bs-tertiary-bg);
    }
    .ev-target__icon {
      flex-shrink: 0;
      font-size: 1.25rem;
      color: var(--bs-primary);
    }
    .ev-target__body {
      flex: 1;
      min-width: 0;
    }
    .ev-target__date {
      flex-shrink: 0;
      text-align: end;
    }
    .ev-criteria {
      display: flex;
      flex-direction: column;
      gap: 12px;
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .ev-criterion {
      margin: 0;
      padding: 12px 16px;
      border: 1px solid var(--bs-border-color);
      border-radius: var(--bs-border-radius-lg);
    }
    .ev-criterion--missing {
      border-color: var(--bs-form-invalid-border-color);
    }
    .ev-criterion--critical-fail {
      border-color: var(--bs-danger-border-subtle);
      background: var(--bs-danger-bg-subtle);
    }
    .ev-criterion__head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 8px;
      margin-bottom: 8px;
      padding: 0;
      font-size: inherit;
      font-weight: var(--app-weight-medium);
    }
    .ev-criterion__text {
      margin-inline-end: auto;
    }
    .ev-scale {
      display: grid;
      grid-template-columns: repeat(11, minmax(0, 1fr));
      gap: 4px;
    }
    .ev-scale__option {
      padding-inline: 0;
      min-width: 0;
      font-variant-numeric: tabular-nums;
    }
    .ev-scale__ends {
      display: flex;
      justify-content: space-between;
      margin-top: 4px;
      font-size: var(--app-text-xs);
      color: var(--bs-secondary-color);
    }
    .ev-score {
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: 4px 8px;
      margin: 0 auto 0 0;
    }
    .ev-score__value {
      font-size: 1.5rem;
      font-weight: var(--app-weight-semibold);
      font-variant-numeric: tabular-nums;
      line-height: 1;
    }
  `,
  imports: [ReactiveFormsModule, RouterLink, NgbInputDatepicker, NgbTooltip, ...DIALOG_DIRECTIVES, ...FORM_FIELD, AppDatePipe, EntityPicker, Icon],
  template: `
    <div class="modal-header">
      <div>
        <h2 appDialogTitle class="h5 mb-0">Registrar monitoria</h2>
        @if (forms.length === 1) {
          <p class="app-muted small mb-0" data-testid="evaluation-form-name">{{ forms[0].name }}</p>
        }
      </div>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <section class="ev-section" [attr.aria-labelledby]="ids.target">
          <div class="ev-section__head">
            <h3 class="app-section-title" [id]="ids.target">Atendimento avaliado</h3>
          </div>
          @if (prefill; as p) {
            <!-- vindo do atendimento: negociador, referência e data fixos -->
            <div class="ev-target" data-testid="evaluation-prefill">
              <app-icon class="ev-target__icon" [name]="refIcons[p.referenceType]" />
              <div class="ev-target__body">
                <div class="fw-semibold">{{ p.userName }}</div>
                <div class="app-muted small text-truncate" [title]="p.label">{{ refLabels[p.referenceType] }} · {{ p.label }}</div>
              </div>
              <div class="ev-target__date">
                <div class="app-muted small">Data</div>
                <div>{{ p.evaluatedOn | appDate: 'date' }}</div>
              </div>
            </div>
          } @else {
            <div class="app-form-grid">
              <app-entity-picker class="app-span-all" [control]="form.controls.user" [source]="users" label="Negociador avaliado" [single]="true" />
              <app-field label="Tipo de atendimento">
                <select appInput formControlName="referenceType">
                  @for (r of references; track r[0]) {
                    <option [ngValue]="r[0]">{{ r[1] }}</option>
                  }
                </select>
              </app-field>
              <app-field label="Data do atendimento">
                <div class="input-group">
                  <input appInput ngbDatepicker #d="ngbDatepicker" formControlName="evaluatedOn" placeholder="dd/mm/aaaa" [maxDate]="today" />
                  <button type="button" class="btn btn-outline-secondary" (click)="d.toggle()" aria-label="Abrir calendário"><app-icon name="calendar-event" /></button>
                </div>
              </app-field>
            </div>
            <div class="alert alert-info d-flex align-items-start gap-2 mt-3 mb-0" role="note" data-testid="evaluation-unlinked">
              <app-icon name="info-circle" />
              <span>
                Sem vínculo com um atendimento específico. Para avaliar uma ligação ou conversa com link de volta para revisar, use
                “Avaliar” no próprio atendimento@if (sources.length) {:
                  @for (s of sources; track s.route; let last = $last) {
                    <a [routerLink]="s.route" (click)="leave()">{{ s.label }}</a>{{ last ? '' : ', ' }}
                  }
                  ou no histórico do caso.
                } @else {
                  (chamada, conversa ou histórico do caso).
                }
              </span>
            </div>
          }
        </section>

        <section class="ev-section" [attr.aria-labelledby]="ids.criteria">
          <div class="ev-section__head">
            <h3 class="app-section-title" [id]="ids.criteria">Critérios</h3>
            @if (selected(); as f) {
              <span class="app-muted small" data-testid="evaluation-progress">{{ answered() }} de {{ f.criteria.length }} avaliados · nota de 0 a 10</span>
            }
          </div>
          @if (forms.length > 1) {
            <app-field class="d-block mb-3" label="Formulário">
              <select appInput formControlName="formId" (change)="pickForm()">
                @for (f of forms; track f.id) {
                  <option [ngValue]="f.id">{{ f.name }}</option>
                }
              </select>
            </app-field>
          }
          @if (selected(); as f) {
            <ol class="ev-criteria">
              @for (c of f.criteria; track c.code; let i = $index) {
                @let value = scoreControl(c.code).value;
                <li>
                  <fieldset
                    class="ev-criterion"
                    [class.ev-criterion--missing]="submitted() && value === null"
                    [class.ev-criterion--critical-fail]="c.critical && value === 0"
                    [attr.aria-invalid]="submitted() && value === null ? 'true' : null"
                    [attr.aria-describedby]="submitted() && value === null ? ids.prefix + c.code + '-error' : null"
                    data-testid="evaluation-criterion"
                  >
                    <legend class="ev-criterion__head">
                      <span class="ev-criterion__text">{{ i + 1 }}. {{ c.description }}</span>
                      @if (c.critical) {
                        <span class="badge rounded-pill border bg-danger-subtle text-danger-emphasis border-danger-subtle fw-normal" ngbTooltip="Nota 0 neste critério zera a avaliação"
                          >Crítico<span class="visually-hidden">: nota 0 neste critério zera a avaliação</span></span
                        >
                      }
                      <span class="badge rounded-pill border bg-body-tertiary text-body-secondary fw-normal">Peso {{ c.weight }}</span>
                    </legend>
                    <div class="ev-scale">
                      @for (n of scale; track n) {
                        <input
                          type="radio"
                          class="btn-check"
                          [id]="ids.prefix + c.code + '-' + n"
                          [name]="ids.prefix + c.code"
                          [value]="n"
                          [formControl]="scoreControl(c.code)"
                          [attr.aria-label]="scaleLabel(n)"
                        />
                        <label class="btn btn-sm btn-outline-secondary ev-scale__option" [for]="ids.prefix + c.code + '-' + n" aria-hidden="true">{{ n }}</label>
                      }
                    </div>
                    <div class="ev-scale__ends" aria-hidden="true"><span>Não atendeu</span><span>Excelente</span></div>
                    @if (submitted() && value === null) {
                      <div class="invalid-feedback d-block" [id]="ids.prefix + c.code + '-error'">Dê uma nota a este critério.</div>
                    }
                  </fieldset>
                </li>
              }
            </ol>
          }
        </section>

        <section class="ev-section">
          <app-field [label]="preview().critical ? 'Comentários' : 'Comentários (opcional)'" [hint]="commentHint()">
            <textarea appInput formControlName="comments" rows="3" maxlength="4000"></textarea>
          </app-field>
        </section>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2 mt-3 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <p class="ev-score" aria-live="polite" data-testid="evaluation-score">
          <span class="app-muted small">Nota ponderada</span>
          <strong class="ev-score__value" [class.text-danger-emphasis]="preview().critical">{{ preview().score === null ? '—' : scoreText(preview().score) }}</strong>
          @if (preview().critical) {
            <span class="small text-danger-emphasis">falha em critério crítico</span>
          } @else if (missing(); as n) {
            <span class="app-muted small">{{ n === 1 ? 'falta 1 critério' : 'faltam ' + n + ' critérios' }}</span>
          }
        </p>
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="sending()">Registrar monitoria</button>
      </div>
    </form>
  `,
})
export class EvaluationDialog {
  private readonly data = inject<EvaluationDialogData>(DIALOG_DATA);
  protected readonly forms = this.data.forms;
  /** Atendimento de origem ("Avaliar este atendimento"): negociador, referência e data ficam fixos. */
  protected readonly prefill = this.data.prefill ?? null;
  protected readonly refLabels = REFERENCE_TYPE_LABELS;
  protected readonly refIcons = REFERENCE_ICONS;
  protected readonly scale = SCALE;
  private readonly dialogId = ++nextDialogId;
  protected readonly ids = { target: `ev-target-${this.dialogId}`, criteria: `ev-criteria-${this.dialogId}`, prefix: `ev-${this.dialogId}-` };
  private readonly ref = inject<DialogRef<EvaluationView>>(DialogRef);
  private readonly api = inject(QualityApi);
  private readonly auth = inject(AuthService);
  protected readonly users = inject(EvaluableUserSource);
  protected readonly references = referenceTypes;
  protected readonly today = toDateStruct(zonedNow().date);
  protected readonly scoreText = score;
  protected readonly sending = signal(false);
  protected readonly submitted = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Onde avaliar com o atendimento vinculado, conforme o perfil abre. */
  protected readonly sources = [
    { label: 'Chamadas e gravações', route: '/telefonia/chamadas', allowed: this.auth.hasPermission(Permission.TELEPHONY_SUPERVISE) },
    { label: 'Caixa de entrada', route: '/conversas', allowed: this.auth.hasPermission(Permission.INBOX_VIEW) },
  ].filter((s) => s.allowed);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    formId: [this.forms[0]?.id ?? '', [Validators.required]],
    user: this.fb.control<string[]>(this.prefill ? [this.prefill.userId] : []),
    referenceType: this.fb.control<ReferenceType>(this.prefill?.referenceType ?? 'OTHER'),
    evaluatedOn: this.fb.control<string | null>(this.prefill?.evaluatedOn ?? toLocalDate(new Date()), [Validators.required]),
    comments: ['', [Validators.maxLength(4000)]],
  });
  private readonly scores = new Map<string, FormControl<number | null>>();
  private readonly formId = toSignal(this.form.controls.formId.valueChanges.pipe(startWith(this.form.controls.formId.value)), { initialValue: '' });
  protected readonly selected = computed(() => this.forms.find((f) => f.id === this.formId()) ?? null);
  private readonly scoreValues = signal(0);
  protected readonly preview = computed(() => {
    this.scoreValues();
    const f = this.selected();
    return f ? weightedScore(f, this.values(f)) : { score: null, critical: false };
  });
  protected readonly answered = computed(() => {
    this.scoreValues();
    const f = this.selected();
    return f ? f.criteria.filter((c) => (this.scores.get(c.code)?.value ?? null) !== null).length : 0;
  });
  protected readonly missing = computed(() => (this.selected()?.criteria.length ?? 0) - this.answered());
  protected readonly commentHint = computed(() =>
    this.preview().critical ? 'Descreva o que aconteceu no critério crítico: o comentário orienta o feedback ao negociador.' : 'Até 4000 caracteres',
  );

  protected pickForm(): void {
    this.scores.clear();
    this.scoreValues.update((v) => v + 1);
  }

  protected scoreControl(code: string): FormControl<number | null> {
    let control = this.scores.get(code);
    if (!control) {
      control = new FormControl<number | null>(null, { validators: [Validators.required, Validators.min(0), Validators.max(10)] });
      control.valueChanges.subscribe(() => this.scoreValues.update((v) => v + 1));
      this.scores.set(code, control);
    }
    return control;
  }

  private values(f: QualityForm): Record<string, number | null> {
    return Object.fromEntries(f.criteria.map((c) => [c.code, this.scores.get(c.code)?.value ?? null]));
  }

  /** Sai para a tela de origem do atendimento: o diálogo fecha sem registrar e o `routerLink` navega. */
  protected leave(): void {
    this.ref.close();
  }

  /** Rótulo do rádio (o critério já vem do `legend`); as pontas levam o significado da escala. */
  protected scaleLabel(n: number): string {
    return n === 0 ? 'Nota 0, não atendeu' : n === 10 ? 'Nota 10, excelente' : `Nota ${n}`;
  }

  protected save(): void {
    const f = this.selected();
    const v = this.form.getRawValue();
    const controls = f ? f.criteria.map((c) => this.scoreControl(c.code)) : [];
    this.submitted.set(true);
    if (!f || this.form.invalid || controls.some((c) => c.invalid) || !v.user[0] || this.sending()) {
      this.form.markAllAsTouched();
      this.error.set(!v.user[0] ? 'Escolha o negociador avaliado.' : this.missing() ? 'Dê uma nota a todos os critérios.' : null);
      return;
    }
    const scores = this.values(f) as Record<string, number>;
    this.sending.set(true);
    this.error.set(null);
    this.api
      .evaluate({
        formId: f.id,
        userId: v.user[0],
        referenceType: v.referenceType,
        referenceId: this.prefill?.referenceId ?? null,
        evaluatedOn: v.evaluatedOn!,
        scores,
        comments: v.comments.trim() || null,
      })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({ next: (e) => this.ref.close(e), error: (err: unknown) => this.error.set(incentiveError(err)) });
  }
}

/** Monitorias de qualidade registradas (insumo do portão de monitoria do plano). */
@Component({
  selector: 'app-incentive-evaluation-list',
  imports: [ReactiveFormsModule, RouterLink, NgbInputDatepicker, ...FORM_FIELD, AppDatePipe, CellDef, DataTable, EntityPicker, Icon, PageHeader],
  template: `
    <app-page-header title="Monitoria de qualidade" subtitle="Avaliações de atendimento (0 a 10). A nota média do período pode suspender o variável pelo portão de monitoria do plano.">
      @if (canEvaluate()) {
        <button class="btn btn-primary" type="button" (click)="create()" [disabled]="!activeForms().length" data-tour="quality-evaluate">
          <app-icon name="plus-lg" />
          Registrar monitoria
        </button>
      }
    </app-page-header>
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
      emptyHint="Avalie a partir do atendimento: “Avaliar” em Chamadas e gravações, na conversa ou no histórico do caso."
    >
      <ng-template appCell="date" [appCellOf]="query.rows()" let-e>
        {{ e.evaluatedOn | appDate: 'date' }}
        <div class="in-small">
          @if (referenceLink(e); as link) {
            <a [routerLink]="link.route" [queryParams]="link.query ?? null" data-testid="evaluation-reference">Ver {{ refs[e.referenceType].toLowerCase() }}</a>
          } @else if (canOpenCall(e)) {
            <button type="button" class="btn btn-link btn-sm p-0 align-baseline" (click)="openCall(e)" data-testid="evaluation-reference">Ver chamada</button>
          } @else {
            <span class="app-muted">{{ refs[e.referenceType] }}</span>
          }
        </div>
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
  private readonly calls = inject(CallsApi);
  private readonly router = inject(Router);
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
  protected readonly query = pagedQuery(this.range.filter, (f, page) => this.api.evaluations(f, page), { url: true });
  private readonly formList = rxResource({ stream: () => this.api.forms() });
  private readonly forms = valueOr(this.formList, []);
  protected readonly formsLoaded = computed(() => this.formList.hasValue());
  protected readonly activeForms = computed(() => this.forms().filter((f) => f.active));
  protected readonly names = nameMap(inject(UserNames), () => this.query.rows().flatMap((e) => [e.userId, e.evaluatorId]), { batch: USER_NAMES_BATCH });

  protected readonly columns: TableColumn[] = [
    { key: 'date', header: 'Atendimento' },
    { key: 'user', header: 'Negociador' },
    { key: 'form', header: 'Formulário' },
    { key: 'score', header: 'Nota', align: 'end', info: 'incentive.performance.qa-score' },
    { key: 'comments', header: 'Comentários' },
  ];

  protected formName(id: string): string {
    return this.forms().find((f) => f.id === id)?.name ?? '—';
  }

  /**
   * Link de volta para o atendimento avaliado (só o que o perfil abre): caso, conversa ou acordo. A chamada não tem
   * ficha própria: abre o histórico do caso dela (`openCall`).
   */
  protected referenceLink(e: EvaluationView): { route: string[]; query?: Record<string, string> } | null {
    if (!e.referenceId) {
      return null;
    }
    switch (e.referenceType) {
      case 'CASE':
        return this.auth.hasPermission(Permission.CASE_VIEW) ? { route: ['/cobranca/casos', e.referenceId], query: { aba: 'historico' } } : null;
      case 'CONVERSATION':
        return this.auth.hasPermission(Permission.INBOX_VIEW) ? { route: ['/conversas', e.referenceId] } : null;
      case 'AGREEMENT':
        return this.auth.hasPermission(Permission.AGREEMENT_VIEW) ? { route: ['/negociacao/acordos', e.referenceId] } : null;
      default:
        return null;
    }
  }

  protected canOpenCall(e: EvaluationView): boolean {
    return e.referenceType === 'CALL' && !!e.referenceId && this.auth.hasAnyPermission([Permission.TELEPHONY_USE, Permission.TELEPHONY_SUPERVISE]) && this.auth.hasPermission(Permission.CASE_VIEW);
  }

  /** Chamada avaliada: abre o histórico do caso em que ela aconteceu (sem caso identificado, avisa). */
  protected openCall(e: EvaluationView): void {
    this.calls.get(e.referenceId!).subscribe({
      next: (call) => {
        if (call.caseId) {
          void this.router.navigate(['/cobranca/casos', call.caseId], { queryParams: { aba: 'historico' } });
        } else {
          this.notifications.info('A chamada não foi ligada a um caso (número não identificado).');
        }
      },
      error: () => this.notifications.error('Não foi possível abrir a chamada: ela pode estar fora do seu escopo.'),
    });
  }

  protected create(): void {
    this.dialog
      .open<EvaluationDialog, EvaluationDialogData, EvaluationView>(EvaluationDialog, { data: { forms: this.activeForms() }, size: 'lg' })
      .afterClosed()
      .subscribe((e) => {
        if (e) {
          this.notifications.success(`Monitoria registrada: nota ${score(e.score)}. Extratos ainda não aprovados podem ser recalculados.`);
          this.query.reload();
        }
      });
  }
}
