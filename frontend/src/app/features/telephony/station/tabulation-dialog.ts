import { Component, DestroyRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { catchError, finalize, map, of, startWith } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { IsoDateAdapter } from '../../../core/config/bootstrap.config';
import { Amount } from '../../../shared/components/amount/amount';
import { Icon } from '../../../shared/components/icon/icon';
import { valueOr } from '../../../shared/data/resource-value';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { fromLocalDate } from '../../../shared/models/dates';
import { CaseWorkApi, CasesApi, CollectionSettingsApi, TabulationsApi } from '../../collection/data/collection.api';
import { interactionProblems, toLocalDateTime, zonedNow } from '../../collection/data/collection.rules';
import { parseMoney } from '../../payment/data/payment.rules';
import { CallsApi } from '../data/telephony.api';
import { AUTO_TABULATION_LABELS, CALL_RESULT_LABELS, TELEPHONY_ERROR_LABELS } from '../data/telephony.labels';
import { CallView } from '../data/telephony.models';
import { clockText, durationText, tabulationDeadline } from '../data/telephony.rules';
import { TelephonySession } from './telephony-session';

export interface TabulationDialogData {
  call: CallView;
}

/**
 * Tabulação obrigatória ao fim da chamada (pós-atendimento): o diálogo não fecha sem tabular. A
 * contagem regressiva mostra quanto falta para a tabulação automática pela sugestão do resultado;
 * vencido o prazo, o diálogo confere a chamada e fecha.
 */
@Component({
  selector: 'app-tabulation-dialog',
  imports: [ReactiveFormsModule, NgbInputDatepicker, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon, Amount],
  template: `
    <!-- sem botão de fechar: a tabulação é obrigatória (o launcher abre sem Esc e sem clique no fundo) -->
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0 d-flex align-items-center gap-2">
        <app-icon name="clipboard-check" />
        Tabular a chamada
      </h2>
    </div>
    <!-- o formulário envolve corpo e rodapé; overflow-hidden mantém a rolagem só no corpo (modal rolável) -->
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" (keydown)="onKeydown($event)" novalidate>
      <div class="modal-body">
        <div class="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-3">
          <span>{{ call().numberMasked ?? 'Número não identificado' }} · {{ results[call().result ?? 'ANSWERED'] }} · conversa {{ talk() }}</span>
          <span
            class="badge rounded-pill border d-inline-flex align-items-center gap-1 fs-6 fw-semibold app-numeric"
            [class]="remainingMs() < 120_000 ? 'bg-warning-subtle text-warning-emphasis border-warning-subtle' : 'bg-info-subtle text-info-emphasis border-info-subtle'"
            role="timer"
            aria-live="off"
            data-tour="tabulation-countdown"
          >
            <app-icon name="stopwatch" />
            Automática em {{ clock() }}
          </span>
        </div>
        <p class="alert alert-info d-flex gap-2" role="note">
          <app-icon name="info-circle" />
          <span>Tabule para ficar disponível de novo. Sem tabulação até o prazo, o sistema registra a sugestão "{{ suggestion() }}" (não conta como contato efetivo).</span>
        </p>
        @if (expired()) {
          <p class="alert alert-warning d-flex gap-2" role="status"><app-icon name="hourglass-bottom" /><span>Prazo vencido: conferindo a tabulação automática…</span></p>
        }
        <div class="d-grid gap-3">
          @if (!call().caseId) {
            <app-field label="Caso da chamada recebida" hint="Chamada não identificada: informe o caso do devedor que ligou">
              <select appInput formControlName="caseId" required>
                <option value="" disabled>Selecione</option>
                @for (c of myCases(); track c.value) {
                  <option [value]="c.value">{{ c.label }}</option>
                }
              </select>
            </app-field>
          }
          <app-field label="Tabulação">
            <select appInput formControlName="tabulationId" required>
              <option value="" disabled>Selecione</option>
              @for (t of tabulations(); track t.id) {
                <option [value]="t.id">{{ t.name }}{{ t.code === call().suggestedTabulation ? ' (sugerida)' : '' }}</option>
              }
            </select>
          </app-field>
          @if (tabulation()?.promise) {
            <div class="row g-3">
              <div class="col-sm-6">
                <app-field label="Valor prometido (R$)">
                  <input appInput formControlName="promiseAmount" inputmode="decimal" />
                </app-field>
                @if (balance() !== null) {
                  <div class="form-text">Saldo em aberto: <app-amount [value]="balance()" /></div>
                }
              </div>
              <app-field class="col-sm-6" label="Data prometida">
                <div class="input-group">
                  <input appInput formControlName="promiseDate" ngbDatepicker #promise="ngbDatepicker" [minDate]="minDate" container="body" placeholder="dd/mm/aaaa" autocomplete="off" />
                  <button class="btn btn-outline-secondary" type="button" (click)="promise.toggle()" aria-label="Abrir calendário"><app-icon name="calendar-event" /></button>
                </div>
              </app-field>
            </div>
          }
          @if (tabulation()?.callback) {
            <div class="row g-3">
              <app-field class="col-sm-6" label="Retornar em">
                <div class="input-group">
                  <input appInput formControlName="callbackDate" ngbDatepicker #callback="ngbDatepicker" [minDate]="minDate" container="body" placeholder="dd/mm/aaaa" autocomplete="off" />
                  <button class="btn btn-outline-secondary" type="button" (click)="callback.toggle()" aria-label="Abrir calendário"><app-icon name="calendar-event" /></button>
                </div>
              </app-field>
              <app-field class="col-sm-6" label="Hora">
                <input appInput type="time" formControlName="callbackTime" />
              </app-field>
            </div>
          }
          <app-field [label]="'Observação' + (tabulation()?.requiresNote ? '' : ' (opcional)')">
            <textarea appInput formControlName="note" rows="3" maxlength="4000"></textarea>
          </app-field>
        </div>
        @if (submitted() && problems().length) {
          <ul class="alert alert-danger mt-3 mb-0 ps-4" role="alert">
            @for (p of problems(); track p) {
              <li>{{ p }}</li>
            }
          </ul>
        }
        @if (error()) {
          <p class="alert alert-danger d-flex gap-2 mt-3 mb-0" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></p>
        }
      </div>
      <div class="modal-footer">
        <span class="app-muted small me-auto">Ctrl + Enter registra</span>
        <button class="btn btn-primary" type="submit" [disabled]="saving() || expired()">Registrar tabulação</button>
      </div>
    </form>
  `,
})
export class TabulationDialog {
  protected readonly data = inject<TabulationDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<CallView>>(DialogRef);
  private readonly calls = inject(CallsApi);
  private readonly cases = inject(CasesApi);
  private readonly session = inject(TelephonySession);
  protected readonly results = CALL_RESULT_LABELS;
  private readonly tabulationsApi = inject(TabulationsApi);
  private readonly settingsApi = inject(CollectionSettingsApi);
  private readonly workApi = inject(CaseWorkApi);
  protected readonly call = signal(this.data.call);

  private readonly tabulationList = rxResource({ stream: () => this.tabulationsApi.list(true) });
  private readonly tabulationValues = valueOr(this.tabulationList, []);
  protected readonly tabulations = computed(() =>
    this.tabulationValues()
      .filter((t) => t.active)
      .sort((a, b) => a.displayOrder - b.displayOrder || a.name.localeCompare(b.name)),
  );
  private readonly settings = rxResource({ stream: () => this.settingsApi.get().pipe(catchError(() => of(null))) });
  private readonly sheet = rxResource({
    params: () => this.call().caseId ?? undefined,
    stream: ({ params }) => this.cases.sheet(params).pipe(catchError(() => of(null))),
  });
  protected readonly balance = computed(() => (this.sheet.hasValue() ? (this.sheet.value()?.collectionCase.openBalance ?? null) : null));
  private readonly casesList = rxResource({
    params: () => (this.call().caseId ? undefined : true),
    stream: () => this.workApi.myCases({ page: 0, size: 100 }).pipe(map((p) => p.content.map((c) => ({ value: c.id, label: `Caso ${c.id.slice(0, 8)} · ${c.daysOverdue} dias` })))),
  });
  protected readonly myCases = valueOr(this.casesList, []);

  /** Hoje no fuso da operação, no formato do datepicker (o modelo dos campos é ISO `yyyy-MM-dd`). */
  protected readonly minDate = new IsoDateAdapter().fromModel(zonedNow().date)!;
  protected readonly saving = signal(false);
  protected readonly submitted = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    caseId: [''],
    tabulationId: ['', [Validators.required]],
    note: ['', [Validators.maxLength(4000)]],
    promiseAmount: [''],
    promiseDate: this.fb.control<string | null>(null),
    callbackDate: this.fb.control<string | null>(null),
    callbackTime: [''],
  });
  private readonly values = toSignal(this.form.valueChanges.pipe(startWith(null)), { initialValue: null });
  protected readonly tabulation = computed(() => {
    this.values();
    return this.tabulations().find((t) => t.id === this.form.controls.tabulationId.value) ?? null;
  });
  protected readonly suggestion = computed(() => this.tabulations().find((t) => t.code === this.call().suggestedTabulation)?.name ?? AUTO_TABULATION_LABELS[this.call().suggestedTabulation ?? ''] ?? 'atendida sem tabulação');
  protected readonly problems = computed(() => {
    this.values();
    const v = this.form.getRawValue();
    const out = interactionProblems(
      { tabulation: this.tabulation(), note: v.note, promiseAmount: parseMoney(v.promiseAmount), promiseDate: this.date('promiseDate'), callbackAt: toLocalDateTime(fromLocalDate(this.date('callbackDate')), v.callbackTime) },
      { openBalance: this.balance() ?? Number.MAX_SAFE_INTEGER, today: zonedNow().date, promiseMaxDays: this.settings.hasValue() ? (this.settings.value()?.promiseMaxDays ?? 30) : 30 },
      zonedNow(),
    );
    if (!this.call().caseId && !v.caseId) {
      out.unshift('Informe o caso da chamada recebida.');
    }
    return out;
  });

  private readonly deadline = tabulationDeadline(this.data.call) ?? new Date(Date.now() + 15 * 60_000);
  private readonly now = signal(Date.now());
  protected readonly remainingMs = computed(() => this.deadline.getTime() - this.now());
  protected readonly clock = computed(() => clockText(this.remainingMs()));
  protected readonly expired = computed(() => this.remainingMs() <= 0);
  protected readonly talk = computed(() => durationText(this.call().talkSeconds));
  private checkedExpired = false;

  constructor() {
    const timer = setInterval(() => {
      this.now.set(Date.now());
      if (this.expired() && !this.checkedExpired) {
        this.checkedExpired = true;
        // o servidor tabula pela sugestão (job): confere e libera a tela
        setTimeout(() => this.reloadAfterDeadline(), 3000);
      }
    }, 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
    // sugestão do resultado pré-selecionada (o operador troca se quiser)
    effect(() => {
      const suggested = this.tabulations().find((t) => t.code === this.call().suggestedTabulation);
      untracked(() => {
        if (suggested && !this.form.controls.tabulationId.value) {
          this.form.controls.tabulationId.setValue(suggested.id);
        }
      });
    });
  }

  protected onKeydown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
      event.preventDefault();
      this.save();
    }
  }

  protected save(): void {
    this.submitted.set(true);
    const t = this.tabulation();
    if (this.form.invalid) {
      this.form.markAllAsTouched();
    }
    if (!t || this.problems().length || this.form.invalid || this.saving()) {
      return;
    }
    const v = this.form.getRawValue();
    const callbackAt = toLocalDateTime(fromLocalDate(this.date('callbackDate')), v.callbackTime);
    const amount = parseMoney(v.promiseAmount);
    const due = this.date('promiseDate');
    this.saving.set(true);
    this.error.set(null);
    this.calls
      .tabulate(this.call().id, {
        tabulationCode: t.code,
        note: v.note.trim() || null,
        promise: t.promise && amount && due ? { amount, dueDate: due } : null,
        callback: t.callback && callbackAt ? { dueAt: callbackAt, assigneeUserId: null, note: null } : null,
        caseId: this.call().caseId ? null : v.caseId || null,
      })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (call) => this.done(call),
        error: (err: unknown) => {
          const e = toApiError(err);
          if (e.code === 'call.already_tabulated') {
            this.reloadAfterDeadline();
            return;
          }
          this.error.set((e.code && TELEPHONY_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }

  /** Data ISO do datepicker; texto digitado inválido (erro `ngbDate`) conta como não informado. */
  private date(name: 'promiseDate' | 'callbackDate'): string | null {
    const control = this.form.controls[name];
    return control.valid ? control.value : null;
  }

  private reloadAfterDeadline(): void {
    this.calls
      .get(this.call().id)
      .pipe(catchError(() => of(null)))
      .subscribe((call) => {
        if (call && call.interactionState !== 'AWAITING_TABULATION') {
          this.done(call);
        } else if (call) {
          this.call.set(call);
          this.checkedExpired = false;
          // job ainda não rodou: confere de novo em instantes
          setTimeout(() => this.reloadAfterDeadline(), 10_000);
        }
      });
  }

  private done(call: CallView): void {
    this.session.tabulated(call);
    this.ref.close(call);
  }
}

