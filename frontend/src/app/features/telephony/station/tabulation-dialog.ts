import { Component, DestroyRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { catchError, finalize, map, of, startWith } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Amount } from '../../../shared/components/amount/amount';
import { valueOr } from '../../../shared/data/resource-value';
import { fromLocalDate, toLocalDate } from '../../../shared/models/dates';
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
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatDatepickerModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, Amount],
  template: `
    <h2 mat-dialog-title class="tb-title">
      <mat-icon aria-hidden="true">assignment_turned_in</mat-icon>
      Tabular a chamada
    </h2>
    <form [formGroup]="form" (ngSubmit)="save()" (keydown)="onKeydown($event)" novalidate>
      <mat-dialog-content>
        <div class="tb-head">
          <span>{{ call().numberMasked ?? 'Número não identificado' }} · {{ results[call().result ?? 'ANSWERED'] }} · conversa {{ talk() }}</span>
          <span class="tb-countdown app-numeric" [class.tb-countdown--late]="remainingMs() < 120_000" role="timer" aria-live="off" data-tour="tabulation-countdown">
            <mat-icon aria-hidden="true">timer</mat-icon>
            Automática em {{ clock() }}
          </span>
        </div>
        <p class="app-alert app-alert--info" role="note">
          <mat-icon aria-hidden="true">info</mat-icon>
          <span>Tabule para ficar disponível de novo. Sem tabulação até o prazo, o sistema registra a sugestão "{{ suggestion() }}" (não conta como contato efetivo).</span>
        </p>
        @if (expired()) {
          <p class="app-alert app-alert--warning" role="status"><mat-icon aria-hidden="true">hourglass_bottom</mat-icon><span>Prazo vencido: conferindo a tabulação automática…</span></p>
        }
        @if (!call().caseId) {
          <mat-form-field class="app-full-width">
            <mat-label>Caso da chamada recebida</mat-label>
            <mat-select formControlName="caseId" required>
              @for (c of myCases(); track c.value) {
                <mat-option [value]="c.value">{{ c.label }}</mat-option>
              }
            </mat-select>
            <mat-hint>Chamada não identificada: informe o caso do devedor que ligou</mat-hint>
          </mat-form-field>
        }
        <mat-form-field class="app-full-width">
          <mat-label>Tabulação</mat-label>
          <mat-select formControlName="tabulationId" required>
            @for (t of tabulations(); track t.id) {
              <mat-option [value]="t.id">{{ t.name }}{{ t.code === call().suggestedTabulation ? ' (sugerida)' : '' }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        @if (tabulation()?.promise) {
          <div class="app-form-grid">
            <mat-form-field>
              <mat-label>Valor prometido (R$)</mat-label>
              <input matInput formControlName="promiseAmount" inputmode="decimal" />
              @if (balance() !== null) {
                <mat-hint>Saldo em aberto: <app-amount [value]="balance()" /></mat-hint>
              }
            </mat-form-field>
            <mat-form-field>
              <mat-label>Data prometida</mat-label>
              <input matInput [matDatepicker]="promise" formControlName="promiseDate" [min]="minDate" />
              <mat-datepicker-toggle matIconSuffix [for]="promise" />
              <mat-datepicker #promise />
            </mat-form-field>
          </div>
        }
        @if (tabulation()?.callback) {
          <div class="app-form-grid">
            <mat-form-field>
              <mat-label>Retornar em</mat-label>
              <input matInput [matDatepicker]="callback" formControlName="callbackDate" [min]="minDate" />
              <mat-datepicker-toggle matIconSuffix [for]="callback" />
              <mat-datepicker #callback />
            </mat-form-field>
            <mat-form-field>
              <mat-label>Hora</mat-label>
              <input matInput type="time" formControlName="callbackTime" />
            </mat-form-field>
          </div>
        }
        <mat-form-field class="app-full-width">
          <mat-label>Observação{{ tabulation()?.requiresNote ? '' : ' (opcional)' }}</mat-label>
          <textarea matInput formControlName="note" rows="3" maxlength="4000"></textarea>
        </mat-form-field>
        @if (submitted() && problems().length) {
          <ul class="app-alert app-alert--error tb-problems" role="alert">
            @for (p of problems(); track p) {
              <li>{{ p }}</li>
            }
          </ul>
        }
        @if (error()) {
          <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <span class="app-muted tb-hint">Ctrl + Enter registra</span>
        <button mat-flat-button type="submit" [disabled]="saving() || expired()">Registrar tabulação</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .tb-title {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .tb-head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      margin-bottom: 12px;
    }
    .tb-countdown {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 2px 10px;
      border-radius: var(--app-radius-pill);
      background: var(--app-info-bg);
      color: var(--app-info-fg);
      font-weight: 600;
    }
    .tb-countdown--late {
      background: var(--app-warning-bg);
      color: var(--app-warning-fg);
    }
    .tb-problems {
      display: block;
      padding-left: 28px;
    }
    .tb-hint {
      margin-right: auto;
      font-size: var(--app-text-xs);
    }
  `,
})
export class TabulationDialog {
  protected readonly data = inject<TabulationDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<TabulationDialog, CallView>>(MatDialogRef);
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

  protected readonly minDate = fromLocalDate(zonedNow().date);
  protected readonly saving = signal(false);
  protected readonly submitted = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    caseId: [''],
    tabulationId: ['', [Validators.required]],
    note: ['', [Validators.maxLength(4000)]],
    promiseAmount: [''],
    promiseDate: this.fb.control<Date | null>(null),
    callbackDate: this.fb.control<Date | null>(null),
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
      { tabulation: this.tabulation(), note: v.note, promiseAmount: parseMoney(v.promiseAmount), promiseDate: toLocalDate(v.promiseDate), callbackAt: toLocalDateTime(v.callbackDate, v.callbackTime) },
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
    if (!t || this.problems().length || this.saving()) {
      return;
    }
    const v = this.form.getRawValue();
    const callbackAt = toLocalDateTime(v.callbackDate, v.callbackTime);
    const amount = parseMoney(v.promiseAmount);
    const due = toLocalDate(v.promiseDate);
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

