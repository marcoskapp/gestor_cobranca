import { Injectable, inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { Observable, filter, map } from 'rxjs';
import { ConfirmDialog, ConfirmDialogData, ConfirmDialogResult } from './confirm-dialog';

/** Atalho para diálogos de confirmação. Emite somente quando o usuário confirma. */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  private readonly dialog = inject(MatDialog);

  /** Emite o motivo informado (`''` quando o diálogo não pede motivo). */
  confirm(data: ConfirmDialogData): Observable<string> {
    return this.dialog
      .open<ConfirmDialog, ConfirmDialogData, ConfirmDialogResult>(ConfirmDialog, { data, width: '480px' })
      .afterClosed()
      .pipe(
        filter((result): result is string => typeof result === 'string'),
        map((reason) => reason),
      );
  }
}
