import { Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { finalize } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { EntityPicker } from '../../../../shared/components/entity-picker/entity-picker';
import { valueOr } from '../../../../shared/data/resource-value';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators, Limits } from '../../../../shared/validation/validators';
import { BusinessUnitsApi } from '../../../organization/data/organization.api';
import { TeamView } from '../../data/admin.models';
import { TeamsApi } from '../../data/teams.api';
import { UserLookup } from '../../data/user.lookup';

@Component({
  selector: 'app-team-form',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    EntityPicker,
  ],
  template: `
    <h2 mat-dialog-title>{{ team ? 'Editar equipe' : 'Nova equipe' }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        @for (e of generalErrors(); track e) {
          <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
        }
        <div class="app-form-stack">
          <mat-form-field>
            <mat-label>Nome</mat-label>
            <input matInput formControlName="name" autocomplete="off" [maxlength]="limits.teamName" />
            @if (form.controls.name.invalid) {
              <mat-error>{{ nameError() }}</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Unidade</mat-label>
            <mat-select formControlName="businessUnitId">
              <mat-option value="">Nenhuma</mat-option>
              @for (u of units(); track u.id) {
                <mat-option [value]="u.id" [disabled]="!u.active">{{ u.code }} — {{ u.name }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <app-entity-picker [control]="form.controls.supervisor" [source]="users" label="Supervisor" [single]="true" />
          <app-entity-picker [control]="form.controls.memberIds" [source]="users" label="Membros" />
        </div>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">{{ team ? 'Salvar' : 'Criar equipe' }}</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class TeamForm {
  protected readonly team = inject<TeamView | null>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<TeamForm, TeamView>>(MatDialogRef);
  private readonly api = inject(TeamsApi);
  private readonly unitsApi = inject(BusinessUnitsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly users = inject(UserLookup);
  protected readonly limits = Limits;
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);
  protected readonly units = valueOr(rxResource({ stream: () => this.unitsApi.list() }), []);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    name: [this.team?.name ?? '', [AppValidators.notBlank, Validators.maxLength(Limits.teamName)]],
    businessUnitId: [this.team?.businessUnitId ?? ''],
    supervisor: [this.team?.supervisorId ? [this.team.supervisorId] : ([] as string[])],
    memberIds: [[...(this.team?.memberIds ?? [])]],
  });

  protected nameError(): string {
    return errorMessage(this.form.controls.name.errors);
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const request = {
      name: v.name.trim(),
      businessUnitId: v.businessUnitId || null,
      supervisorId: v.supervisor[0] ?? null,
      memberIds: v.memberIds,
    };
    this.saving.set(true);
    this.generalErrors.set([]);
    (this.team ? this.api.update(this.team.id, request) : this.api.create(request))
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.notifications.success(this.team ? 'Equipe atualizada.' : 'Equipe criada.');
          this.ref.close(saved);
        },
        error: (err: unknown) =>
          this.generalErrors.set(applyServerErrors(this.form, toApiError(err), { supervisorId: 'supervisor' })),
      });
  }
}
