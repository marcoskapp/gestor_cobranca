import { Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
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
import { FORM_FIELD } from '../../../../shared/forms/field';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';
import { Icon } from '../../../../shared/components/icon/icon';

@Component({
  selector: 'app-team-form',
  imports: [Icon, ...FORM_FIELD,
    ReactiveFormsModule,
    ...DIALOG_DIRECTIVES,
    EntityPicker,
  ],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ team ? 'Editar equipe' : 'Nova equipe' }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @for (e of generalErrors(); track e) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ e }}</span></div>
        }
        <div class="vstack gap-3">
          <app-field label="Nome" [error]="nameError()">
            <input appInput formControlName="name" autocomplete="off" [maxlength]="limits.teamName" ngbAutofocus />
          </app-field>
          <app-field label="Unidade">
            <select appInput formControlName="businessUnitId">
              <option value="">Nenhuma</option>
              @for (u of units(); track u.id) {
                <option [ngValue]="u.id" [disabled]="!u.active">{{ u.code }} — {{ u.name }}</option>
              }
            </select>
          </app-field>
          <app-entity-picker [control]="form.controls.supervisor" [source]="users" label="Supervisor" [single]="true" />
          <app-entity-picker [control]="form.controls.memberIds" [source]="users" label="Membros" />
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">{{ team ? 'Salvar' : 'Criar equipe' }}</button>
      </div>
    </form>
  `,
})
export class TeamForm {
  protected readonly team = inject<TeamView | null>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<TeamView>>(DialogRef);
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
