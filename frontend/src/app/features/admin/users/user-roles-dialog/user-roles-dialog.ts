import { Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { finalize } from 'rxjs';
import { NotificationService } from '../../../../core/notification/notification.service';
import { valueOr } from '../../../../shared/data/resource-value';
import { AppValidators } from '../../../../shared/validation/validators';
import { UserView } from '../../data/admin.models';
import { RolesApi } from '../../data/roles.api';
import { UsersApi } from '../../data/users.api';

@Component({
  selector: 'app-user-roles-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatCheckboxModule],
  template: `
    <h2 mat-dialog-title>Perfis de {{ user.name }}</h2>
    <mat-dialog-content>
      <fieldset class="app-checkbox-list">
        <legend class="cdk-visually-hidden">Perfis</legend>
        @for (role of roles(); track role.id) {
          <mat-checkbox [checked]="isSelected(role.id)" (change)="toggle(role.id, $event.checked)" [disabled]="!role.active && !isSelected(role.id)">
            <strong>{{ role.name }}</strong>
            @if (!role.active) {
              <span class="app-muted"> (inativo)</span>
            }
            @if (role.description) {
              <span class="app-muted"> — {{ role.description }}</span>
            }
          </mat-checkbox>
        }
      </fieldset>
      @if (selection.invalid && selection.touched) {
        <p class="app-field-error" role="alert">Selecione ao menos um perfil.</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancelar</button>
      <button mat-flat-button type="button" (click)="save()" [disabled]="saving()">Salvar perfis</button>
    </mat-dialog-actions>
  `,
})
export class UserRolesDialog {
  protected readonly user = inject<UserView>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<UserRolesDialog, UserView>>(MatDialogRef);
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
