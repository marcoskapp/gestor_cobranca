import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { MatSelectModule } from '@angular/material/select';
import { finalize } from 'rxjs';
import { toApiError } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
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
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatInputModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>{{ data ? 'Editar perfil de alçada' : 'Novo perfil de alçada' }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        @for (e of errors(); track e) {
          <p class="app-alert app-alert--error" role="alert">{{ e }}</p>
        }
        <div class="app-form-grid">
          <mat-form-field>
            <mat-label>Código</mat-label>
            <input matInput formControlName="code" maxlength="40" [readonly]="!!data" required />
            <mat-hint>{{ data ? 'Imutável' : 'Maiúsculas, dígitos e _' }}</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Nome</mat-label>
            <input matInput formControlName="name" maxlength="120" required />
          </mat-form-field>
          <mat-form-field class="app-span-all">
            <mat-label>Descrição</mat-label>
            <input matInput formControlName="description" maxlength="500" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Nível máximo da escada</mat-label>
            <input matInput type="number" min="0" max="99" formControlName="maxConcessionLevel" required />
            <mat-hint>99 = topo da escada da política</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Fatia do desconto máximo (%)</mat-label>
            <input matInput type="number" min="0" max="100" step="0.01" formControlName="maxDiscountShare" required />
            <mat-hint>100 = todo o envelope da política</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Pagamentos máximos</mat-label>
            <input matInput type="number" min="1" max="360" formControlName="maxInstallments" required />
            <mat-hint>Nunca acima da política</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Valor máximo do acordo (R$)</mat-label>
            <input matInput type="number" min="0.01" step="0.01" formControlName="maxAgreementAmount" />
            <mat-hint>Vazio = sem limite próprio</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Entrada mínima (%)</mat-label>
            <input matInput type="number" min="0" max="99.99" step="0.01" formControlName="minDownPaymentShare" required />
            <mat-hint>Vale a maior entre política e perfil</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Renegociações de acordo quebrado</mat-label>
            <input matInput type="number" min="0" max="10" formControlName="maxRenegotiations" required />
          </mat-form-field>
          <mat-checkbox formControlName="canFormalize">Formaliza sem aprovação</mat-checkbox>
          <mat-checkbox formControlName="canApprove">Aprova propostas de outros até o próprio limite</mat-checkbox>
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.creditors" [source]="creditorSource" label="Restrito aos credores" hint="Vazio = vale para todos (exige acesso a todos os credores)." />
          </div>
        </div>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Salvar perfil</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class ProfileForm {
  protected readonly data = inject<ProfileView | null>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ProfileForm, ProfileView>>(MatDialogRef);
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
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatRadioModule, MatSelectModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>Atribuir alçada</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        <mat-radio-group formControlName="subjectType" aria-label="Quem recebe a alçada" class="aa-type">
          <mat-radio-button value="USER">Usuário</mat-radio-button>
          <mat-radio-button value="ASSISTANT_POLICY">Política do assistente de IA</mat-radio-button>
        </mat-radio-group>
        @if (form.controls.subjectType.value === 'USER') {
          <app-entity-picker [control]="form.controls.user" [source]="users" label="Usuário" [single]="true" hint="Ninguém atribui alçada a si mesmo." />
        } @else {
          <mat-form-field class="app-full-width">
            <mat-label>Id da política do assistente</mat-label>
            <input matInput formControlName="assistantPolicyId" autocomplete="off" />
            <mat-hint>A política do assistente vincula o perfil ao ser publicada; use aqui só para ajuste manual.</mat-hint>
          </mat-form-field>
        }
        <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor" [single]="true" hint="Vazio = todos os credores (exige acesso a todos)." />
        <mat-form-field class="app-full-width">
          <mat-label>Perfil</mat-label>
          <mat-select formControlName="profileId" required>
            @for (p of data.profiles; track p.id) {
              <mat-option [value]="p.id">{{ p.name }} ({{ p.code }})</mat-option>
            }
          </mat-select>
        </mat-form-field>
        @if (problem()) {
          <p class="app-field-error" role="alert">{{ problem() }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Atribuir</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .aa-type {
      display: flex;
      flex-wrap: wrap;
      gap: 16px;
      margin-bottom: 8px;
    }
  `,
})
export class AssignAuthorityDialog {
  protected readonly data = inject<AssignAuthorityData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<AssignAuthorityDialog, AuthorityAssignmentView>>(MatDialogRef);
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
