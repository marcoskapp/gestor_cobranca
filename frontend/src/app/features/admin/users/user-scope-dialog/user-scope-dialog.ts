import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import { NotificationService } from '../../../../core/notification/notification.service';
import { EntityPicker } from '../../../../shared/components/entity-picker/entity-picker';
import { CreditorLookup } from '../../../organization/data/organization.lookups';
import { UserView } from '../../data/admin.models';
import { UsersApi } from '../../data/users.api';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';

@Component({
  selector: 'app-user-scope-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, EntityPicker],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Escopo de credores de {{ user.name }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <p class="app-muted">O usuário só acessa carteiras, devedores e operações dos credores do seu escopo.</p>
        <div class="form-check"><input class="form-check-input" type="checkbox" id="usersc-allCreditors" formControlName="allCreditors"  /><label class="form-check-label" for="usersc-allCreditors">Acesso a todos os credores</label></div>
        @if (!form.controls.allCreditors.value) {
          <app-entity-picker [control]="form.controls.creditorIds" [source]="creditors" label="Credores permitidos" />
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Salvar escopo</button>
      </div>
    </form>
  `,
})
export class UserScopeDialog {
  protected readonly user = inject<UserView>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<UserView>>(DialogRef);
  private readonly api = inject(UsersApi);
  private readonly notifications = inject(NotificationService);
  protected readonly creditors = inject(CreditorLookup);
  protected readonly saving = signal(false);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    allCreditors: [this.user.allCreditors],
    creditorIds: [[...this.user.creditorIds]],
  });

  protected save(): void {
    if (this.saving()) {
      return;
    }
    const { allCreditors, creditorIds } = this.form.getRawValue();
    this.saving.set(true);
    this.api
      .defineCreditorScope(this.user.id, allCreditors, allCreditors ? [] : creditorIds)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe((updated) => {
        this.notifications.success('Escopo de credores atualizado.');
        this.ref.close(updated);
      });
  }
}
