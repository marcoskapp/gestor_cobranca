import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { ProposalsApi } from '../data/agreement.api';
import { ACCEPTANCE_CHANNEL_LABELS, ACCEPTANCE_REFERENCE_HINTS } from '../data/agreement.labels';
import { AcceptanceChannel, ProposalView } from '../data/agreement.models';

export interface AcceptDialogData {
  proposal: ProposalView;
  /** Resumo do impacto (devedor mascarado, valor, parcelas, vencimentos). */
  details: readonly { label: string; value: string }[];
}

/**
 * Registro do aceite do devedor: canal e referência externa (gravação, mensagem, sessão). O
 * servidor gera o termo em PDF, guarda o SHA-256 e registra IP e agente da própria requisição.
 */
@Component({
  selector: 'app-accept-dialog',
  imports: [Icon, ...FORM_FIELD, ...DIALOG_DIRECTIVES, ReactiveFormsModule],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Registrar aceite do devedor</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">Registre somente depois de o devedor confirmar as condições abaixo. O termo do acordo é gerado com estes dados e fica como evidência.</p>
        <dl class="app-definition-list bg-body-tertiary rounded p-3 mb-3">
          @for (d of data.details; track d.label) {
            <dt>{{ d.label }}</dt>
            <dd>{{ d.value }}</dd>
          }
        </dl>
        <app-field class="mb-3" label="Canal do aceite">
          <select appInput formControlName="channel" required ngbAutofocus>
            @for (c of channels; track c.value) {
              <option [ngValue]="c.value">{{ c.label }}</option>
            }
          </select>
        </app-field>
        <app-field class="mb-3" label="Referência da evidência (opcional)" [hint]="hint()">
          <input appInput formControlName="reference" maxlength="120" autocomplete="off" />
        </app-field>
        <div class="alert alert-secondary d-flex gap-2 align-items-start" role="note">
          <app-icon name="shield" />
          <span>Data, hora, quem registrou, endereço IP e navegador são gravados automaticamente.</span>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Registrar aceite</button>
      </div>
    </form>
  `,
})
export class AcceptDialog {
  protected readonly data = inject<AcceptDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<ProposalView>>(DialogRef);
  private readonly api = inject(ProposalsApi);

  protected readonly channels = Object.entries(ACCEPTANCE_CHANNEL_LABELS).map(([value, label]) => ({ value: value as AcceptanceChannel, label }));
  protected readonly saving = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    channel: this.fb.control<AcceptanceChannel | ''>(this.initialChannel(), Validators.required),
    reference: ['', [Validators.maxLength(120)]],
  });
  private readonly channel = toSignal(this.form.controls.channel.valueChanges, { initialValue: this.form.controls.channel.value });
  protected readonly hint = computed(() => {
    const c = this.channel();
    return c ? ACCEPTANCE_REFERENCE_HINTS[c] : 'Escolha o canal em que o devedor aceitou.';
  });

  private initialChannel(): AcceptanceChannel | '' {
    const c = this.data.proposal.channel;
    return c && c in ACCEPTANCE_CHANNEL_LABELS ? (c as AcceptanceChannel) : '';
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.api
      .accept(this.data.proposal.id, { channel: v.channel as AcceptanceChannel, reference: v.reference.trim() || null })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe((p) => this.ref.close(p));
  }
}
