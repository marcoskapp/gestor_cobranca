import { Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { finalize } from 'rxjs';
import { NotificationService } from '../../../../core/notification/notification.service';
import { valueOr } from '../../../../shared/data/resource-value';
import { AppValidators } from '../../../../shared/validation/validators';
import { UserView } from '../../data/admin.models';
import { RolesApi } from '../../data/roles.api';
import { UsersApi } from '../../data/users.api';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';

@Component({
  selector: 'app-user-roles-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Perfis de {{ user.name }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <div class="modal-body">
      <fieldset>
        <legend class="visually-hidden">Perfis</legend>
        @for (role of roles(); track role.id) {
          <div class="form-check mb-2">
            <input
              class="form-check-input"
              type="checkbox"
              [id]="'user-role-' + role.id"
              [checked]="isSelected(role.id)"
              #cb
              (change)="toggle(role.id, cb.checked)"
              [disabled]="!role.active && !isSelected(role.id)"
            />
            <label class="form-check-label" [for]="'user-role-' + role.id">
              <strong class="fw-medium">{{ role.name }}</strong>
              @if (!role.active) {
                <span class="app-muted"> (inativo)</span>
              }
              @if (role.description) {
                <span class="app-muted"> — {{ role.description }}</span>
              }
            </label>
          </div>
        }
      </fieldset>
      @if (selection.invalid && selection.touched) {
        <div class="invalid-feedback d-block" role="alert">Selecione ao menos um perfil.</div>
      }
    </div>
    <div class="modal-footer">
      <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
      <button class="btn btn-primary" type="button" (click)="save()" [disabled]="saving()">Salvar perfis</button>
    </div>
  `,
})
export class UserRolesDialog {
  protected readonly user = inject<UserView>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<UserView>>(DialogRef);
  private readonly api = inject(UsersApi);
  private readonly notifications = inject(NotificationService);
  private readonly rolesApi = inject(RolesApi);

  protected readonly roles = valueOr(rxResource({ stream: () => this.rolesApi.list() }), []);
  protected readonly saving = signal(false);
  protected readonly selection = new FormControl<string[]>([...this.user.roleIds], {
    nonNullable: true,
    validators: [AppValidators.notBlank],
  });

  protected isSelected(id: string): boolean {
    return this.selection.value.includes(id);
  }

  protected toggle(id: string, checked: boolean): void {
    const current = this.selection.value.filter((value) => value !== id);
    this.selection.setValue(checked ? [...current, id] : current);
    this.selection.markAsTouched();
  }

  protected save(): void {
    this.selection.markAsTouched();
    if (this.selection.invalid || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.api
      .assignRoles(this.user.id, this.selection.value)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe((updated) => {
        this.notifications.success('Perfis atualizados.');
        this.ref.close(updated);
      });
  }
}
