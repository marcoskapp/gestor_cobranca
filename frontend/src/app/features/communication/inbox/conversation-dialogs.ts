import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { finalize } from 'rxjs';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { UserLookup } from '../../admin/data/user.lookup';
import { DebtorLookup } from '../../debt/data/debt.lookups';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { ConversationsApi } from '../data/communication.api';
import { ConversationView } from '../data/communication.models';

export interface ConversationDialogData {
  conversation: ConversationView;
}

/**
 * Identificação do devedor de um contato desconhecido. O contato precisa constar do cadastro do
 * devedor; sem identificação, nada pode ser enviado (evita falar com terceiro).
 */
@Component({
  selector: 'app-identify-dialog',
  imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>Identificar devedor</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        <p class="app-dialog-message">
          Contato {{ data.conversation.contactMasked }}. Escolha o devedor que tem este contato no cadastro; a conversa passa a fazer parte do caso dele.
        </p>
        <app-entity-picker [control]="form.controls.debtor" [source]="debtors" label="Devedor" [single]="true" hint="Nome ou CPF/CNPJ completo." />
        @if (!data.conversation.creditorId) {
          <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor" [single]="true" hint="Credor da dívida tratada nesta conversa." />
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving() || !form.controls.debtor.value.length">Identificar</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class IdentifyDialog {
  protected readonly data = inject<ConversationDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<IdentifyDialog, ConversationView>>(MatDialogRef);
  private readonly api = inject(ConversationsApi);
  protected readonly debtors = inject(DebtorLookup);
  protected readonly creditors = inject(CreditorLookup);
  protected readonly saving = signal(false);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({ debtor: this.fb.control<string[]>([]), creditor: this.fb.control<string[]>([]) });

  protected save(): void {
    const debtorId = this.form.controls.debtor.value[0];
    if (!debtorId || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.api
      .identify(this.data.conversation.id, debtorId, this.form.controls.creditor.value[0] ?? null)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe((c) => this.ref.close(c));
  }
}

/** Atribuição da conversa (e do caso) a um operador — supervisão (`INBOX_MANAGE`). */
@Component({
  selector: 'app-assign-conversation-dialog',
  imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>Atribuir conversa</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        <p class="app-dialog-message">O operador passa a ser o dono desta conversa e do caso; as demais conversas abertas do caso acompanham.</p>
        <app-entity-picker [control]="form.controls.user" [source]="users" label="Operador" [single]="true" />
        <mat-form-field class="app-full-width">
          <mat-label>Motivo (opcional)</mat-label>
          <input matInput formControlName="reason" maxlength="300" />
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving() || !form.controls.user.value.length">Atribuir</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class AssignConversationDialog {
  protected readonly data = inject<ConversationDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<AssignConversationDialog, ConversationView>>(MatDialogRef);
  private readonly api = inject(ConversationsApi);
  protected readonly users = inject(UserLookup);
  protected readonly saving = signal(false);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({ user: this.fb.control<string[]>([]), reason: ['', Validators.maxLength(300)] });

  protected save(): void {
    const userId = this.form.controls.user.value[0];
    if (!userId || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.api
      .assign(this.data.conversation.id, userId, this.form.controls.reason.value.trim() || null)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe((c) => this.ref.close(c));
  }
}
