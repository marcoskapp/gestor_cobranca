import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, startWith } from 'rxjs';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { UserLookup } from '../../admin/data/user.lookup';
import { TeamLookup } from '../../collection/data/collection.lookups';
import { CommissionPlansApi } from '../data/incentive.api';
import { GoalSubjectType, GoalUnit, GoalView, YearMonth } from '../data/incentive.models';
import { monthLabel } from '../data/incentive.rules';
import { incentiveError } from '../statements/statement-dialogs';
import { GOAL_PERCENT_MAX, GOAL_PERCENT_MIN, GOAL_UNITS, GOAL_UNIT_LABELS, goalPercentValidator, goalTargetText, goalUnitHint } from './goal-units';

export interface GoalDialogData {
  period: YearMonth;
  goal?: GoalView;
  subjectName?: string;
}

let nextId = 0;

/**
 * Define (ou altera) a meta de um negociador ou equipe no período. A pessoa escolhe a unidade
 * (valor em R$ ou percentual) e digita o valor nela: R$ com a máscara `appMoney`, % como número
 * simples (decisão 386). A unidade aceita vem da métrica da meta do plano vigente
 * (`/goals/unit-options`); se só uma é aceita, ela vem marcada e a outra desabilitada. Trocar a
 * unidade limpa o valor (nunca reinterpreta o número digitado).
 */
@Component({
  selector: 'app-incentive-goal-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, EntityPicker, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.goal ? 'Alterar meta' : 'Definir meta' }} · {{ month }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">A meta do período prevalece sobre a meta padrão do plano. Escolha a unidade e digite o valor: em reais para a métrica "valor recuperado", em percentual para "taxa de recuperação".</p>
        @if (data.goal) {
          <dl class="app-definition-list mb-3">
            <dt>{{ data.goal.subjectType === 'USER' ? 'Negociador' : 'Equipe' }}</dt>
            <dd>{{ data.subjectName }}</dd>
          </dl>
        } @else {
          <fieldset class="mb-3">
            <legend class="form-label">Meta de</legend>
            <div class="form-check form-check-inline">
              <input class="form-check-input" type="radio" id="gd-user" value="USER" formControlName="subjectType" />
              <label class="form-check-label" for="gd-user">Negociador</label>
            </div>
            <div class="form-check form-check-inline">
              <input class="form-check-input" type="radio" id="gd-team" value="TEAM" formControlName="subjectType" />
              <label class="form-check-label" for="gd-team">Equipe</label>
            </div>
          </fieldset>
          @if (type() === 'USER') {
            <app-entity-picker class="d-block mb-3" [control]="form.controls.user" [source]="users" label="Negociador" [single]="true" />
          } @else {
            <app-entity-picker class="d-block mb-3" [control]="form.controls.team" [source]="teams" label="Equipe" [single]="true" />
          }
        }

        <div class="mb-3">
          <span class="form-label d-block" [id]="uid + '-unit-label'">Unidade da meta</span>
          <div class="btn-group" role="group" [attr.aria-labelledby]="uid + '-unit-label'" [attr.aria-describedby]="uid + '-unit-hint'" [attr.aria-busy]="unitOptions.isLoading() || null">
            @for (u of units; track u) {
              <input type="radio" class="btn-check" [id]="uid + '-unit-' + u" [value]="u" formControlName="unit" [attr.disabled]="allows(u) ? null : ''" />
              <label class="btn btn-outline-primary" [for]="uid + '-unit-' + u">{{ unitLabels[u] }}</label>
            }
          </div>
          @if (unitMissing()) {
            <div class="invalid-feedback d-block" aria-live="polite">Escolha a unidade da meta.</div>
          }
          <div class="form-text" [id]="uid + '-unit-hint'" aria-live="polite">{{ unitHint() }}@if (notice()) { {{ notice() }}}</div>
        </div>

        @if (unit() === 'CURRENCY') {
          <app-field label="Meta" hint="Digite só os algarismos: os centavos entram pela direita." error="Informe um valor maior que zero.">
            <div class="input-group">
              <span class="input-group-text" aria-hidden="true">R$</span>
              <input appInput appMoney ngbAutofocus formControlName="amount" [min]="0.01" />
            </div>
          </app-field>
        } @else if (unit() === 'PERCENT') {
          <app-field label="Meta" hint="Percentual de 0,01 a 100, com até 2 casas (ex.: 12,5)." error="Informe um percentual de 0,01 a 100, com até 2 casas.">
            <div class="input-group">
              <input appInput ngbAutofocus type="number" inputmode="decimal" [min]="percentMin" [max]="percentMax" step="0.01" formControlName="percent" autocomplete="off" />
              <span class="input-group-text" aria-hidden="true">%</span>
            </div>
          </app-field>
        }
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="sending()">Salvar meta</button>
      </div>
    </form>
  `,
})
export class GoalDialog {
  protected readonly data = inject<GoalDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<GoalView>>(DialogRef);
  private readonly api = inject(CommissionPlansApi);
  protected readonly users = inject(UserLookup);
  protected readonly teams = inject(TeamLookup);
  protected readonly uid = `gd-${++nextId}`;
  protected readonly month = monthLabel(this.data.period);
  protected readonly units = GOAL_UNITS;
  protected readonly unitLabels = GOAL_UNIT_LABELS;
  protected readonly percentMin = GOAL_PERCENT_MIN;
  protected readonly percentMax = GOAL_PERCENT_MAX;
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Aviso da última mudança de unidade feita pela tela (lido pelo leitor de tela junto da explicação). */
  protected readonly notice = signal<string | null>(null);
  private readonly submitted = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    subjectType: this.fb.control<GoalSubjectType>(this.data.goal?.subjectType ?? 'USER'),
    user: this.fb.control<string[]>([]),
    team: this.fb.control<string[]>([]),
    unit: this.fb.control<GoalUnit | null>(this.data.goal?.unit ?? null, Validators.required),
    amount: this.fb.control<number | null>(this.data.goal?.unit === 'CURRENCY' ? this.data.goal.target : null, Validators.required),
    percent: this.fb.control<number | null>(this.data.goal?.unit === 'PERCENT' ? this.data.goal.target : null, [Validators.required, goalPercentValidator]),
  });
  private readonly value = toSignal(this.form.valueChanges.pipe(startWith(this.form.getRawValue())), { initialValue: this.form.getRawValue() });
  protected readonly type = computed(() => this.value().subjectType ?? 'USER');
  protected readonly unit = toSignal(this.form.controls.unit.valueChanges.pipe(startWith(this.form.controls.unit.value)), { initialValue: this.form.controls.unit.value });

  /** Negociador/equipe escolhido, como chave estável ("USER|id") para não recarregar a cada tecla. */
  private readonly subjectKey = computed(() => {
    const v = this.value();
    const type = this.data.goal?.subjectType ?? v.subjectType ?? 'USER';
    const id = this.data.goal?.subjectId ?? (type === 'USER' ? v.user?.[0] : v.team?.[0]);
    return id ? `${type}|${id}` : undefined;
  });
  protected readonly unitOptions = rxResource({
    params: () => this.subjectKey(),
    stream: ({ params }) => {
      const [type, id] = params.split('|') as [GoalSubjectType, string];
      return this.api.goalUnitOptions(this.data.period, type, id);
    },
  });
  private readonly options = computed(() => (this.unitOptions.status() === 'resolved' ? this.unitOptions.value() : null));

  protected readonly unitHint = computed(() => {
    if (!this.subjectKey()) {
      return 'Escolha o negociador ou a equipe para conferir a unidade aceita pelo plano vigente.';
    }
    if (this.unitOptions.isLoading()) {
      return 'Conferindo a métrica do plano vigente…';
    }
    if (this.unitOptions.status() === 'error') {
      return 'Não foi possível conferir o plano vigente; escolha a unidade (o servidor confere ao salvar).';
    }
    return goalUnitHint(this.options(), this.data.goal?.subjectType ?? this.type());
  });
  protected readonly unitMissing = computed(() => {
    this.value();
    const c = this.form.controls.unit;
    return c.invalid && (c.touched || this.submitted());
  });

  constructor() {
    this.applyUnit(this.form.controls.unit.value, false);
    // a escolha da pessoa limpa o valor: o número digitado numa unidade não vale na outra
    this.form.controls.unit.valueChanges.subscribe((u) => {
      this.applyUnit(u, true);
      this.notice.set(null);
    });
    // unidade única aceita pelo plano: marcada pela tela, com aviso explícito (a outra fica desabilitada)
    effect(() => {
      const options = this.options();
      if (!options) {
        return;
      }
      untracked(() => this.reconcile(options.units));
    });
  }

  protected allows(unit: GoalUnit): boolean {
    const options = this.options();
    return !options || options.units.includes(unit);
  }

  private reconcile(allowed: GoalUnit[]): void {
    const current = this.form.controls.unit.value;
    if (allowed.length !== 1 || current === allowed[0]) {
      if (current === null && this.data.goal && this.data.goal.unit === null) {
        this.notice.set(`Meta cadastrada antes da escolha de unidade (${goalTargetText(this.data.goal.target, null)}): escolha a unidade e digite o valor.`);
      }
      return;
    }
    const only = allowed[0];
    const legacy = this.data.goal && this.data.goal.unit === null && current === null ? this.data.goal : null;
    // emite: o campo da unidade aparece e a assinatura limpa o valor (avisos definidos em seguida)
    this.form.controls.unit.setValue(only);
    if (legacy) {
      // meta antiga: o plano já a lia nesta unidade; o valor aparece para conferência, sem conversão
      (only === 'CURRENCY' ? this.form.controls.amount : this.form.controls.percent).setValue(legacy.target);
      this.notice.set(`Meta cadastrada antes da escolha de unidade: confira o valor em ${only === 'CURRENCY' ? 'R$' : '%'} e salve.`);
    } else if (current !== null) {
      this.notice.set(`A unidade mudou para ${only === 'CURRENCY' ? 'valor (R$)' : 'percentual (%)'}: digite o valor de novo.`);
    }
  }

  /** Habilita só o campo da unidade escolhida (o outro sai da validação) e, se pedido, limpa os dois. */
  private applyUnit(unit: GoalUnit | null, clear: boolean): void {
    const { amount, percent } = this.form.controls;
    if (clear) {
      amount.reset(null, { emitEvent: false });
      percent.reset(null, { emitEvent: false });
    }
    if (unit === 'CURRENCY') {
      amount.enable({ emitEvent: false });
    } else {
      amount.disable({ emitEvent: false });
    }
    if (unit === 'PERCENT') {
      percent.enable({ emitEvent: false });
    } else {
      percent.disable({ emitEvent: false });
    }
    this.form.updateValueAndValidity();
  }

  protected save(): void {
    this.submitted.set(true);
    const v = this.form.getRawValue();
    const subjectType = this.data.goal?.subjectType ?? v.subjectType;
    const subjectId = this.data.goal?.subjectId ?? (subjectType === 'USER' ? v.user[0] : v.team[0]);
    if (!subjectId) {
      this.error.set(subjectType === 'USER' ? 'Escolha o negociador.' : 'Escolha a equipe.');
    }
    if (this.form.invalid || !subjectId || !v.unit || this.sending()) {
      this.form.markAllAsTouched();
      return;
    }
    const target = v.unit === 'CURRENCY' ? v.amount : v.percent;
    this.sending.set(true);
    this.error.set(null);
    this.api
      .setGoal({ period: this.data.period, subjectType, subjectId, target: target!, unit: v.unit })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({ next: (g) => this.ref.close(g), error: (err: unknown) => this.error.set(incentiveError(err)) });
  }
}
