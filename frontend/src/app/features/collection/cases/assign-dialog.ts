import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { finalize } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Limits } from '../../../shared/validation/validators';
import { UserLookup } from '../../admin/data/user.lookup';
import { CaseWorkApi } from '../data/collection.api';
import { TeamLookup } from '../data/collection.lookups';
import { CaseView } from '../data/collection.models';

export interface AssignDialogData {
  caseView: CaseView;
  debtorName: string;
}

/** Atribuição manual do caso a um operador (com acesso ao credor) ou a uma equipe. */
@Component({
  selector: 'app-assign-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatRadioModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>Atribuir caso</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        <p class="app-dialog-message">Devedor: <strong>{{ data.debtorName }}</strong></p>
        <mat-radio-group formControlName="target" aria-label="Destino" class="ad-target">
          <mat-radio-button value="user">Operador</mat-radio-button>
          <mat-radio-button value="team">Equipe (sem operador)</mat-radio-button>
        </mat-radio-group>
        @if (form.controls.target.value === 'user') {
          <app-entity-picker [control]="form.controls.user" [source]="users" label="Operador" [single]="true" hint="Precisa ter acesso ao credor e permissão de trabalhar casos." />
        } @else {
          <app-entity-picker [control]="form.controls.team" [source]="teams" label="Equipe" [single]="true" />
        }
        @if (missing()) {
          <p class="app-field-error" role="alert">Escolha o destino.</p>
        }
        <mat-form-field class="app-full-width">
          <mat-label>Motivo (opcional)</mat-label>
          <textarea matInput formControlName="reason" rows="2" [maxlength]="maxReason"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Atribuir</button>
      </mat-dialog-actions>
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
  protected readonly data = inject<AssignDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<AssignDialog, CaseView>>(MatDialogRef);
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
