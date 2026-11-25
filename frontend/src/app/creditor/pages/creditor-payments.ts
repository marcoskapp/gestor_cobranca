import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbCalendar, NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { finalize } from 'rxjs';
import { ApiError, toApiError, userMessage } from '../../core/http/problem';
import { Amount } from '../../shared/components/amount/amount';
import { CellDef } from '../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../shared/components/data-table/data-table';
import { Icon } from '../../shared/components/icon/icon';
import { StatusChip } from '../../shared/components/status-chip/status-chip';
import { DialogRef } from '../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../shared/dialog/dialog.directives';
import { DialogService } from '../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../shared/forms/field';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { CreditorPaymentsApi } from '../data/creditor-payments.api';
import { CreditorSession } from '../data/creditor-session';
import {
  CREDITOR_PAYMENT_METHOD_LABELS,
  CREDITOR_REJECTION_REASON_LABELS,
  CreditorPaymentMethod,
  CreditorPermission,
  CreditorReportedPayment,
  CreditorTitleMatch,
} from '../data/creditor.models';

const MAX_TITLES = 20;
const MAX_BYTES = 10 * 1024 * 1024;
const ACCEPT = '.pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg';
const ALLOWED_NAME = /\.(pdf|png|jpe?g)$/i;

function reportMessage(error: ApiError): string {
  switch (error.code) {
    case 'storage.pdf_busy':
      return error.detail ?? 'Muitos arquivos sendo conferidos agora. Tente de novo em alguns segundos.';
    case 'idempotency.key_reused':
      return 'Este informe já foi enviado. Feche a janela e confira a lista antes de informar de novo.';
    case 'creditor_step_up.invalid':
    case 'creditor_step_up.proof_invalid':
      return error.detail ?? 'Código do autenticador inválido. Digite o código atual do aplicativo.';
    case 'payment.creditor_report_limit':
    case 'payment.creditor_report_duplicate':
    case 'payment.creditor_title_not_found':
    case 'payment.creditor_titles_mixed':
    case 'payment.creditor_amount_above_balance':
    case 'payment.creditor_agreement_paid':
    case 'payment.evidence_active_content':
    case 'payment.evidence_unreadable':
    case 'payment.evidence_rejected':
    case 'payment.evidence_type_mismatch':
      return error.detail ?? 'Não foi possível registrar o informe.';
    default:
      break;
  }
  if (error.status === 503) {
    return 'A verificação de segurança do comprovante está indisponível agora. Tente de novo em alguns minutos.';
  }
  if (error.status === 429) {
    return 'Muitas tentativas em pouco tempo. Aguarde um minuto e tente de novo.';
  }
  if (error.status === 422 || error.status === 409) {
    return error.detail ?? 'Não foi possível registrar o informe. Confira os dados e tente de novo.';
  }
  return userMessage(error);
}

/**
 * Informar pagamento recebido diretamente pelo credor: números dos títulos (conferidos um a um), valor, data,
 * comprovante e o código do autenticador. O informe fica em análise na assessoria; nada é baixado antes da aprovação.
 */
@Component({
  selector: 'app-creditor-payment-report-dialog',
  imports: [ReactiveFormsModule, NgbInputDatepicker, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Amount, AppDatePipe, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Informar pagamento recebido</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="submit()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">
          Informe aqui o pagamento que o devedor fez diretamente a você. A assessoria confere o comprovante e, ao aprovar,
          dá baixa nos títulos (ou nas parcelas do acordo). O informe não pode ser alterado depois de enviado.
        </p>

        <app-field label="Título" hint="O número do título no seu sistema. Adicione um por vez (até 20).">
          <div class="input-group">
            <input appInput ngbAutofocus #numberInput formControlName="number" maxlength="60" autocomplete="off" spellcheck="false" (keydown.enter)="$event.preventDefault(); addTitle()" />
            <button type="button" class="btn btn-outline-primary" (click)="addTitle()" [disabled]="searching() || titles().length >= max" [attr.aria-busy]="searching()">
              <app-icon name="plus-lg" /> {{ searching() ? 'Conferindo…' : 'Adicionar' }}
            </button>
          </div>
        </app-field>
        <div aria-live="polite">
          @if (titleError(); as e) {
            <div class="alert alert-warning mt-2 mb-0">{{ e }}</div>
          }
        </div>
        @if (titles().length) {
          <ul class="list-unstyled mt-2 mb-3">
            @for (t of titles(); track t.number) {
              <li class="d-flex flex-wrap align-items-center gap-2">
                <strong>{{ t.number }}</strong>
                <span class="app-muted">{{ t.installmentNumber ? 'parcela ' + t.installmentNumber + ' · ' : '' }}{{ t.dueDate ? 'vence ' + (t.dueDate | appDate: 'date') + ' · ' : '' }}saldo <app-amount [value]="t.openBalance" /></span>
                @if (t.underAgreement) {
                  <span class="badge rounded-pill bg-info-subtle text-info-emphasis">Em acordo</span>
                }
                <button type="button" class="btn btn-link btn-sm" (click)="removeTitle(t.number)" [attr.aria-label]="'Remover título ' + t.number">Remover</button>
              </li>
            }
          </ul>
        }
        @if (mixed()) {
          <div class="alert alert-warning mb-3">Parte dos títulos está num acordo e parte não. Informe os pagamentos separadamente.</div>
        } @else if (openTotal() !== null) {
          <p class="app-muted mb-3">Saldo em aberto desses títulos: <app-amount [value]="openTotal()!" /></p>
        } @else if (titles().length) {
          <p class="app-muted mb-3">Títulos em acordo: o valor vai para as parcelas em aberto do acordo, da mais antiga para a mais nova.</p>
        }
        @if (submitted() && !titles().length) {
          <div class="invalid-feedback d-block" role="alert">
            {{ form.controls.number.value.trim() ? 'Clique em Adicionar para conferir o título digitado.' : 'Adicione ao menos um título.' }}
          </div>
        }

        <div class="app-form-grid mb-3">
          <app-field label="Valor pago" error="Informe o valor pago (maior que zero).">
            <div class="input-group">
              <span class="input-group-text" aria-hidden="true">R$</span>
              <input appInput appMoney formControlName="amount" required [min]="0.01" />
            </div>
            @if (aboveBalance()) {
              <div class="invalid-feedback d-block" role="alert">O valor é maior que o saldo em aberto desses títulos. Confira o valor ou os títulos.</div>
            }
          </app-field>
          <app-field label="Data do pagamento" error="Informe a data (não pode ser futura).">
            <div class="input-group">
              <input appInput ngbDatepicker #paid="ngbDatepicker" placeholder="dd/mm/aaaa" formControlName="paidOn" [maxDate]="today" required />
              <button type="button" class="btn btn-outline-secondary" (click)="paid.toggle()" aria-label="Abrir calendário"><app-icon name="calendar-event" /></button>
            </div>
          </app-field>
          <app-field label="Meio">
            <select appInput formControlName="method">
              @for (m of methods; track m[0]) {
                <option [value]="m[0]">{{ m[1] }}</option>
              }
            </select>
          </app-field>
          <app-field label="Referência (opcional)" hint="Número do comprovante ou da transação, sem nome nem documento de quem pagou.">
            <input appInput formControlName="reference" maxlength="100" autocomplete="off" spellcheck="false" />
          </app-field>
        </div>
        <app-field class="mb-3" label="Observação (opcional)" hint="Vai para a assessoria. Não escreva nome, CPF ou dados bancários de quem pagou.">
          <textarea appInput formControlName="note" rows="2" maxlength="1000"></textarea>
        </app-field>

        <input #file type="file" [accept]="accept" (change)="pick(file.files); file.value = ''" hidden aria-label="Escolher comprovante" />
        <button type="button" class="btn btn-outline-primary" (click)="file.click()">
          <app-icon name="paperclip" /> {{ evidence() ? 'Trocar comprovante' : 'Anexar comprovante' }}
        </button>
        <span class="ms-2 text-break" aria-live="polite">{{ evidence()?.name }}</span>
        <div class="form-text">PDF, PNG ou JPEG de até 10 MB. O arquivo passa por verificação de vírus.</div>
        <div aria-live="polite">
          @if (fileError(); as e) {
            <div class="alert alert-warning mt-2 mb-0">{{ e }}</div>
          }
        </div>
        @if (submitted() && !evidence()) {
          <div class="invalid-feedback d-block" role="alert">Anexe o comprovante do pagamento.</div>
        }

        <app-field class="mt-3" label="Código do autenticador" error="Digite os 6 dígitos." hint="O código de 6 dígitos do aplicativo autenticador.">
          <input appInput formControlName="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" required (input)="onlyDigits()" />
        </app-field>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2 mt-3" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="busy()">Enviar para análise</button>
      </div>
    </form>
  `,
})
export class CreditorPaymentReportDialog {
  private readonly ref = inject<DialogRef<CreditorReportedPayment>>(DialogRef);
  private readonly api = inject(CreditorPaymentsApi);
  protected readonly today = inject(NgbCalendar).getToday();
  protected readonly max = MAX_TITLES;
  protected readonly accept = ACCEPT;
  protected readonly methods = Object.entries(CREDITOR_PAYMENT_METHOD_LABELS) as [CreditorPaymentMethod, string][];
  /** Uma chave por diálogo: reenviar depois de um erro de rede não cria um segundo informe. */
  private readonly idempotencyKey = globalThis.crypto.randomUUID();
  protected readonly titles = signal<CreditorTitleMatch[]>([]);
  private readonly numberInput = viewChild<ElementRef<HTMLInputElement>>('numberInput');
  protected readonly searching = signal(false);
  protected readonly titleError = signal<string | null>(null);
  protected readonly evidence = signal<File | null>(null);
  protected readonly fileError = signal<string | null>(null);
  protected readonly submitted = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    number: [''],
    amount: [null as number | null, [Validators.required, Validators.min(0.01)]],
    /** Data no formato ISO (o adaptador do datepicker do app). */
    paidOn: [null as string | null, [Validators.required]],
    method: ['PIX' as CreditorPaymentMethod],
    reference: ['', [Validators.maxLength(100)]],
    note: ['', [Validators.maxLength(1000)]],
    code: ['', [Validators.required, Validators.pattern(/^\d{6}$/)]],
  });
  private readonly amount = toSignal(this.form.controls.amount.valueChanges, { initialValue: null as number | null });
  /** Mistura de títulos com e sem acordo: o servidor recusa; a tela avisa antes do código. */
  protected readonly mixed = computed(() => {
    const t = this.titles();
    return t.some((x) => x.underAgreement) && t.some((x) => !x.underAgreement);
  });
  /** Saldo somado só sem acordo (com acordo, o limite é o saldo do acordo, que o servidor confere). */
  protected readonly openTotal = computed(() => {
    const t = this.titles();
    return t.length && !t.some((x) => x.underAgreement) ? t.reduce((s, x) => s + Number(x.openBalance), 0) : null;
  });
  protected readonly aboveBalance = computed(() => {
    const a = this.amount();
    const o = this.openTotal();
    return a != null && o != null && Number(a) > o + 0.005;
  });

  protected addTitle(): void {
    const number = this.form.controls.number.value.trim();
    this.titleError.set(null);
    if (!number || this.searching() || this.titles().length >= this.max) {
      return;
    }
    if (this.titles().some((t) => t.number === number)) {
      this.titleError.set(`O título ${number} já está na lista.`);
      return;
    }
    this.searching.set(true);
    this.api.findTitles(number).pipe(finalize(() => this.searching.set(false))).subscribe({
      next: (found) => {
        if (!found.length) {
          this.titleError.set(`Título ${number} não encontrado ou sem saldo em aberto. Confira o número.`);
          return;
        }
        this.titles.update((list) => (list.some((t) => t.number === found[0].number) ? list : [...list, found[0]]));
        this.form.controls.number.setValue('');
      },
      error: (err: unknown) => this.titleError.set(reportMessage(toApiError(err))),
    });
  }

  protected removeTitle(number: string): void {
    this.titles.update((list) => list.filter((t) => t.number !== number));
    this.numberInput()?.nativeElement.focus();
  }

  protected pick(list: FileList | null): void {
    const f = list?.[0] ?? null;
    this.fileError.set(null);
    if (!f) {
      return;
    }
    if (f.size === 0 || f.size > MAX_BYTES || !ALLOWED_NAME.test(f.name)) {
      this.fileError.set(`${f.name}: envie um PDF, PNG ou JPEG de até 10 MB.`);
      return;
    }
    this.evidence.set(f);
  }

  protected onlyDigits(): void {
    const c = this.form.controls.code;
    const digits = c.value.replace(/\D/g, '').slice(0, 6);
    if (digits !== c.value) {
      c.setValue(digits);
    }
  }

  protected submit(): void {
    this.submitted.set(true);
    if (!this.titles().length && this.form.controls.number.value.trim()) {
      this.addTitle();
      return;
    }
    if (this.form.invalid || !this.titles().length || !this.evidence() || this.mixed() || this.aboveBalance()) {
      this.form.markAllAsTouched();
      return;
    }
    if (this.busy()) {
      return;
    }
    const v = this.form.getRawValue();
    this.busy.set(true);
    this.error.set(null);
    this.api
      .report({ titleNumbers: this.titles().map((t) => t.number), amount: Number(v.amount), paidOn: v.paidOn!,
        method: v.method, reference: v.reference.trim() || null, note: v.note.trim() || null, code: v.code },
      this.evidence()!, this.idempotencyKey)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (saved) => this.ref.close(saved),
        error: (err: unknown) => {
          this.error.set(reportMessage(toApiError(err)));
          this.form.controls.code.reset('');
        },
      });
  }
}

/**
 * Pagamentos informados no portal do credor: o que o time do credor informou que recebeu diretamente, com a situação
 * da análise da assessoria e, na recusa, o motivo.
 */
@Component({
  selector: 'app-creditor-payments',
  imports: [Amount, AppDatePipe, CellDef, DataTable, Icon, StatusChip],
  template: `
    <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
      <h1 class="h4 mb-0 flex-grow-1">Pagamentos informados</h1>
      @if (canReport()) {
        <button class="btn btn-primary" type="button" (click)="report()" data-tour="creditor-payment-report">
          <app-icon name="plus-lg" /> Informar pagamento
        </button>
      }
    </div>
    <p class="app-muted">
      Pagamentos que o devedor fez diretamente a você. A assessoria confere cada um antes de dar baixa nos títulos.
    </p>
    <div aria-live="polite">
      @if (notice(); as n) {
        <div class="alert alert-success d-flex align-items-start gap-2"><app-icon name="check-circle" /><span>{{ n }}</span></div>
      }
    </div>

    <section class="app-card" aria-labelledby="cp-lista">
      <h2 class="app-card__title" id="cp-lista">Informes do seu time</h2>
      <app-data-table
        caption="Pagamentos informados pelo seu time"
        [rows]="rows()"
        [columns]="columns"
        [loading]="list.isLoading()"
        [failed]="!!list.error()"
        (retry)="list.reload()"
        emptyIcon="cash-coin"
        emptyMessage="Nenhum pagamento informado."
        [emptyHint]="canReport() ? 'Recebeu um pagamento diretamente do devedor? Use Informar pagamento.' : 'Quem informa pagamentos pelo credor são os perfis Gestão de recuperação e Financeiro.'"
      >
        <ng-template appCell="paidOn" [appCellOf]="rows()" let-p>
          {{ p.paidOn | appDate: 'date' }}
          <div class="app-muted cp-small">{{ method(p.method) }}</div>
        </ng-template>
        <ng-template appCell="titles" [appCellOf]="rows()" let-p>
          {{ p.titleNumbers.join(', ') }}
          @if (p.reference) {
            <div class="app-muted cp-small">Ref. {{ p.reference }}</div>
          }
        </ng-template>
        <ng-template appCell="amount" [appCellOf]="rows()" let-p><app-amount [value]="p.amount" /></ng-template>
        <ng-template appCell="status" [appCellOf]="rows()" let-p>
          <app-status-chip domain="creditorPayment" [status]="p.status" />
          @if (p.status === 'REJECTED' && p.rejectionReason) {
            <div class="cp-small mt-1">{{ reason(p.rejectionReason) }}</div>
          }
          @if (p.status === 'CONFIRMED') {
            <div class="cp-small mt-1">Baixado nos títulos{{ p.decidedAt ? ' em ' + (p.decidedAt | appDate: 'date') : '' }}.</div>
          }
          @if (p.status === 'REVERSED') {
            <div class="cp-small mt-1">A assessoria desfez a baixa; os títulos voltaram a ter saldo. Fale com a assessoria para os detalhes.</div>
          }
        </ng-template>
        <ng-template appCell="informedAt" [appCellOf]="rows()" let-p>{{ p.informedAt | appDate }}</ng-template>
      </app-data-table>
    </section>
  `,
  styles: `
    .cp-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class CreditorPayments {
  private readonly api = inject(CreditorPaymentsApi);
  private readonly dialog = inject(DialogService);
  private readonly session = inject(CreditorSession);
  protected readonly list = rxResource({ stream: () => this.api.list() });
  protected readonly rows = computed(() => this.list.value() ?? []);
  protected readonly canReport = computed(() => this.session.me()?.permissions.includes(CreditorPermission.PAYMENT_REPORT) ?? false);
  protected readonly notice = signal<string | null>(null);
  protected readonly method = (m: CreditorPaymentMethod) => CREDITOR_PAYMENT_METHOD_LABELS[m];
  protected readonly reason = (r: CreditorReportedPayment['rejectionReason']) => (r ? CREDITOR_REJECTION_REASON_LABELS[r] : '');

  protected readonly columns: TableColumn[] = [
    { key: 'paidOn', header: 'Pago em', mobile: 'title' },
    { key: 'status', header: 'Situação', mobile: 'badge' },
    { key: 'titles', header: 'Títulos' },
    { key: 'amount', header: 'Valor', align: 'end' },
    { key: 'informedAt', header: 'Informado em' },
  ];

  protected report(): void {
    this.dialog.open<CreditorPaymentReportDialog, void, CreditorReportedPayment>(CreditorPaymentReportDialog, {})
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notice.set('Pagamento enviado para análise da assessoria. Acompanhe a situação nesta lista.');
          this.list.reload();
        }
      });
  }
}
