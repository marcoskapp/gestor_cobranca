import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { ERASURE_NOTICE_CHANNEL_LABELS } from '../data/compliance.labels';
import { ErasureNoticeChannel, RegisterNoticeSent } from '../data/compliance.models';
import { SentAtTexts, localNow, sentAtInstant, sentAtProblem } from './erasure-notice.rules';

export interface RegisterSentDialogData {
  creditorName: string;
  /** Quantos avisos o registro alcança (1 no detalhe; a remessa inteira na lista). */
  count: number;
  /** Eliminação mais recente entre os avisos (ou o bloqueio do lote): o envio não pode ser anterior. */
  latestErasedAt: string | null;
  /** Título do diálogo (padrão: "Registrar aviso a …"). */
  title?: string;
  /** Aviso no topo (padrão: o do aviso ao credor da eliminação). */
  note?: string;
  /** Rótulo do botão (padrão: "Registrar aviso"). */
  confirmLabel?: string;
  /** Rótulo do campo de data (padrão: "Avisado em"). */
  sentLabel?: string;
  /** Textos das mensagens de validação (padrão: os do aviso da eliminação). */
  texts?: SentAtTexts;
}

/**
 * Registro de que o credor foi avisado: quando (data e hora, já preenchidas com agora) e por qual canal. Sem texto
 * livre; o registro é feito uma vez só e não se corrige (é a evidência do aviso).
 */
@Component({
  selector: 'app-register-sent-dialog',
  imports: [ReactiveFormsModule, Icon, ...DIALOG_DIRECTIVES, ...FORM_FIELD],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0" tabindex="-1" ngbAutofocus>{{ data.title ?? 'Registrar aviso a ' + data.creditorName }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <div class="alert alert-warning d-flex gap-2 align-items-start" role="note">
          <app-icon name="exclamation-triangle" />
          <span>
            @if (data.note) {
              {{ data.note }}
            } @else if (data.count === 1) {
              O registro é a evidência de que o credor foi avisado. É feito uma vez só e não pode ser corrigido.
            } @else {
              O registro vale para os {{ data.count }} titulares da remessa. É feito uma vez só e não pode ser corrigido.
            }
          </span>
        </div>
        <div class="row g-3">
          <div class="col-sm-6">
            <app-field [label]="data.sentLabel ?? 'Avisado em'" hint="Data e hora do envio." [error]="sentError() ?? undefined">
              <input appInput type="datetime-local" formControlName="sentAt" required />
            </app-field>
          </div>
          <div class="col-sm-6">
            <app-field label="Canal">
              <select appInput formControlName="channel" required>
                @for (c of channels; track c.value) {
                  <option [ngValue]="c.value">{{ c.label }}</option>
                }
              </select>
            </app-field>
          </div>
        </div>
        <div class="form-check mt-3">
          <input class="form-check-input" type="checkbox" id="notice-sent-confirm" formControlName="confirmed" />
          <label class="form-check-label" for="notice-sent-confirm">Já enviei o texto para {{ data.creditorName }} por este canal.</label>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="!form.controls.confirmed.value">{{ data.confirmLabel ?? 'Registrar aviso' }}</button>
      </div>
    </form>
  `,
})
export class RegisterSentDialog {
  protected readonly data = inject<RegisterSentDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<RegisterNoticeSent>>(DialogRef);
  protected readonly channels = Object.entries(ERASURE_NOTICE_CHANNEL_LABELS).map(([value, label]) => ({
    value: value as ErasureNoticeChannel,
    label,
  }));
  protected readonly sentError = signal<string | null>(null);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    sentAt: [localNow()],
    channel: ['EMAIL' as ErasureNoticeChannel],
    confirmed: [false],
  });

  constructor() {
    // o erro da data some assim que a pessoa corrige o campo
    this.form.controls.sentAt.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.sentError.set(null));
  }

  protected save(): void {
    const v = this.form.getRawValue();
    if (!v.confirmed) {
      return;
    }
    this.sentError.set(sentAtProblem(v.sentAt, this.data.latestErasedAt, new Date(), this.data.texts));
    if (this.sentError()) {
      return;
    }
    this.ref.close({ notifiedAt: sentAtInstant(v.sentAt, this.data.latestErasedAt), channel: v.channel });
  }
}
