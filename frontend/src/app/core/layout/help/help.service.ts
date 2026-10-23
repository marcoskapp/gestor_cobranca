import { Injectable, inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { OnboardingService } from '../../onboarding/onboarding.service';

/** Ações da central de ajuda (menu "?" do cabeçalho, paleta de comandos e atalhos). */
@Injectable({ providedIn: 'root' })
export class HelpService {
  private readonly dialog = inject(MatDialog);
  private readonly onboarding = inject(OnboardingService);

  async openWhatsNew(): Promise<void> {
    const { WhatsNewDialog } = await import('../../onboarding/whats-new/whats-new-dialog');
    this.onboarding.markReleaseNotesRead();
    this.dialog.open(WhatsNewDialog, { width: '600px' });
  }

  async openShortcuts(): Promise<void> {
    const { ShortcutsDialog } = await import('./shortcuts-dialog');
    if (this.dialog.openDialogs.some((d) => d.componentInstance instanceof ShortcutsDialog)) {
      return;
    }
    this.dialog.open(ShortcutsDialog, { width: '520px' });
  }
}
