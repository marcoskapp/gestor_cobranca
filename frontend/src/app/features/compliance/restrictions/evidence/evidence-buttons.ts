import { Component, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { finalize } from 'rxjs';
import { NotificationService } from '../../../../core/notification/notification.service';
import { RestrictionsApi } from '../../data/compliance.api';
import { EVIDENCE_ACCEPT, evidenceProblem } from '../../data/compliance.labels';
import { RestrictionView } from '../../data/compliance.models';

/**
 * Comprovante da restrição (opt-out por escrito, ofício, gravação): anexar uma única vez, em
 * restrição ativa, e baixar (o download é registrado no log de acesso como `FILE_DOWNLOADED`).
 */
@Component({
  selector: 'app-evidence-buttons',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule, MatProgressSpinnerModule],
  template: `
    @let r = restriction();
    @if (r.hasEvidence) {
      <button
        mat-icon-button
        type="button"
        (click)="download(r)"
        [attr.aria-label]="'Baixar evidência ' + (r.evidenceFileName ?? '')"
        [matTooltip]="'Baixar ' + (r.evidenceFileName ?? 'evidência') + ' (acesso registrado)'"
      >
        <mat-icon aria-hidden="true">attach_file</mat-icon>
      </button>
    } @else if (canManage() && r.status === 'ACTIVE') {
      <input #file type="file" class="cdk-visually-hidden" [accept]="accept" (change)="upload(r, file)" tabindex="-1" aria-hidden="true" />
      <button
        mat-icon-button
        type="button"
        [disabled]="busy()"
        (click)="file.click()"
        aria-label="Anexar evidência"
        matTooltip="Anexar evidência (PDF, imagem, áudio ou texto até 20 MB)"
      >
        @if (busy()) {
          <mat-spinner diameter="18" />
        } @else {
          <mat-icon aria-hidden="true">upload_file</mat-icon>
        }
      </button>
    }
  `,
})
export class EvidenceButtons {
  readonly restriction = input.required<RestrictionView>();
  readonly canManage = input(false);
  readonly updated = output<RestrictionView>();

  private readonly api = inject(RestrictionsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly accept = EVIDENCE_ACCEPT;
  protected readonly busy = signal(false);

  protected upload(r: RestrictionView, input: HTMLInputElement): void {
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    const problem = evidenceProblem(file);
    if (problem) {
      this.notifications.error(problem);
      return;
    }
    this.busy.set(true);
    this.api
      .attachEvidence(r.id, file)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe((saved) => {
        this.notifications.success('Evidência anexada.');
        this.updated.emit(saved);
      });
  }

  protected download(r: RestrictionView): void {
    this.api.downloadEvidence(r.id).subscribe();
  }
}
