import { Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { SessionView } from '../../../core/auth/auth.models';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { SessionTable } from '../../../shared/components/session-table/session-table';
import { valueOr } from '../../../shared/data/resource-value';
import { AccountApi } from '../account.api';

@Component({
  selector: 'app-my-sessions',
  imports: [PageHeader, SessionTable],
  template: `
    <app-page-header
      title="Minhas sessões"
      subtitle="Acessos abertos com a sua conta. Encerre os que você não reconhecer e troque a senha."
    />
    <app-session-table
      caption="Minhas sessões"
      [sessions]="rows()"
      [loading]="sessions.isLoading()"
      [failed]="sessions.status() === 'error'"
      [revocable]="true"
      (revoke)="revoke($event)"
      (retry)="sessions.reload()"
    />
  `,
})
export class MySessions {
  private readonly api = inject(AccountApi);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly sessions = rxResource({ stream: () => this.api.sessions() });
  protected readonly rows = valueOr(this.sessions, []);

  protected revoke(session: SessionView): void {
    this.confirm
      .confirm({
        title: 'Encerrar sessão',
        message: 'A sessão selecionada será encerrada imediatamente naquele dispositivo.',
        confirmLabel: 'Encerrar',
        destructive: true,
      })
      .subscribe(() =>
        this.api.revokeSession(session.id).subscribe(() => {
          this.notifications.success('Sessão encerrada.');
          this.sessions.reload();
        }),
      );
  }
}
