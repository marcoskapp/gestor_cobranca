import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { finalize } from 'rxjs';
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
  imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule],
  template: `
    <h2 mat-dialog-title>Registrar aceite do devedor</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        <p class="app-dialog-message">Registre somente depois de o devedor confirmar as condições abaixo. O termo do acordo é gerado com estes dados e fica como evidência.</p>
        <dl class="app-definition-list ad-details">
          @for (d of data.details; track d.label) {
            <dt>{{ d.label }}</dt>
            <dd>{{ d.value }}</dd>
          }
        </dl>
        <mat-form-field class="app-full-width">
          <mat-label>Canal do aceite</mat-label>
          <mat-select formControlName="channel" required>
            @for (c of channels; track c.value) {
              <mat-option [value]="c.value">{{ c.label }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field class="app-full-width">
          <mat-label>Referência da evidência (opcional)</mat-label>
          <input matInput formControlName="reference" maxlength="120" autocomplete="off" />
          <mat-hint>{{ hint() }}</mat-hint>
        </mat-form-field>
        <p class="app-alert app-alert--neutral" role="note">
          <mat-icon aria-hidden="true">shield</mat-icon>
          <span>Data, hora, quem registrou, endereço IP e navegador são gravados automaticamente.</span>
        </p>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Registrar aceite</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .ad-details {
      margin: 0 0 16px;
      padding: 12px;
      border-radius: var(--app-radius-md);
      background: var(--app-surface-raised);
    }
  `,
})
export class AcceptDialog {
  protected readonly data = inject<AcceptDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<AcceptDialog, ProposalView>>(MatDialogRef);
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
