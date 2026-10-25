import { Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../auth/auth.service';
import { NAVIGATION } from '../../layout/navigation';
import { DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { RELEASE_NOTES } from '../content/release-notes';
import { ReleaseNoteItem } from '../onboarding.models';

/** Central "Novidades": mudanças por versão, com atalho para conhecer cada uma. */
@Component({
  selector: 'app-whats-new-dialog',
  imports: [...DIALOG_DIRECTIVES, AppDatePipe],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Novidades</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <div class="modal-body">
      @for (note of notes; track note.version) {
        <section class="note" [attr.aria-labelledby]="'nota-' + note.version">
          <h3 class="note__title" [id]="'nota-' + note.version">{{ note.title }}</h3>
          <p class="note__date app-muted">{{ note.date | appDate: 'date' }}</p>
          <ul class="note__items">
            @for (item of note.items; track item.text) {
              <li>
                {{ item.text }}
                @if (canOpen(item)) {
                  <button type="button" class="btn btn-sm btn-link note__link" (click)="open(item)">Conhecer</button>
                }
              </li>
            }
          </ul>
        </section>
      }
    </div>
    <div class="modal-footer">
      <button type="button" class="btn btn-primary" ngbAutofocus appDialogClose>Fechar</button>
    </div>
  `,
  styles: `
    .note + .note {
      margin-top: 20px;
      padding-top: 16px;
      border-top: var(--bs-border-width) solid var(--bs-border-color);
    }
    .note__title {
      margin: 0;
      font-size: var(--app-text-lg);
      font-weight: 400;
    }
    .note__date {
      margin: 2px 0 8px;
      font-size: var(--app-text-xs);
    }
    .note__items {
      margin: 0;
      padding-left: 20px;
      line-height: var(--app-line-normal);
    }
    .note__link {
      margin-left: 4px;
    }
  `,
})
export class WhatsNewDialog {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly ref = inject(DialogRef);
  protected readonly notes = RELEASE_NOTES;
  private readonly allowed = new Set(
    NAVIGATION.flatMap((s) => s.items)
      .filter((i) => this.auth.hasAnyPermission(i.permissions))
      .map((i) => i.route),
  );

  protected canOpen(item: ReleaseNoteItem): boolean {
    return !!item.route && this.allowed.has(item.route);
  }

  protected open(item: ReleaseNoteItem): void {
    this.ref.close();
    void this.router.navigateByUrl(item.route as string);
  }
}
