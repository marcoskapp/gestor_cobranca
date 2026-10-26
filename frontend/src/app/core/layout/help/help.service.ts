import { Injectable, inject } from '@angular/core';
import { NgbOffcanvas, NgbOffcanvasRef } from '@ng-bootstrap/ng-bootstrap';
import { DialogRef } from '../../../shared/dialog/dialog-ref';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { OnboardingService } from '../../onboarding/onboarding.service';

/** Ações da central de ajuda (menu "?" do cabeçalho, paleta de comandos e atalhos). */
@Injectable({ providedIn: 'root' })
export class HelpService {
  private readonly dialog = inject(DialogService);
  private readonly onboarding = inject(OnboardingService);
  private readonly offcanvas = inject(NgbOffcanvas);
  private shortcuts: DialogRef | null = null;
  private manualPanel: NgbOffcanvasRef | null = null;

  /** "Sobre esta tela": manual rápido da tela atual em painel lateral (conteúdo sob demanda). */
  async openScreenManual(): Promise<void> {
    if (this.manualPanel) {
      return;
    }
    const { ScreenManualPanel, MANUAL_PANEL_TITLE_ID } = await import('../../onboarding/manual/screen-manual-panel');
    if (this.manualPanel) {
      return;
    }
    const ref = this.offcanvas.open(ScreenManualPanel, {
      position: 'end',
      panelClass: 'app-manual-panel',
      ariaLabelledBy: MANUAL_PANEL_TITLE_ID,
    });
    this.manualPanel = ref;
    const clear = () => (this.manualPanel = null);
    ref.result.then(clear, clear);
  }

  async openWhatsNew(): Promise<void> {
    const { WhatsNewDialog } = await import('../../onboarding/whats-new/whats-new-dialog');
    this.onboarding.markReleaseNotesRead();
    this.dialog.open(WhatsNewDialog, { size: 'lg' });
  }

  async openShortcuts(): Promise<void> {
    if (this.shortcuts) {
      return;
    }
    const { ShortcutsDialog } = await import('./shortcuts-dialog');
    if (this.shortcuts) {
      return;
    }
    const ref = this.dialog.open(ShortcutsDialog);
    this.shortcuts = ref;
    ref.afterClosed().subscribe(() => (this.shortcuts = null));
  }
}
