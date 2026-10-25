import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Limits } from '../../../shared/validation/validators';
import { UserLookup } from '../../admin/data/user.lookup';
import { CaseWorkApi } from '../data/collection.api';
import { TeamLookup } from '../data/collection.lookups';
import { CaseView } from '../data/collection.models';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';

export interface AssignDialogData {
  caseView: CaseView;
  debtorName: string;
}

/** Atribuição manual do caso a um operador (com acesso ao credor) ou a uma equipe. */
@Component({
  selector: 'app-assign-dialog',
  imports: [ReactiveFormsModule, EntityPicker, ...FORM_FIELD, ...DIALOG_DIRECTIVES],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Atribuir caso</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">Devedor: <strong>{{ data.debtorName }}</strong></p>
        <div role="radiogroup" aria-label="Destino" class="ad-target">
          <div class="form-check form-check-inline">
            <input class="form-check-input" type="radio" id="assign-dialog-target-user" value="user" formControlName="target" />
            <label class="form-check-label" for="assign-dialog-target-user">Operador</label>
          </div>
          <div class="form-check form-check-inline">
            <input class="form-check-input" type="radio" id="assign-dialog-target-team" value="team" formControlName="target" />
            <label class="form-check-label" for="assign-dialog-target-team">Equipe (sem operador)</label>
          </div>
        </div>
        @if (form.controls.target.value === 'user') {
          <app-entity-picker [control]="form.controls.user" [source]="users" label="Operador" [single]="true" hint="Precisa ter acesso ao credor e permissão de trabalhar casos." />
        } @else {
          <app-entity-picker [control]="form.controls.team" [source]="teams" label="Equipe" [single]="true" />
        }
        @if (missing()) {
          <p class="app-field-error" role="alert">Escolha o destino.</p>
        }
        <app-field class="app-full-width mb-3" label="Motivo (opcional)">
          <textarea appInput formControlName="reason" rows="2" [maxlength]="maxReason"></textarea>
        </app-field>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Atribuir</button>
      </div>
    </form>
  `,
  styles: `
    .ad-target {
      display: flex;
      gap: 16px;
      margin-bottom: 8px;
    }
  `,
})
export class AssignDialog {
  protected readonly data = inject<AssignDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<CaseView>>(DialogRef);
  private readonly api = inject(CaseWorkApi);
  private readonly notifications = inject(NotificationService);
  protected readonly users = inject(UserLookup);
  protected readonly teams = inject(TeamLookup);
  protected readonly maxReason = Limits.reason;
  protected readonly saving = signal(false);
  protected readonly missing = signal(false);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    target: this.fb.control<'user' | 'team'>('user'),
    user: this.fb.control<string[]>(this.data.caseView.assigneeUserId ? [this.data.caseView.assigneeUserId] : []),
    team: this.fb.control<string[]>(this.data.caseView.assigneeTeamId ? [this.data.caseView.assigneeTeamId] : []),
    reason: ['', [Validators.maxLength(Limits.reason)]],
  });

  protected save(): void {
    const v = this.form.getRawValue();
    const userId = v.target === 'user' ? (v.user[0] ?? null) : null;
    const teamId = v.target === 'team' ? (v.team[0] ?? null) : null;
    this.missing.set(!userId && !teamId);
    if (this.missing() || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.api
      .assign(this.data.caseView.id, { userId, teamId, reason: v.reason.trim() || null })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe((saved) => {
        this.notifications.success('Caso atribuído.');
        this.ref.close(saved);
      });
  }
}
