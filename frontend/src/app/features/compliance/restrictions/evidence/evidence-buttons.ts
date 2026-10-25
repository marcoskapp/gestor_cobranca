import { Component, inject, input, output, signal } from '@angular/core';
import { finalize } from 'rxjs';
import { NotificationService } from '../../../../core/notification/notification.service';
import { RestrictionsApi } from '../../data/compliance.api';
import { EVIDENCE_ACCEPT, evidenceProblem } from '../../data/compliance.labels';
import { RestrictionView } from '../../data/compliance.models';
import { NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../../../shared/components/icon/icon';

/**
 * Comprovante da restrição (opt-out por escrito, ofício, gravação): anexar uma única vez, em
 * restrição ativa, e baixar (o download é registrado no log de acesso como `FILE_DOWNLOADED`).
 */
@Component({
  selector: 'app-evidence-buttons',
  imports: [Icon, NgbTooltip],
  template: `
    @let r = restriction();
    @if (r.hasEvidence) {
      <button class="btn btn-sm btn-outline-secondary border-0"
       
        type="button"
        (click)="download(r)"
        [attr.aria-label]="'Baixar evidência ' + (r.evidenceFileName ?? '')"
        [ngbTooltip]="'Baixar ' + (r.evidenceFileName ?? 'evidência') + ' (acesso registrado)'"
      >
        <app-icon name="paperclip" />
      </button>
    } @else if (canManage() && r.status === 'ACTIVE') {
      <input #file type="file" class="visually-hidden" [accept]="accept" (change)="upload(r, file)" tabindex="-1" aria-hidden="true" />
      <button class="btn btn-sm btn-outline-secondary border-0"
       
        type="button"
        [disabled]="busy()"
        (click)="file.click()"
        aria-label="Anexar evidência"
        ngbTooltip="Anexar evidência (PDF, imagem, áudio ou texto até 20 MB)"
      >
        @if (busy()) {
          <span class="spinner-border spinner-border-sm" aria-hidden="true"></span>
        } @else {
          <app-icon name="file-earmark-arrow-up" />
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
