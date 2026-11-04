import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { PlatformBillingApi } from '../data/platform-billing.api';
import { PolicyView } from '../data/platform-billing.models';
import { PLATFORM_BILLING_ERRORS } from './platform-billing.labels';
import { PlatformPolicyDialogData } from './platform-policy-dialog';

/** Vigência da política da plataforma: o início só muda antes do primeiro extrato; o fim nunca no passado. */
@Component({
  selector: 'app-platform-term-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0" tabindex="-1" ngbAutofocus>Vigência da política — {{ data.tenantName }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        <app-field label="Início" [hint]="data.policy.hasStatements ? 'Fixo: a assessoria já tem extrato da plataforma.' : 'Os ciclos começam aqui.'">
          <input appInput type="date" formControlName="startDate" required />
        </app-field>
        <app-field label="Fim" hint="Opcional. O último ciclo termina no fim da vigência, com mensalidade e franquias proporcionais.">
          <input appInput type="date" formControlName="endDate" />
        </app-field>
        <app-field label="Motivo" hint="Obrigatório; fica na trilha de auditoria da plataforma e da assessoria.">
          <input appInput formControlName="reason" maxlength="500" required />
        </app-field>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Salvar vigência</button>
      </div>
    </form>
  `,
})
export class PlatformTermDialog {
  protected readonly data = inject<PlatformPolicyDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<PolicyView>>(DialogRef);
  private readonly api = inject(PlatformBillingApi);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = this.fb.group({
    startDate: [{ value: this.data.policy.startDate ?? '', disabled: this.data.policy.hasStatements }, Validators.required],
    endDate: [this.data.policy.endDate ?? ''],
    reason: ['', [Validators.required, Validators.maxLength(500)]],
  });

  protected save(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.saving()) {
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.error.set(null);
    this.api
      .changeTerm(this.data.tenantId, v.startDate, v.endDate || null, v.reason.trim())
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (saved) => {
          this.notifications.success('Vigência atualizada.');
          this.ref.close(saved);
        },
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && PLATFORM_BILLING_ERRORS[e.code]) || userMessage(e));
        },
      });
  }
}
