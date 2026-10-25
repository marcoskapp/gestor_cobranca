import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { applyServerErrors } from '../../../shared/validation/server-errors';
import { AppValidators } from '../../../shared/validation/validators';
import { UserLookup } from '../../admin/data/user.lookup';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { AuthorityApi } from '../data/negotiation.api';
import { AuthorityAssignmentView, ProfileRequest, ProfileView, SubjectType } from '../data/negotiation.models';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Perfil de alçada: quanto do envelope da política o ator usa sem aprovação. */
@Component({
  selector: 'app-profile-form',
  imports: [...FORM_FIELD, ...DIALOG_DIRECTIVES, ReactiveFormsModule, EntityPicker],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data ? 'Editar perfil de alçada' : 'Novo perfil de alçada' }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @for (e of errors(); track e) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert">{{ e }}</div>
        }
        <div class="app-form-grid">
          <app-field label="Código" hint="{{ data ? 'Imutável' : 'Maiúsculas, dígitos e _' }}">
            <input appInput formControlName="code" maxlength="40" [readonly]="!!data" required ngbAutofocus />
          </app-field>
          <app-field label="Nome">
            <input appInput formControlName="name" maxlength="120" required />
          </app-field>
          <app-field class="app-span-all" label="Descrição">
            <input appInput formControlName="description" maxlength="500" />
          </app-field>
          <app-field label="Nível máximo da escada" hint="99 = topo da escada da política">
            <input appInput type="number" min="0" max="99" formControlName="maxConcessionLevel" required />
          </app-field>
          <app-field label="Fatia do desconto máximo (%)" hint="100 = todo o envelope da política">
            <input appInput type="number" min="0" max="100" step="0.01" formControlName="maxDiscountShare" required />
          </app-field>
          <app-field label="Pagamentos máximos" hint="Nunca acima da política">
            <input appInput type="number" min="1" max="360" formControlName="maxInstallments" required />
          </app-field>
          <app-field label="Valor máximo do acordo (R$)" hint="Vazio = sem limite próprio">
            <input appInput type="number" min="0.01" step="0.01" formControlName="maxAgreementAmount" />
          </app-field>
          <app-field label="Entrada mínima (%)" hint="Vale a maior entre política e perfil">
            <input appInput type="number" min="0" max="99.99" step="0.01" formControlName="minDownPaymentShare" required />
          </app-field>
          <app-field label="Renegociações de acordo quebrado">
            <input appInput type="number" min="0" max="10" formControlName="maxRenegotiations" required />
          </app-field>
          <div class="form-check">
            <input class="form-check-input" type="checkbox" id="pf-canFormalize" formControlName="canFormalize" />
            <label class="form-check-label" for="pf-canFormalize">Formaliza sem aprovação</label>
          </div>
          <div class="form-check">
            <input class="form-check-input" type="checkbox" id="pf-canApprove" formControlName="canApprove" />
            <label class="form-check-label" for="pf-canApprove">Aprova propostas de outros até o próprio limite</label>
          </div>
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.creditors" [source]="creditorSource" label="Restrito aos credores" hint="Vazio = vale para todos (exige acesso a todos os credores)." />
          </div>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Salvar perfil</button>
      </div>
    </form>
  `,
})
export class ProfileForm {
  protected readonly data = inject<ProfileView | null>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<ProfileView>>(DialogRef);
  private readonly api = inject(AuthorityApi);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly saving = signal(false);
  protected readonly errors = signal<string[]>([]);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    code: [this.data?.code ?? '', [AppValidators.notBlank, Validators.pattern(/^[A-Z0-9_]{2,40}$/)]],
    name: [this.data?.name ?? '', [AppValidators.notBlank, Validators.maxLength(120)]],
    description: [this.data?.description ?? '', [Validators.maxLength(500)]],
    maxConcessionLevel: [this.data?.maxConcessionLevel ?? 0, [Validators.required, Validators.min(0), Validators.max(99)]],
    maxDiscountShare: [this.data?.maxDiscountShare ?? 50, [Validators.required, Validators.min(0), Validators.max(100)]],
    maxInstallments: [this.data?.maxInstallments ?? 6, [Validators.required, Validators.min(1), Validators.max(360)]],
    maxAgreementAmount: this.fb.control<number | null>(this.data?.maxAgreementAmount ?? null, [Validators.min(0.01)]),
    minDownPaymentShare: [this.data?.minDownPaymentShare ?? 10, [Validators.required, Validators.min(0), Validators.max(99.99)]],
    canFormalize: [this.data?.canFormalize ?? false],
    canApprove: [this.data?.canApprove ?? false],
    maxRenegotiations: [this.data?.maxRenegotiations ?? 0, [Validators.required, Validators.min(0), Validators.max(10)]],
    creditors: this.fb.control<string[]>(this.data?.creditorIds ?? []),
  });

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const request: ProfileRequest = {
      code: this.data ? null : v.code.trim().toUpperCase(),
      name: v.name.trim(),
      description: v.description.trim() || null,
      maxConcessionLevel: Number(v.maxConcessionLevel),
      maxDiscountShare: Number(v.maxDiscountShare),
      maxInstallments: Number(v.maxInstallments),
      maxAgreementAmount: v.maxAgreementAmount ? Number(v.maxAgreementAmount) : null,
      minDownPaymentShare: Number(v.minDownPaymentShare),
      canFormalize: v.canFormalize,
      canApprove: v.canApprove,
      maxRenegotiations: Number(v.maxRenegotiations),
      creditorIds: v.creditors,
    };
    this.saving.set(true);
    (this.data ? this.api.updateProfile(this.data.id, request) : this.api.createProfile(request))
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => this.ref.close(saved),
        error: (err: unknown) => this.errors.set(applyServerErrors(this.form, toApiError(err))),
      });
  }
}

export interface AssignAuthorityData {
  profiles: readonly ProfileView[];
}

/** Atribui (ou troca) o perfil de um usuário ou de uma política do assistente, geral ou por credor. */
@Component({
  selector: 'app-assign-authority',
  imports: [...FORM_FIELD, ...DIALOG_DIRECTIVES, ReactiveFormsModule, EntityPicker],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Atribuir alçada</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <fieldset class="d-flex flex-wrap column-gap-3 mb-2">
          <legend class="visually-hidden">Quem recebe a alçada</legend>
          <div class="form-check">
            <input class="form-check-input" type="radio" formControlName="subjectType" value="USER" id="aa-subjectType-user" ngbAutofocus />
            <label class="form-check-label" for="aa-subjectType-user">Usuário</label>
          </div>
          <div class="form-check">
            <input class="form-check-input" type="radio" formControlName="subjectType" value="ASSISTANT_POLICY" id="aa-subjectType-assistant-policy" />
            <label class="form-check-label" for="aa-subjectType-assistant-policy">Política do assistente de IA</label>
          </div>
        </fieldset>
        @if (form.controls.subjectType.value === 'USER') {
          <app-entity-picker [control]="form.controls.user" [source]="users" label="Usuário" [single]="true" hint="Ninguém atribui alçada a si mesmo." />
        } @else {
          <app-field class="mb-3" label="Id da política do assistente" hint="A política do assistente vincula o perfil ao ser publicada; use aqui só para ajuste manual.">
            <input appInput formControlName="assistantPolicyId" autocomplete="off" />
          </app-field>
        }
        <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor" [single]="true" hint="Vazio = todos os credores (exige acesso a todos)." />
        <app-field class="mb-3" label="Perfil">
          <select appInput formControlName="profileId" required>
            @for (p of data.profiles; track p.id) {
              <option [ngValue]="p.id">{{ p.name }} ({{ p.code }})</option>
            }
          </select>
        </app-field>
        @if (problem()) {
          <div class="invalid-feedback d-block" role="alert">{{ problem() }}</div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Atribuir</button>
      </div>
    </form>
  `,
})
export class AssignAuthorityDialog {
  protected readonly data = inject<AssignAuthorityData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<AuthorityAssignmentView>>(DialogRef);
  private readonly api = inject(AuthorityApi);
  private readonly notifications = inject(NotificationService);
  protected readonly users = inject(UserLookup);
  protected readonly creditors = inject(CreditorLookup);
  protected readonly saving = signal(false);
  protected readonly problem = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    subjectType: this.fb.control<SubjectType>('USER'),
    user: this.fb.control<string[]>([]),
    assistantPolicyId: [''],
    creditor: this.fb.control<string[]>([]),
    profileId: ['', [AppValidators.notBlank]],
  });

  protected save(): void {
    const v = this.form.getRawValue();
    const subjectId = v.subjectType === 'USER' ? v.user[0] : v.assistantPolicyId.trim();
    this.problem.set(!subjectId ? 'Escolha quem recebe a alçada.' : !UUID.test(subjectId) ? 'Id da política inválido.' : !v.profileId ? 'Escolha o perfil.' : null);
    if (this.problem() || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.api
      .assign({ subjectType: v.subjectType, subjectId: subjectId!, creditorId: v.creditor[0] ?? null, profileId: v.profileId })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe((saved) => {
        this.notifications.success('Alçada atribuída. A mudança fica registrada no log de acesso.');
        this.ref.close(saved);
      });
  }
}
