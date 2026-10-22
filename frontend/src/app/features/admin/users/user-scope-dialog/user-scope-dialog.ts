import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { finalize } from 'rxjs';
import { NotificationService } from '../../../../core/notification/notification.service';
import { EntityPicker } from '../../../../shared/components/entity-picker/entity-picker';
import { CreditorLookup } from '../../../organization/data/organization.lookups';
import { UserView } from '../../data/admin.models';
import { UsersApi } from '../../data/users.api';

@Component({
  selector: 'app-user-scope-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatCheckboxModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>Escopo de credores de {{ user.name }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        <p class="app-muted">O usuário só acessa carteiras, devedores e operações dos credores do seu escopo.</p>
        <mat-checkbox formControlName="allCreditors">Acesso a todos os credores</mat-checkbox>
        @if (!form.controls.allCreditors.value) {
          <app-entity-picker [control]="form.controls.creditorIds" [source]="creditors" label="Credores permitidos" />
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Salvar escopo</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class UserScopeDialog {
  protected readonly user = inject<UserView>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<UserScopeDialog, UserView>>(MatDialogRef);
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
