import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
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
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, EntityPicker],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Identificar devedor</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body d-grid gap-3">
        <p class="app-dialog-message mb-0">
          Contato {{ data.conversation.contactMasked }}. Escolha o devedor que tem este contato no cadastro; a conversa passa a fazer parte do caso dele.
        </p>
        <app-entity-picker [control]="form.controls.debtor" [source]="debtors" label="Devedor" [single]="true" hint="Nome ou CPF/CNPJ completo." />
        @if (!data.conversation.creditorId) {
          <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor" [single]="true" hint="Credor da dívida tratada nesta conversa." />
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving() || !form.controls.debtor.value.length">Identificar</button>
      </div>
    </form>
  `,
})
export class IdentifyDialog {
  protected readonly data = inject<ConversationDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<ConversationView>>(DialogRef);
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
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, EntityPicker],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Atribuir conversa</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body d-grid gap-3">
        <p class="app-dialog-message mb-0">O operador passa a ser o dono desta conversa e do caso; as demais conversas abertas do caso acompanham.</p>
        <app-entity-picker [control]="form.controls.user" [source]="users" label="Operador" [single]="true" />
        <app-field label="Motivo (opcional)">
          <input appInput formControlName="reason" maxlength="300" />
        </app-field>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving() || !form.controls.user.value.length">Atribuir</button>
      </div>
    </form>
  `,
})
export class AssignConversationDialog {
  protected readonly data = inject<ConversationDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<ConversationView>>(DialogRef);
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
