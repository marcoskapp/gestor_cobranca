import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { EMPTY, finalize, switchMap, tap } from 'rxjs';
import { Amount } from '../../../shared/components/amount/amount';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { ProposalsApi } from '../data/agreement.api';
import { ACCEPTANCE_CHANNEL_LABELS, ACCEPTANCE_REFERENCE_HINTS } from '../data/agreement.labels';
import { AcceptanceChannel, AgreementView, ProposalView } from '../data/agreement.models';
import { scheduleSummary } from '../data/agreement.rules';
import { planText } from '../data/negotiation.labels';

/** Texto a digitar para formalizar (passo irreversível). */
export const FORMALIZE_TEXT = 'FORMALIZAR';

export interface CloseAgreementData {
  proposal: ProposalView;
  /** Resumo do impacto (devedor mascarado, credor, total, forma de pagamento, vencimentos). */
  details: readonly { label: string; value: string }[];
  /** Canal do atendimento em curso (pré-preenche o canal do aceite; senão, o da proposta). */
  channel: string | null;
  /** `AGREEMENT_FORMALIZE` e perfil de alçada que formaliza: aceite e formalização num passo só. */
  canFormalize: boolean;
  /** Pode gerar Pix da parcela (`PAYMENT_INSTRUCTION_CREATE`). */
  canPix: boolean;
}

export interface CloseAgreementResult {
  proposal: ProposalView;
  agreement: AgreementView | null;
  /** O operador pediu o Pix da próxima parcela ao concluir. */
  pix: boolean;
}

type Step = 'accept' | 'formalize' | 'done';

/** Canal da interação → canal do aceite (o chat do portal não vem da estação). */
export function acceptanceChannel(channel: string | null | undefined): AcceptanceChannel | '' {
  return channel && channel in ACCEPTANCE_CHANNEL_LABELS ? (channel as AcceptanceChannel) : '';
}

/**
 * Fechamento do acordo sem sair da estação (decisão D7): registra o aceite do devedor (canal
 * pré-preenchido com o do atendimento) e, quando a alçada permite formalizar, formaliza no mesmo
 * passo — com a confirmação digitada, porque a formalização é irreversível. Sem alçada para
 * formalizar, só o aceite é registrado e a formalização fica com quem pode. Os dois comandos são os
 * mesmos da página da proposta e são revalidados no servidor (guard, alçada, escopo e situação); se
 * a formalização falhar depois do aceite, o diálogo continua no passo de formalizar.
 */
@Component({
  selector: 'app-close-agreement-dialog',
  imports: [ReactiveFormsModule, Icon, Amount, AppDatePipe, ...FORM_FIELD, ...DIALOG_DIRECTIVES],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ step() === 'done' ? 'Acordo formalizado' : 'Fechar o acordo' }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" [appDialogClose]="result()"></button>
    </div>
    @if (step() === 'done' && agreement(); as a) {
      <div class="modal-body">
        <div class="alert alert-success d-flex gap-2 align-items-start" role="status">
          <app-icon name="check-circle" />
          <span>
            Acordo de <app-amount [value]="a.totalAmount" /> em {{ plan() }} formalizado. O termo ficou registrado como evidência.
          </span>
        </div>
        @if (next(); as n) {
          <p class="mb-0">
            Próximo passo: enviar ao devedor o Pix da {{ n.number }}ª parcela (<app-amount [value]="n.amount" />, vence em
            {{ n.dueDate | appDate: 'date' }}).
          </p>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" [appDialogClose]="result()">Voltar ao caso</button>
        @if (data.canPix && next()) {
          <button class="btn btn-primary d-inline-flex align-items-center gap-2" type="button" ngbAutofocus (click)="pix()">
            <app-icon name="qr-code" />
            Gerar Pix da {{ next()!.number }}ª parcela
          </button>
        }
      </div>
    } @else {
      <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="submit()" novalidate>
        <div class="modal-body">
          <p class="app-dialog-message">
            @if (step() === 'accept') {
              Registre somente depois de o devedor confirmar as condições abaixo. O termo do acordo é gerado com estes dados e fica como evidência.
            } @else {
              O aceite do devedor já está registrado. Formalize para o acordo passar a valer.
            }
          </p>
          <dl class="app-definition-list bg-body-tertiary rounded p-3 mb-3">
            @for (d of data.details; track d.label) {
              <dt>{{ d.label }}</dt>
              <dd>{{ d.value }}</dd>
            }
          </dl>
          @if (step() === 'accept') {
            <app-field class="mb-3" label="Canal do aceite" [hint]="channelHint()">
              <select appInput formControlName="channel" required>
                <option value="" disabled>Selecione o canal</option>
                @for (c of channels; track c.value) {
                  <option [ngValue]="c.value">{{ c.label }}</option>
                }
              </select>
            </app-field>
            <app-field class="mb-3" label="Referência da evidência (opcional)" [hint]="referenceHint()">
              <input appInput formControlName="reference" maxlength="120" autocomplete="off" />
            </app-field>
          }
          @if (data.canFormalize) {
            <div class="alert alert-warning d-flex gap-2 align-items-start" role="note">
              <app-icon name="exclamation-triangle" />
              <span>{{ warning() }}</span>
            </div>
            <app-field [label]="'Digite ' + formalizeText + ' para confirmar'">
              <input appInput formControlName="typed" autocomplete="off" spellcheck="false" />
            </app-field>
          } @else {
            <div class="alert alert-secondary d-flex gap-2 align-items-start mb-0" role="note">
              <app-icon name="info-circle" />
              <span>Sua alçada não formaliza acordos: com o aceite registrado, a formalização fica com quem tem alçada para isso.</span>
            </div>
          }
        </div>
        <div class="modal-footer">
          <button class="btn btn-link" type="button" [appDialogClose]="result()">{{ canSubmit() ? 'Cancelar' : 'Voltar ao caso' }}</button>
          @if (step() === 'accept' && data.canFormalize) {
            <button class="btn btn-outline-primary" type="button" (click)="acceptOnly()" [disabled]="busy() || form.controls.channel.invalid">Só registrar o aceite</button>
          }
          <!-- aceite já registrado e sem alçada para formalizar: não há o que enviar daqui -->
          @if (canSubmit()) {
            <button class="btn btn-primary" type="submit" [disabled]="busy() || !ready()">
              @if (busy()) {
                <span class="spinner-border spinner-border-sm" aria-hidden="true"></span>
              }
              {{ primaryLabel() }}
            </button>
          }
        </div>
      </form>
    }
  `,
})
export class CloseAgreementDialog {
  protected readonly data = inject<CloseAgreementData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<CloseAgreementResult>>(DialogRef);
  private readonly api = inject(ProposalsApi);

  protected readonly formalizeText = FORMALIZE_TEXT;
  protected readonly channels = Object.entries(ACCEPTANCE_CHANNEL_LABELS).map(([value, label]) => ({ value: value as AcceptanceChannel, label }));
  protected readonly proposal = signal<ProposalView>(this.data.proposal);
  protected readonly agreement = signal<AgreementView | null>(null);
  protected readonly busy = signal(false);

  protected readonly step = computed<Step>(() => (this.agreement() ? 'done' : this.proposal().status === 'ACCEPTED' ? 'formalize' : 'accept'));
  protected readonly result = computed<CloseAgreementResult>(() => ({ proposal: this.proposal(), agreement: this.agreement(), pix: false }));

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    channel: this.fb.control<AcceptanceChannel | ''>(acceptanceChannel(this.data.channel) || acceptanceChannel(this.data.proposal.channel), Validators.required),
    reference: ['', [Validators.maxLength(120)]],
    typed: [''],
  });
  private readonly value = toSignal(this.form.valueChanges, { initialValue: this.form.getRawValue() });

  /** Há comando a enviar: o aceite, ou a formalização quando a alçada permite (sem ela, o passo de formalizar só informa). */
  protected readonly canSubmit = computed(() => this.step() === 'accept' || this.data.canFormalize);
  protected readonly typedMatches = computed(() => (this.value().typed ?? '').trim().toUpperCase() === FORMALIZE_TEXT);
  protected readonly ready = computed(() => {
    const channelOk = this.step() !== 'accept' || !!this.value().channel;
    return channelOk && (!this.data.canFormalize || this.typedMatches());
  });
  protected readonly primaryLabel = computed(() => {
    if (this.step() === 'formalize') {
      return 'Formalizar';
    }
    return this.data.canFormalize ? 'Registrar aceite e formalizar' : 'Registrar aceite';
  });
  protected readonly channelHint = computed(() =>
    this.data.channel && acceptanceChannel(this.data.channel) ? 'Preenchido com o canal do atendimento.' : 'Escolha o canal em que o devedor aceitou.',
  );
  protected readonly referenceHint = computed(() => {
    const c = this.value().channel;
    return c ? ACCEPTANCE_REFERENCE_HINTS[c as AcceptanceChannel] : undefined;
  });
  protected readonly warning = computed(() =>
    this.proposal().replacedAgreementId
      ? 'O acordo anterior será marcado como renegociado. A formalização não pode ser desfeita: só o cancelamento com motivo.'
      : 'A formalização não pode ser desfeita: só o cancelamento do acordo, com motivo.',
  );
  protected readonly plan = computed(() => {
    const a = this.agreement();
    return a ? planText({ installments: a.installments.length, downPayment: a.downPayment }).toLowerCase() : '';
  });
  protected readonly next = computed(() => {
    const a = this.agreement();
    return a ? scheduleSummary(a).next : null;
  });

  protected submit(): void {
    if (this.busy() || !this.ready() || !this.canSubmit()) {
      this.form.markAllAsTouched();
      return;
    }
    if (this.step() === 'formalize') {
      this.formalize();
    } else {
      this.accept(this.data.canFormalize);
    }
  }

  protected acceptOnly(): void {
    if (!this.busy() && this.form.controls.channel.valid) {
      this.accept(false);
    }
  }

  protected pix(): void {
    this.ref.close({ ...this.result(), pix: true });
  }

  private accept(thenFormalize: boolean): void {
    const v = this.form.getRawValue();
    this.busy.set(true);
    this.api
      .accept(this.proposal().id, { channel: v.channel as AcceptanceChannel, reference: v.reference.trim() || null })
      .pipe(
        // aceite registrado: se a formalização falhar, o diálogo continua no passo de formalizar
        tap((accepted) => this.proposal.set(accepted)),
        switchMap((accepted) => {
          if (!thenFormalize) {
            this.ref.close(this.result());
            return EMPTY;
          }
          return this.api.formalize(accepted.id);
        }),
        finalize(() => this.busy.set(false)),
      )
      // a recusa do servidor já vem notificada (ProposalsApi): o diálogo fica no passo em que parou
      .subscribe({ next: (agreement) => this.agreement.set(agreement), error: () => undefined });
  }

  private formalize(): void {
    if (!this.data.canFormalize || !this.typedMatches()) {
      return;
    }
    this.busy.set(true);
    this.api
      .formalize(this.proposal().id)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({ next: (agreement) => this.agreement.set(agreement), error: () => undefined });
  }
}
