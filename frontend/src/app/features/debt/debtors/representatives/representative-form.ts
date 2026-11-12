import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { finalize } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { Icon } from '../../../../shared/components/icon/icon';
import { DIALOG_DIRECTIVES } from '../../../../shared/dialog/dialog.directives';
import { DIALOG_DATA, DialogRef } from '../../../../shared/dialog/dialog-ref';
import { DocumentMask } from '../../../../shared/directives/document-mask';
import { FORM_FIELD } from '../../../../shared/forms/field';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators } from '../../../../shared/validation/validators';
import { CompanyRepresentativesApi } from '../../data/debt.api';
import { REPRESENTATIVE_ROLE_LABELS, options } from '../../data/debt.labels';
import { RepresentativeRole, RepresentativeView } from '../../data/debt.models';

export interface RepresentativeFormData {
  companyId: string;
  /** Nulo = novo representante. O CPF não muda (outro CPF é outro representante). */
  representative: RepresentativeView | null;
}

type Field = 'document' | 'name' | 'role' | 'validUntil' | 'evidenceNote';

/** Inclusão e edição de representante da empresa devedora (B3). */
@Component({
  selector: 'app-representative-form',
  imports: [ReactiveFormsModule, DocumentMask, Icon, NgbInputDatepicker, ...FORM_FIELD, ...DIALOG_DIRECTIVES],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.representative ? 'Editar representante' : 'Novo representante' }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @for (e of generalErrors(); track e) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert">{{ e }}</div>
        }
        @if (data.representative; as r) {
          <p class="app-dialog-message">
            CPF <span class="app-numeric">{{ r.maskedDocument }}</span>. Para outra pessoa, cadastre um novo representante e
            encerre este.
          </p>
        }
        <div class="app-form-grid gap-3">
          @if (!data.representative) {
            <app-field label="CPF" [error]="form.controls.document.invalid ? message('document') : undefined">
              <input appInput formControlName="document" appDocumentMask maxlength="14" inputmode="numeric" autocomplete="off" />
            </app-field>
          }
          <app-field [class.app-span-all]="!!data.representative" label="Papel" [error]="form.controls.role.invalid ? message('role') : undefined">
            <select appInput formControlName="role">
              @for (r of roles; track r.value) {
                <option [ngValue]="r.value">{{ r.label }}</option>
              }
            </select>
          </app-field>
          <app-field class="app-span-all" label="Nome" [error]="form.controls.name.invalid ? message('name') : undefined">
            <input appInput formControlName="name" maxlength="200" autocomplete="off" />
          </app-field>
          <app-field label="Válido até" hint="Opcional: fim da procuração ou do mandato." [error]="form.controls.validUntil.invalid ? message('validUntil') : undefined">
            <div class="input-group">
              <input appInput ngbDatepicker #until="ngbDatepicker" formControlName="validUntil" placeholder="dd/mm/aaaa" />
              <button type="button" class="btn btn-outline-secondary" (click)="until.toggle()" aria-label="Abrir calendário">
                <app-icon name="calendar-event" />
              </button>
            </div>
          </app-field>
          <app-field label="Evidência" hint="Ex.: contrato social de 2024. Não anexe documentos aqui." [error]="form.controls.evidenceNote.invalid ? message('evidenceNote') : undefined">
            <input appInput formControlName="evidenceNote" maxlength="200" autocomplete="off" />
          </app-field>
          <div class="form-check form-switch app-span-all">
            <input class="form-check-input" type="checkbox" role="switch" id="representative-negotiates" formControlName="canNegotiate" />
            <label class="form-check-label" for="representative-negotiates">Pode negociar pela empresa (inclusive no portal)</label>
          </div>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">{{ data.representative ? 'Salvar' : 'Cadastrar representante' }}</button>
      </div>
    </form>
  `,
})
export class RepresentativeForm {
  protected readonly data = inject<RepresentativeFormData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<RepresentativeView>>(DialogRef);
  private readonly api = inject(CompanyRepresentativesApi);
  private readonly notifications = inject(NotificationService);
  protected readonly roles = options(REPRESENTATIVE_ROLE_LABELS);
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    document: ['', this.data.representative ? [] : [AppValidators.notBlank, AppValidators.document()]],
    name: [this.data.representative?.name ?? '', [AppValidators.notBlank, Validators.maxLength(200)]],
    role: this.fb.control<RepresentativeRole>(this.data.representative?.role ?? 'MANAGING_PARTNER'),
    canNegotiate: [this.data.representative?.canNegotiate ?? true],
    /** `LocalDate` ISO (yyyy-MM-dd) do `ngbDatepicker`. */
    validUntil: this.fb.control<string | null>(this.data.representative?.validUntil ?? null),
    evidenceNote: [this.data.representative?.evidenceNote ?? '', [Validators.maxLength(200)]],
  });

  protected message(field: Field): string {
    return errorMessage(this.form.controls[field].errors);
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const body = {
      name: v.name.trim(),
      role: v.role,
      canNegotiate: v.canNegotiate,
      validUntil: v.validUntil || null,
      evidenceNote: v.evidenceNote.trim() || null,
    };
    const r = this.data.representative;
    const request = r
      ? this.api.update(this.data.companyId, r.id, body)
      : this.api.add(this.data.companyId, { ...body, document: v.document });
    this.saving.set(true);
    this.generalErrors.set([]);
    request.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (saved) => {
        this.notifications.success(r ? 'Representante atualizado.' : 'Representante cadastrado.');
        this.ref.close(saved);
      },
      error: (err: unknown) => this.generalErrors.set(applyServerErrors(this.form, toApiError(err))),
    });
  }
}
