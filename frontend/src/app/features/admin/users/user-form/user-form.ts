import { Component, computed, inject, signal } from '@angular/core';
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
import { CreditorLookup } from '../../../organization/data/organization.lookups';
import { UserView } from '../../data/admin.models';
import { RolesApi } from '../../data/roles.api';
import { UsersApi } from '../../data/users.api';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { Icon } from '../../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';

export interface UserFormData {
  /** null = criação */
  user: UserView | null;
}

/**
 * Criação (nome, e-mail, unidade, perfis, escopo) ou edição de dados cadastrais (nome, unidade).
 * Perfis e escopo de usuários existentes são alterados por diálogos próprios (auditoria distinta).
 */
@Component({
  selector: 'app-user-form',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, EntityPicker, Icon],
  templateUrl: './user-form.html',
})
export class UserForm {
  private readonly api = inject(UsersApi);
  private readonly ref = inject<DialogRef<UserView>>(DialogRef);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly data = inject<UserFormData>(DIALOG_DATA);
  private readonly unitsApi = inject(BusinessUnitsApi);
  private readonly rolesApi = inject(RolesApi);

  protected readonly limits = Limits;
  protected readonly editing = this.data.user !== null;
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);

  protected readonly units = valueOr(rxResource({ stream: () => this.unitsApi.list() }), []);
  private readonly allRoles = valueOr(rxResource({ stream: () => this.rolesApi.list() }), []);
  protected readonly roles = computed(() => this.allRoles().filter((r) => r.active));

  protected readonly form = inject(FormBuilder).nonNullable.group({
    name: [this.data.user?.name ?? '', [AppValidators.notBlank, Validators.maxLength(Limits.personName)]],
    email: [
      { value: this.data.user?.email ?? '', disabled: this.editing },
      [AppValidators.notBlank, AppValidators.email, Validators.maxLength(Limits.email)],
    ],
    businessUnitId: [this.data.user?.businessUnitId ?? ''],
    roleIds: [[] as string[], this.editing ? [] : [AppValidators.notBlank]],
    allCreditors: [false],
    creditorIds: [[] as string[]],
  });

  /** Erro da lista de perfis (só na criação), exibido depois de tocar ou submeter. */
  protected rolesInvalid(): boolean {
    const roles = this.form.controls.roleIds;
    return roles.invalid && roles.touched;
  }

  protected hasRole(id: string): boolean {
    return this.form.controls.roleIds.value.includes(id);
  }

  protected toggleRole(id: string, checked: boolean): void {
    const roles = this.form.controls.roleIds;
    const others = roles.value.filter((value) => value !== id);
    roles.setValue(checked ? [...others, id] : others);
    roles.markAsTouched();
  }

  protected message(control: 'name' | 'email' | 'roleIds'): string {
    return errorMessage(this.form.controls[control].errors);
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const businessUnitId = v.businessUnitId || null;
    const request$ = this.data.user
      ? this.api.update(this.data.user.id, { name: v.name.trim(), businessUnitId })
      : this.api.create({
          name: v.name.trim(),
          email: v.email.trim(),
          businessUnitId,
          roleIds: v.roleIds,
          allCreditors: v.allCreditors,
          creditorIds: v.allCreditors ? [] : v.creditorIds,
        });
    this.saving.set(true);
    this.generalErrors.set([]);
    request$.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (user) => {
        this.notifications.success(
          this.editing ? 'Usuário atualizado.' : 'Usuário criado. O link de ativação foi enviado por e-mail.',
        );
        this.ref.close(user);
      },
      error: (err: unknown) => this.generalErrors.set(applyServerErrors(this.form, toApiError(err))),
    });
  }
}
