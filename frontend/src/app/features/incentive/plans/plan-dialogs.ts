import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, startWith } from 'rxjs';
import { Amount } from '../../../shared/components/amount/amount';
import { Icon } from '../../../shared/components/icon/icon';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { zonedNow } from '../../collection/data/collection.rules';
import { CommissionPlansApi } from '../data/incentive.api';
import { PlanView, SimulationView } from '../data/incentive.models';
import { addMonths, fraction, monthLabel, monthOf, monthOptions } from '../data/incentive.rules';
import { cloneRules, standardRules } from '../data/plan-rules';
import { incentiveError } from '../statements/statement-dialogs';

export interface NewPlanDialogData {
  /** Versões vigentes que podem servir de base para as regras. */
  bases: readonly PlanView[];
}

/** Novo plano (versão 1, rascunho): código estável, nome, prioridade e regras de partida. */
@Component({
  selector: 'app-incentive-new-plan-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Novo plano de remuneração</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">O plano nasce como rascunho na versão 1. Ajuste as regras e publique a partir do dia 1º de um mês.</p>
        <div class="app-form-grid">
          <app-field label="Código" hint="Letras maiúsculas, números e _ (não muda depois)" error="De 2 a 40 caracteres: A–Z, 0–9 e _.">
            <input appInput ngbAutofocus class="font-monospace" formControlName="code" maxlength="40" autocomplete="off" (input)="upper()" />
          </app-field>
          <app-field label="Prioridade" hint="Desempate quando mais de um plano cobre o negociador (maior vence)" error="Entre 0 e 1000.">
            <input appInput type="number" min="0" max="1000" formControlName="priority" />
          </app-field>
          <app-field class="app-span-all" label="Nome">
            <input appInput formControlName="name" maxlength="120" />
          </app-field>
          <app-field class="app-span-all" label="Descrição (opcional)">
            <textarea appInput formControlName="description" rows="2" maxlength="1000"></textarea>
          </app-field>
          <app-field class="app-span-all" label="Regras de partida">
            <select appInput formControlName="base">
              <option ngValue="">Modelo de referência de mercado</option>
              @for (b of data.bases; track b.id) {
                <option [ngValue]="b.id">Copiar de {{ b.name }} ({{ b.code }} v{{ b.version }})</option>
              }
            </select>
          </app-field>
        </div>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="sending()">Criar rascunho</button>
      </div>
    </form>
  `,
})
export class NewPlanDialog {
  protected readonly data = inject<NewPlanDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<PlanView>>(DialogRef);
  private readonly api = inject(CommissionPlansApi);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    code: ['', [Validators.required, Validators.pattern(/^[A-Z0-9_]{2,40}$/)]],
    name: ['', [Validators.required, Validators.maxLength(120)]],
    description: ['', [Validators.maxLength(1000)]],
    priority: [10, [Validators.required, Validators.min(0), Validators.max(1000)]],
    base: [''],
  });

  protected upper(): void {
    const c = this.form.controls.code;
    c.setValue(c.value.toUpperCase().replace(/[^A-Z0-9_]/g, '_'), { emitEvent: false });
  }

  protected save(): void {
    if (this.form.invalid || this.sending()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const base = this.data.bases.find((b) => b.id === v.base);
    this.sending.set(true);
    this.error.set(null);
    this.api
      .create({ code: v.code, name: v.name.trim(), description: v.description.trim() || null, priority: Number(v.priority), rules: base ? cloneRules(base.rules) : standardRules() })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({ next: (p) => this.ref.close(p), error: (err: unknown) => this.error.set(incentiveError(err)) });
  }
}

export interface VigencyDialogData {
  plan: PlanView;
  mode: 'publish' | 'retire';
  options: readonly { value: string; label: string }[];
  /** Versão vigente que a publicação vai encerrar (na véspera do novo início). */
  current?: PlanView | null;
}

/** Publicação (início no dia 1º) ou aposentadoria (fim no último dia de um mês), com o impacto. */
@Component({
  selector: 'app-incentive-vigency-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ publish ? 'Publicar versão' : 'Aposentar versão' }} · {{ data.plan.code }} v{{ data.plan.version }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @if (publish) {
          <p class="app-dialog-message">A versão publicada fica imutável. A apuração de cada mês usa a versão vigente na data dos fatos; não há publicação retroativa.</p>
        } @else {
          <p class="app-dialog-message">Depois do fim da vigência, nenhum negociador é apurado por esta versão. Se não houver outro plano cobrindo, os extratos saem "sem plano".</p>
        }
        <app-field [label]="publish ? 'Início da vigência' : 'Fim da vigência'">
          <select appInput ngbAutofocus formControlName="date">
            @for (o of data.options; track o.value) {
              <option [ngValue]="o.value">{{ o.label }}</option>
            }
          </select>
        </app-field>
        @if (publish && data.current) {
          <div class="alert alert-info d-flex align-items-start gap-2" role="note">
            <app-icon name="info-circle" />
            <span>A versão vigente (v{{ data.current.version }}) termina na véspera do novo início.</span>
          </div>
        }
        <div class="alert alert-warning d-flex align-items-start gap-2" role="note">
          <app-icon name="exclamation-triangle" />
          <span>{{ publish ? 'Publicar afeta a remuneração de todos os negociadores cobertos.' : 'Aposentar encerra a remuneração por esta versão.' }} Para confirmar, digite <strong>{{ word }}</strong>.</span>
        </div>
        <app-field [label]="'Digite ' + word" error="Digite exatamente a palavra indicada.">
          <input appInput formControlName="confirm" autocomplete="off" />
        </app-field>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn" [class.btn-primary]="publish" [class.btn-danger]="!publish" type="submit" [disabled]="sending() || !confirmed()">{{ publish ? 'Publicar' : 'Aposentar' }}</button>
      </div>
    </form>
  `,
})
export class VigencyDialog {
  protected readonly data = inject<VigencyDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<PlanView>>(DialogRef);
  private readonly api = inject(CommissionPlansApi);
  protected readonly publish = this.data.mode === 'publish';
  protected readonly word = this.publish ? 'PUBLICAR' : 'APOSENTAR';
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    date: [this.data.options[0]?.value ?? '', [Validators.required]],
    confirm: ['', [(c: AbstractControl) => ((c.value as string).trim().toUpperCase() === this.word ? null : { confirmation: true })]],
  });
  private readonly typed = toSignal(this.form.controls.confirm.valueChanges.pipe(startWith('')), { initialValue: '' });
  protected readonly confirmed = computed(() => this.typed().trim().toUpperCase() === this.word);

  protected save(): void {
    if (this.form.invalid || this.sending()) {
      this.form.markAllAsTouched();
      return;
    }
    const date = this.form.getRawValue().date;
    this.sending.set(true);
    this.error.set(null);
    const call = this.publish ? this.api.publish(this.data.plan.id, date) : this.api.retire(this.data.plan.id, date);
    call.pipe(finalize(() => this.sending.set(false))).subscribe({ next: (p) => this.ref.close(p), error: (err: unknown) => this.error.set(incentiveError(err)) });
  }
}

/** Simulação da versão (inclusive rascunho) sobre um mês encerrado, comparada aos extratos efetivos. */
@Component({
  selector: 'app-incentive-simulation-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Amount, Icon, StatusChip],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Simular {{ plan.code }} v{{ plan.version }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <div class="modal-body">
      <p class="app-dialog-message">Recalcula pesos de crédito e comissão de todos os negociadores com os lançamentos liberados do mês e compara com o que foi apurado. Nada é gravado.</p>
      <form class="app-filters" [formGroup]="form" (ngSubmit)="run()" aria-label="Mês da simulação">
        <app-field label="Mês encerrado">
          <select appInput ngbAutofocus formControlName="period">
            @for (m of months; track m.value) {
              <option [ngValue]="m.value">{{ m.label }}</option>
            }
          </select>
        </app-field>
        <div class="app-filters__actions">
          <button class="btn btn-primary" type="submit" [disabled]="running()">
            <app-icon name="calculator" />
            Simular
          </button>
        </div>
      </form>
      @if (running()) {
        <p role="status" aria-live="polite">Simulando…</p>
      }
      @if (error()) {
        <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
      }
      @if (result(); as r) {
        <section aria-live="polite" aria-labelledby="sim-title">
          <h3 class="app-section-title" id="sim-title">{{ monthName(r.period) }}</h3>
          <dl class="app-definition-list mb-3">
            <dt>Recuperado creditado</dt>
            <dd><app-amount [value]="r.creditedRecovery" /></dd>
            <dt>Total simulado</dt>
            <dd><app-amount [value]="r.simulatedTotal" /> <span class="app-muted">({{ cost(r.simulatedCostPerRecovered) }} do recuperado)</span></dd>
            <dt>Total efetivo</dt>
            <dd><app-amount [value]="r.actualTotal" /> <span class="app-muted">({{ cost(r.actualCostPerRecovered) }} do recuperado)</span></dd>
            <dt>Diferença</dt>
            <dd><app-amount [value]="r.simulatedTotal - r.actualTotal" [signed]="true" /></dd>
          </dl>
          @if (r.statements.length === 0) {
            <p class="app-muted" role="status">Nenhum negociador com lançamentos liberados no mês.</p>
          } @else {
            <div class="in-scroll">
              <table class="in-table">
                <caption class="visually-hidden">Simulação por negociador</caption>
                <thead>
                  <tr>
                    <th scope="col">Negociador</th>
                    <th scope="col">Resultado</th>
                    <th scope="col" class="in-end">Recuperado</th>
                    <th scope="col" class="in-end">Simulado</th>
                    <th scope="col" class="in-end">Efetivo</th>
                  </tr>
                </thead>
                <tbody>
                  @for (s of r.statements; track s.userId) {
                    <tr>
                      <th scope="row">{{ users.name(s.userId) }}</th>
                      <td><app-status-chip domain="commissionOutcome" [status]="s.outcome" /></td>
                      <td class="in-end"><app-amount [value]="s.creditedRecovery" /></td>
                      <td class="in-end"><app-amount [value]="s.simulatedAmount" /></td>
                      <td class="in-end"><app-amount [value]="s.actualAmount" /></td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </section>
      }
    </div>
    <div class="modal-footer">
      <button class="btn btn-link" type="button" appDialogClose>Fechar</button>
    </div>
  `,
  styleUrl: '../incentive.scss',
})
export class SimulationDialog {
  protected readonly plan = inject<PlanView>(DIALOG_DATA);
  private readonly api = inject(CommissionPlansApi);
  private readonly today = zonedNow().date;
  /** Só meses encerrados (o servidor recusa o corrente). */
  protected readonly months = monthOptions(addMonths(monthOf(this.today), -1) + '-01', 11);
  protected readonly monthName = monthLabel;
  protected readonly running = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly result = signal<SimulationView | null>(null);
  protected readonly users = nameMap(inject(UserNames), () => (this.result()?.statements ?? []).map((s) => s.userId), { batch: USER_NAMES_BATCH });
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({ period: [this.months[0].value] });

  protected cost(value: number | null): string {
    return fraction(value);
  }

  protected run(): void {
    this.running.set(true);
    this.error.set(null);
    this.api
      .simulate(this.plan.id, this.form.getRawValue().period)
      .pipe(finalize(() => this.running.set(false)))
      .subscribe({ next: (r) => this.result.set(r), error: (err: unknown) => this.error.set(incentiveError(err)) });
  }
}
