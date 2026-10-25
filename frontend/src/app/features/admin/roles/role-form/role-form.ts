import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, startWith } from 'rxjs';
import { Permission, permissionGroupOf, PERMISSION_GROUPS } from '../../../../core/auth/permission';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { valueOr } from '../../../../shared/data/resource-value';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators, Limits, Patterns } from '../../../../shared/validation/validators';
import { PermissionView, RoleView } from '../../data/admin.models';
import { RolesApi } from '../../data/roles.api';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';
import { Icon } from '../../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../../shared/forms/field';

interface PermissionGroup {
  label: string;
  permissions: PermissionView[];
}

@Component({
  selector: 'app-role-form',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  templateUrl: './role-form.html',
  styleUrl: './role-form.scss',
})
export class RoleForm {
  protected readonly role = inject<RoleView | null>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<RoleView>>(DialogRef);
  private readonly api = inject(RolesApi);
  private readonly notifications = inject(NotificationService);

  protected readonly limits = Limits;
  protected readonly editing = this.role !== null;
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);

  private readonly catalogResource = rxResource({ stream: () => this.api.permissions() });
  protected readonly catalogLoading = this.catalogResource.isLoading;
  private readonly catalog = valueOr(this.catalogResource, []);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    code: [
      { value: this.role?.code ?? '', disabled: this.editing },
      [AppValidators.notBlank, Validators.pattern(Patterns.roleCode)],
    ],
    name: [this.role?.name ?? '', [AppValidators.notBlank, Validators.maxLength(Limits.roleName)]],
    description: [this.role?.description ?? '', [Validators.maxLength(Limits.roleDescription)]],
    permissions: [[...(this.role?.permissions ?? [])], [AppValidators.notBlank]],
  });

  private readonly selected = toSignal(
    this.form.controls.permissions.valueChanges.pipe(startWith(this.form.controls.permissions.value)),
    { requireSync: true },
  );
  protected readonly selectedSet = computed(() => new Set(this.selected()));

  /** Catálogo agrupado; TENANT_MANAGE só aparece no perfil de plataforma (regra do domínio). */
  protected readonly groups = computed<PermissionGroup[]>(() => {
    const allowPlatform = this.role?.code === 'PLATFORM_ADMIN';
    const byGroup = new Map<string, PermissionView[]>();
    for (const permission of this.catalog()) {
      if (permission.code === Permission.TENANT_MANAGE && !allowPlatform) {
        continue;
      }
      const group = permissionGroupOf(permission.code);
      byGroup.set(group, [...(byGroup.get(group) ?? []), permission]);
    }
    const order = [...PERMISSION_GROUPS.map((g) => g.label), 'Outras'];
    return order.filter((label) => byGroup.has(label)).map((label) => ({ label, permissions: byGroup.get(label) ?? [] }));
  });

  /** Permissões do catálogo que o usuário atual não pode conceder (fora do próprio perfil). */
  protected readonly notGrantable = computed(() =>
    this.groups().flatMap((g) => g.permissions).filter((p) => !p.grantable),
  );

  /** Já marcadas no perfil, mas fora do alcance de quem edita: ficam como estão. */
  protected readonly lockedSelected = computed(() =>
    this.notGrantable().filter((p) => this.selectedSet().has(p.code)).length,
  );

  protected message(control: 'code' | 'name' | 'description'): string {
    const errors = this.form.controls[control].errors;
    if (control === 'code' && errors?.['pattern']) {
      return 'Use letras maiúsculas, números e "_", começando por letra (2 a 40 caracteres).';
    }
    return errorMessage(errors);
  }

  protected toggle(code: string, checked: boolean): void {
    const current = this.form.controls.permissions.value.filter((value) => value !== code);
    this.form.controls.permissions.setValue(checked ? [...current, code] : current);
    this.form.controls.permissions.markAsTouched();
  }

  protected groupState(group: PermissionGroup): 'all' | 'some' | 'none' {
    const grantable = group.permissions.filter((p) => p.grantable);
    const count = grantable.filter((p) => this.selectedSet().has(p.code)).length;
    return count === 0 ? 'none' : count === grantable.length ? 'all' : 'some';
  }

  protected groupGrantable(group: PermissionGroup): boolean {
    return group.permissions.some((p) => p.grantable);
  }

  /** Marca todas as permissões que o usuário atual pode conceder. */
  protected selectAllGrantable(): void {
    const grantable = this.groups().flatMap((g) => g.permissions).filter((p) => p.grantable).map((p) => p.code);
    this.form.controls.permissions.setValue([...new Set([...this.form.controls.permissions.value, ...grantable])]);
    this.form.controls.permissions.markAsTouched();
  }

  protected clearGrantable(): void {
    const grantable = new Set(this.groups().flatMap((g) => g.permissions).filter((p) => p.grantable).map((p) => p.code));
    this.form.controls.permissions.setValue(this.form.controls.permissions.value.filter((c) => !grantable.has(c)));
    this.form.controls.permissions.markAsTouched();
  }

  protected toggleGroup(group: PermissionGroup, checked: boolean): void {
    // só as permissões que o usuário pode conceder; as demais ficam como estão
    const codes = new Set(group.permissions.filter((p) => p.grantable).map((p) => p.code));
    const others = this.form.controls.permissions.value.filter((value) => !codes.has(value));
    this.form.controls.permissions.setValue(checked ? [...others, ...codes] : others);
    this.form.controls.permissions.markAsTouched();
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const request = {
      code: this.editing ? null : v.code.trim(),
      name: v.name.trim(),
      description: v.description.trim() || null,
      permissions: v.permissions,
    };
    this.saving.set(true);
    this.generalErrors.set([]);
    (this.role ? this.api.update(this.role.id, request) : this.api.create(request))
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.notifications.success(this.editing ? 'Perfil atualizado.' : 'Perfil criado.');
          this.ref.close(saved);
        },
        error: (err: unknown) => this.generalErrors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}
