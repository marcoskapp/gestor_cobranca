import { Injectable, inject } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';

/** Mensagens transitórias ao usuário (MatSnackBar), anunciadas a leitores de tela. */
@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly snackBar = inject(MatSnackBar);

  success(message: string): void {
    this.snackBar.open(message, 'Fechar', { panelClass: 'app-snack-success', politeness: 'polite' });
  }

  info(message: string): void {
    this.snackBar.open(message, 'Fechar', { panelClass: 'app-snack-info', politeness: 'polite' });
  }

  error(message: string): void {
    this.snackBar.open(message, 'Fechar', {
      panelClass: 'app-snack-error',
      politeness: 'assertive',
      duration: 10000,
    });
  }
}
