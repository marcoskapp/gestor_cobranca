import { Injectable, inject } from '@angular/core';
import { DialogRef } from '../../../shared/dialog/dialog-ref';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { OnboardingService } from '../../onboarding/onboarding.service';

/** Ações da central de ajuda (menu "?" do cabeçalho, paleta de comandos e atalhos). */
@Injectable({ providedIn: 'root' })
export class HelpService {
  private readonly dialog = inject(DialogService);
  private readonly onboarding = inject(OnboardingService);
  private shortcuts: DialogRef | null = null;

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
