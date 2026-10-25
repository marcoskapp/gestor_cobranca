import { Injectable, inject } from '@angular/core';
import { Observable, filter } from 'rxjs';
import { DialogService } from '../../dialog/dialog.service';
import { ConfirmDialog, ConfirmDialogData, ConfirmDialogResult } from './confirm-dialog';

/** Atalho para diálogos de confirmação. Emite somente quando o usuário confirma. */
@Injectable({ providedIn: 'root' })
export class ConfirmService {
  private readonly dialog = inject(DialogService);

  /** Emite o motivo informado (`''` quando o diálogo não pede motivo). */
  confirm(data: ConfirmDialogData): Observable<string> {
    return this.dialog
      .open<ConfirmDialog, ConfirmDialogData, ConfirmDialogResult>(ConfirmDialog, {
        data,
        role: data.destructive ? 'alertdialog' : 'dialog',
      })
      .afterClosed()
      .pipe(filter((result): result is string => typeof result === 'string'));
  }
}
