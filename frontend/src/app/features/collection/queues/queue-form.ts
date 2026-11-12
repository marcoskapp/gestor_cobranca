import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { errorMessage } from '../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { AppValidators } from '../../../shared/validation/validators';
import { CreditorLookup, PortfolioLookup } from '../../organization/data/organization.lookups';
import { QueuesApi } from '../data/collection.api';
import { ORDERING_LABELS, STRATEGY_LABELS } from '../data/collection.labels';
import { TeamLookup } from '../data/collection.lookups';
import { QueueOrdering, QueueRequest, QueueStrategy, QueueView } from '../data/collection.models';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { Icon } from '../../../shared/components/icon/icon';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';

/** Faixas mínimo ≤ máximo nos critérios da fila. */
export function rangeValidator(min: string, max: string) {
  return (group: AbstractControl): ValidationErrors | null => {
    const a = group.get(min)?.value as number | null;
    const b = group.get(max)?.value as number | null;
    return a !== null && b !== null && a !== undefined && b !== undefined && a > b ? { range: `${min}-${max}` } : null;
  };
}

const num = (v: number | null | string) => (v === null || v === '' || v === undefined ? null : Number(v));

/** Máximo de horários da distribuição automática (`CollectionQueue.MAX_AUTO_TIMES`). */
export const MAX_AUTO_TIMES = 6;

/** Explicação de cada estratégia, mostrada abaixo do campo. */
export const STRATEGY_HINTS: Record<QueueStrategy, string> = {
  PULL: 'O operador pede o próximo caso; não há distribuição em lote.',
  ROUND_ROBIN: 'O "Distribuir" entrega um caso a cada operador, em rodízio.',
  LEAST_LOADED: 'O "Distribuir" entrega cada caso a quem tem menos casos ativos na fila.',
  TIERED:
    'Os casos de maior valor esperado (chance de pagamento pelo score × saldo) vão primeiro aos negociadores de nível mais alto, que também recebem mais casos (capacidade por nível). Parte dos casos de alto potencial vai em rodízio a todos. O "Próximo caso" segue a mesma lógica.',
};

/**
 * Distribuição automática ligada exige ao menos um horário e o limite por rodada; fila sob demanda não tem
 * distribuição automática; a estratégia por nível exige a capacidade por operador (base do multiplicador).
 */
export function distributionValidator(group: AbstractControl): ValidationErrors | null {
  const strategy = group.get('strategy')?.value as QueueStrategy;
  const errors: ValidationErrors = {};
  if (strategy === 'TIERED' && num(group.get('maxCasesPerUser')?.value) === null) {
    errors['tieredCapacity'] = true;
  }
  if (group.get('autoEnabled')?.value) {
    const times = ((group.get('autoTimes')?.value as string[]) ?? []).filter((t) => !!t);
    if (strategy === 'PULL') {
      errors['autoPull'] = true;
    }
    if (!times.length) {
      errors['autoTimes'] = true;
    }
    if (num(group.get('autoLimit')?.value) === null) {
      errors['autoLimit'] = true;
    }
  }
  return Object.keys(errors).length ? errors : null;
}

/**
 * Cadastro de fila: critérios opcionais (credor, carteira, faixas de atraso, saldo e score),
 * prioridade, ordem de entrega, estratégia de distribuição (inclusive por nível de desempenho), capacidade, equipes e
 * distribuição automática por horário. Salvar reprocessa o roteamento dos casos ativos em segundo plano.
 */
@Component({
  selector: 'app-queue-form',
  imports: [ReactiveFormsModule, EntityPicker, Icon, AppDatePipe, ...FORM_FIELD, ...DIALOG_DIRECTIVES],
  host: { class: 'd-flex flex-column overflow-hidden' },
  styles: `
    .qf-time {
      width: auto;
      input {
        width: 8rem;
      }
    }
  `,
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data ? 'Editar fila' : 'Nova fila' }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @for (e of errors(); track e) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert">{{ e }}</div>
        }
        <div class="app-form-grid gap-3">
          <app-field label="Nome" [error]="form.controls.name.invalid ? (message('name')) : undefined">
            <input appInput formControlName="name" maxlength="120" required />
          </app-field>
          <app-field label="Código" [hint]="(data ? 'O código não muda.' : 'Vazio = gerado pelo nome.')">
            <input appInput formControlName="code" maxlength="40" [readonly]="!!data" />
          </app-field>
          <app-field label="Prioridade" hint="Menor número = avaliada primeiro.">
            <input appInput type="number" min="0" formControlName="priority" required />
          </app-field>
          <app-field class="app-span-all" label="Descrição">
            <input appInput formControlName="description" maxlength="500" />
          </app-field>

          <h3 class="app-section-title app-span-all">Critérios (vazios = qualquer)</h3>
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor" [single]="true" hint="Fila sem credor exige acesso a todos os credores." />
          </div>
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.portfolio" [source]="portfolios" label="Carteira" [single]="true" />
          </div>
          <app-field label="Atraso mínimo (dias)">
            <input appInput type="number" min="0" formControlName="minDaysOverdue" />
          </app-field>
          <app-field label="Atraso máximo (dias)">
            <input appInput type="number" min="0" formControlName="maxDaysOverdue" />
          </app-field>
          <app-field label="Saldo mínimo">
            <div class="input-group">
              <span class="input-group-text" aria-hidden="true">R$</span>
              <input appInput appMoney formControlName="minBalance" />
            </div>
          </app-field>
          <app-field label="Saldo máximo">
            <div class="input-group">
              <span class="input-group-text" aria-hidden="true">R$</span>
              <input appInput appMoney formControlName="maxBalance" />
            </div>
          </app-field>
          <app-field label="Score mínimo">
            <input appInput type="number" formControlName="minScore" />
          </app-field>
          <app-field label="Score máximo">
            <input appInput type="number" formControlName="maxScore" />
          </app-field>
          <div class="form-check form-switch app-span-all">
            <input class="form-check-input" type="checkbox" role="switch" id="queue-vulnerable" formControlName="vulnerableOnly" />
            <label class="form-check-label" for="queue-vulnerable">Só devedores com marcador de vulnerabilidade (fila de atendimento cuidadoso)</label>
            <div class="form-text">Use com prioridade menor que a das demais filas do credor para que esses casos caiam aqui primeiro.</div>
          </div>
          <div class="form-check form-switch app-span-all" data-tour="queue-new-contact">
            <input class="form-check-input" type="checkbox" role="switch" id="queue-new-contact" formControlName="newContactOnly" aria-describedby="queue-new-contact-hint" />
            <label class="form-check-label" for="queue-new-contact">Só devedores com contato novo</label>
            <div class="form-text" id="queue-new-contact-hint">Telefone, e-mail ou endereço recebido numa nova carga e ainda sem tentativa. O caso sai da fila quando todos os contatos novos do devedor forem tentados ou, vencido o prazo, na revisão diária da madrugada.</div>
          </div>
          <app-field label="Tipo de pessoa" hint="Pessoa física (CPF) ou jurídica (CNPJ).">
            <select appInput formControlName="personType">
              <option value="">Qualquer</option>
              <option value="INDIVIDUAL">Pessoa física</option>
              <option value="COMPANY">Pessoa jurídica</option>
            </select>
          </app-field>
          @if (form.errors?.['range']) {
            <p class="app-field-error app-span-all" role="alert">O mínimo de cada faixa não pode passar do máximo.</p>
          }

          <h3 class="app-section-title app-span-all">Entrega e distribuição</h3>
          <app-field label="Ordem de entrega">
            <select appInput formControlName="ordering">
              @for (o of orderings; track o.value) {
                <option [ngValue]="o.value">{{ o.label }}</option>
              }
            </select>
          </app-field>
          <app-field label="Estratégia" [hint]="strategyHint()">
            <select appInput formControlName="strategy" data-tour="queue-strategy">
              @for (s of strategies; track s.value) {
                <option [ngValue]="s.value">{{ s.label }}</option>
              }
            </select>
          </app-field>
          <app-field label="Casos por operador" [hint]="strategy() === 'TIERED' ? 'Obrigatório nesta estratégia: é multiplicado pelo nível de cada negociador.' : 'Vazio = sem limite.'">
            <input appInput type="number" min="1" formControlName="maxCasesPerUser" [required]="strategy() === 'TIERED'" />
          </app-field>
          @if (form.errors?.['tieredCapacity'] && (form.touched || form.dirty)) {
            <p class="app-field-error app-span-all" role="alert">Informe os casos por operador: a distribuição por nível multiplica esse número pelo nível de cada negociador.</p>
          }
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.teams" [source]="teams" label="Equipes que atendem" hint="Vazio = qualquer operador." />
          </div>

          <h3 class="app-section-title app-span-all" id="queue-auto-title">Distribuição automática</h3>
          <div class="app-span-all" data-tour="queue-auto-distribution" role="group" aria-labelledby="queue-auto-title">
            @if (strategy() === 'PULL') {
              <p class="app-muted mb-0">Disponível nas estratégias de distribuição em lote (rodízio, menor carga ou por nível de desempenho).</p>
            } @else {
              <div class="form-check form-switch">
                <input class="form-check-input" type="checkbox" role="switch" id="queue-auto" formControlName="autoEnabled" aria-describedby="queue-auto-hint" />
                <label class="form-check-label" for="queue-auto">Distribuir sozinha nos horários abaixo</label>
                <div class="form-text" id="queue-auto-hint">Em dias úteis (segunda a sexta, fora dos feriados do calendário), no horário de Brasília, uma vez por horário, com a mesma regra do botão "Distribuir". A carga atual de cada operador conta para a capacidade.</div>
              </div>
            }
          </div>
          @if (strategy() !== 'PULL' && form.controls.autoEnabled.value) {
            <div class="app-span-all">
              <span class="form-label d-block" id="queue-auto-times">Horários</span>
              <div class="d-flex flex-wrap gap-2 align-items-center" role="group" aria-labelledby="queue-auto-times">
                @for (control of form.controls.autoTimes.controls; track $index; let i = $index) {
                  <div class="input-group qf-time">
                    <input class="form-control" type="time" step="60" [formControl]="control" [attr.aria-label]="'Horário ' + (i + 1)" />
                    <button class="btn btn-outline-secondary" type="button" (click)="removeTime(i)" [attr.aria-label]="'Remover horário ' + (i + 1)">
                      <app-icon name="x-lg" />
                    </button>
                  </div>
                }
                @if (form.controls.autoTimes.length < maxTimes) {
                  <button class="btn btn-outline-secondary btn-sm" type="button" (click)="addTime()">
                    <app-icon name="plus-lg" />
                    Adicionar horário
                  </button>
                }
              </div>
              @if (form.errors?.['autoTimes'] && (form.touched || form.dirty)) {
                <p class="app-field-error" role="alert">Informe ao menos um horário.</p>
              }
            </div>
            <app-field label="Casos por rodada" hint="Máximo de casos distribuídos em cada horário (1 a 1.000).">
              <input appInput type="number" min="1" max="1000" formControlName="autoLimit" />
            </app-field>
            @if (form.errors?.['autoLimit'] && (form.touched || form.dirty)) {
              <p class="app-field-error app-span-all" role="alert">Informe quantos casos distribuir em cada horário.</p>
            }
          }
          @if (lastRun(); as at) {
            <p class="app-muted app-span-all mb-0">Última rodada automática ou mudança da configuração: {{ at | appDate }}.</p>
          }
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Salvar fila</button>
      </div>
    </form>
  `,
})
export class QueueForm {
  protected readonly data = inject<QueueView | null>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<QueueView>>(DialogRef);
  private readonly api = inject(QueuesApi);
  private readonly notifications = inject(NotificationService);
  protected readonly creditors = inject(CreditorLookup);
  protected readonly teams = inject(TeamLookup);

  protected readonly orderings = Object.entries(ORDERING_LABELS).map(([value, label]) => ({ value: value as QueueOrdering, label }));
  protected readonly strategies = Object.entries(STRATEGY_LABELS).map(([value, label]) => ({ value: value as QueueStrategy, label }));
  protected readonly saving = signal(false);
  protected readonly errors = signal<string[]>([]);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group(
    {
      code: [this.data?.code ?? '', [Validators.maxLength(40)]],
      name: [this.data?.name ?? '', [AppValidators.notBlank, Validators.maxLength(120)]],
      description: [this.data?.description ?? '', [Validators.maxLength(500)]],
      priority: this.fb.control<number | null>(this.data?.priority ?? 100, [Validators.required, Validators.min(0)]),
      creditor: this.fb.control<string[]>(this.data?.creditorId ? [this.data.creditorId] : []),
      portfolio: this.fb.control<string[]>(this.data?.portfolioId ? [this.data.portfolioId] : []),
      minDaysOverdue: this.fb.control<number | null>(this.data?.minDaysOverdue ?? null, [Validators.min(0)]),
      maxDaysOverdue: this.fb.control<number | null>(this.data?.maxDaysOverdue ?? null, [Validators.min(0)]),
      minBalance: this.fb.control<number | null>(this.data?.minBalance ?? null, [Validators.min(0)]),
      maxBalance: this.fb.control<number | null>(this.data?.maxBalance ?? null, [Validators.min(0)]),
      minScore: this.fb.control<number | null>(this.data?.minScore ?? null),
      maxScore: this.fb.control<number | null>(this.data?.maxScore ?? null),
      ordering: this.fb.control<QueueOrdering>(this.data?.ordering ?? 'OLDEST_OVERDUE'),
      strategy: this.fb.control<QueueStrategy>(this.data?.strategy ?? 'PULL'),
      maxCasesPerUser: this.fb.control<number | null>(this.data?.maxCasesPerUser ?? null, [Validators.min(1)]),
      teams: this.fb.control<string[]>(this.data?.teamIds ?? []),
      vulnerableOnly: this.fb.control<boolean>(this.data?.vulnerableOnly ?? false),
      newContactOnly: this.fb.control<boolean>(this.data?.newContactOnly ?? false),
      personType: this.fb.control<'' | 'INDIVIDUAL' | 'COMPANY'>(this.data?.personType ?? ''),
      autoEnabled: this.fb.control<boolean>(this.data?.autoDistribution?.enabled ?? false),
      autoTimes: this.fb.array((this.data?.autoDistribution?.times ?? []).map((t) => this.fb.control<string>(t))),
      autoLimit: this.fb.control<number | null>(this.data?.autoDistribution?.limit ?? 200, [Validators.min(1), Validators.max(1000)]),
    },
    {
      validators: [
        rangeValidator('minDaysOverdue', 'maxDaysOverdue'),
        rangeValidator('minBalance', 'maxBalance'),
        rangeValidator('minScore', 'maxScore'),
        distributionValidator,
      ],
    },
  );
  protected readonly maxTimes = MAX_AUTO_TIMES;
  protected readonly strategy = toSignal(this.form.controls.strategy.valueChanges, { initialValue: this.form.controls.strategy.value });
  protected readonly strategyHint = computed(() => STRATEGY_HINTS[this.strategy()]);
  protected readonly lastRun = signal(this.data?.autoDistribution?.lastRunAt ?? null);

  protected addTime(): void {
    if (this.form.controls.autoTimes.length < MAX_AUTO_TIMES) {
      this.form.controls.autoTimes.push(this.fb.control<string>(this.form.controls.autoTimes.length ? '' : '08:00'));
      this.form.markAsDirty();
    }
  }

  protected removeTime(index: number): void {
    this.form.controls.autoTimes.removeAt(index);
    this.form.markAsDirty();
  }
  protected readonly portfolios = inject(PortfolioLookup).source(() => this.form.controls.creditor.value[0] ?? null);

  protected message(field: 'name'): string {
    return errorMessage(this.form.controls[field].errors);
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const request: QueueRequest = {
      code: this.data ? null : v.code.trim() || null,
      name: v.name.trim(),
      description: v.description.trim() || null,
      priority: Number(v.priority),
      creditorId: v.creditor[0] ?? null,
      portfolioId: v.portfolio[0] ?? null,
      minDaysOverdue: num(v.minDaysOverdue),
      maxDaysOverdue: num(v.maxDaysOverdue),
      minBalance: num(v.minBalance),
      maxBalance: num(v.maxBalance),
      minScore: num(v.minScore),
      maxScore: num(v.maxScore),
      ordering: v.ordering,
      strategy: v.strategy,
      maxCasesPerUser: num(v.maxCasesPerUser),
      teamIds: v.teams,
      vulnerableOnly: v.vulnerableOnly,
      newContactOnly: v.newContactOnly,
      personType: v.personType || null,
      autoDistribution: {
        // fila sob demanda não tem distribuição automática: o envio desliga
        enabled: v.strategy !== 'PULL' && v.autoEnabled,
        times: [...new Set(v.autoTimes.filter((t) => !!t).map((t) => t.slice(0, 5)))].sort(),
        limit: num(v.autoLimit),
      },
    };
    this.saving.set(true);
    this.errors.set([]);
    (this.data ? this.api.update(this.data.id, request) : this.api.create(request))
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.notifications.success('Fila salva. O roteamento dos casos ativos é reprocessado em segundo plano.');
          this.ref.close(saved);
        },
        error: (err: unknown) => this.errors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}
