import { Clipboard } from '@angular/cdk/clipboard';
import { Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Observable, finalize } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { PixAccountsApi } from '../data/payment.api';
import { PixAccountView } from '../data/payment.models';
import { CREDENTIAL_KEY_LABELS, certificateState, groupedFingerprint } from '../data/payment.rules';
import { PixAccountDialog, PixAccountDialogData } from './pix-account-dialog';
import { RotateDialog, RotateDialogData } from './rotate-dialog';

/**
 * Contas Pix (PSP) da assessoria e dos credores: dados de conexão, certificado mTLS (só impressão
 * digital e validade), segredos gravados (só os nomes), webhook e ativação. A conta ativa do credor
 * tem precedência; sem ela, vale a da assessoria.
 */
@Component({
  selector: 'app-pix-account-list',
  imports: [MatButtonModule, MatIconModule, MatMenuModule, MatTooltipModule, AppDatePipe, EmptyState, PageHeader, Skeleton, StatusChip],
  template: `
    <app-page-header title="Contas Pix" subtitle="Conexão com o PSP que gera as cobranças Pix e avisa os pagamentos. Uma conta ativa por credor; a da assessoria vale para os demais.">
      <button mat-flat-button type="button" (click)="create()">
        <mat-icon aria-hidden="true">add</mat-icon>
        Nova conta Pix
      </button>
    </app-page-header>

    @if (resource.isLoading() && !accounts().length) {
      <app-skeleton variant="card" [rows]="4" />
    } @else if (resource.status() === 'error') {
      <app-empty-state icon="cloud_off" tone="error" title="Não foi possível carregar as contas." message="Verifique a conexão e tente novamente.">
        <button mat-stroked-button type="button" (click)="resource.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (!accounts().length) {
      <app-empty-state icon="account_balance" title="Nenhuma conta Pix cadastrada." message="Sem conta ativa, não é possível gerar Pix para os devedores.">
        <button mat-flat-button type="button" (click)="create()">Nova conta Pix</button>
      </app-empty-state>
    } @else {
      <div class="pa-grid" data-tour="pix-accounts">
        @for (a of accounts(); track a.id) {
          @let cert = certificate(a);
          <section class="app-card pa-card" [attr.aria-labelledby]="'pa-' + a.id">
            <div class="pa-head">
              <div>
                <h2 class="app-card__title pa-title" [id]="'pa-' + a.id">{{ a.name }}</h2>
                <span class="app-muted">{{ a.creditorId ? creditors.name(a.creditorId) : 'Assessoria (todos os credores sem conta própria)' }}</span>
              </div>
              <app-status-chip domain="active" [status]="a.active" />
            </div>
            <dl class="app-definition-list">
              <dt>Provedor</dt>
              <dd>{{ a.provider === 'SANDBOX' ? 'Simulador (teste)' : 'API Pix do BACEN' }}</dd>
              <dt>Chave Pix</dt>
              <dd class="app-mono">{{ a.pixKey }}</dd>
              <dt>API</dt>
              <dd class="app-mono pa-break">{{ a.apiBaseUrl }}</dd>
            </dl>

            <h3 class="app-section-title">Certificado e segredos</h3>
            <div class="pa-row">
              <span class="app-status" [class]="'app-status--' + cert.tone"><mat-icon class="app-status__icon" aria-hidden="true">{{ cert.tone === 'success' ? 'verified' : cert.tone === 'neutral' ? 'remove_circle_outline' : 'warning_amber' }}</mat-icon>{{ cert.text }}</span>
              @if (a.certificateExpiresAt) {
                <span class="app-muted">até {{ a.certificateExpiresAt | appDate }}</span>
              }
            </div>
            @if (a.certificateSha256) {
              <p class="app-mono pa-fp" [attr.aria-label]="'Impressão digital SHA-256 ' + a.certificateSha256">{{ fingerprint(a.certificateSha256) }}</p>
            }
            <p class="app-muted pa-small">
              Gravados: {{ keys(a) }}{{ a.credentialsUpdatedAt ? ' · trocados em ' + (a.credentialsUpdatedAt | appDate) : '' }}
            </p>

            <h3 class="app-section-title">Webhook</h3>
            <div class="pa-row">
              @if (a.webhookRegisteredAt) {
                <span class="app-status app-status--success"><mat-icon class="app-status__icon" aria-hidden="true">check_circle</mat-icon>Registrado no PSP</span>
                <span class="app-muted">em {{ a.webhookRegisteredAt | appDate }}</span>
              } @else {
                <span class="app-status app-status--warning"><mat-icon class="app-status__icon" aria-hidden="true">warning_amber</mat-icon>Não registrado</span>
                <span class="app-muted">pagamentos só chegam pelo extrato</span>
              }
            </div>
            <p class="app-muted pa-small">{{ a.webhookAuth === 'MTLS' ? 'Autenticação mTLS · ' + a.webhookFingerprints.length + ' certificado(s) do PSP fixado(s)' : 'Autenticação por assinatura HMAC' }}</p>
            <div class="pa-url">
              <code class="pa-break">{{ a.webhookUrl }}</code>
              <button mat-icon-button type="button" (click)="copy(a.webhookUrl)" aria-label="Copiar URL do webhook" matTooltip="Copiar URL">
                <mat-icon aria-hidden="true">content_copy</mat-icon>
              </button>
            </div>

            <div class="pa-actions">
              <button mat-stroked-button type="button" (click)="registerWebhook(a)" [disabled]="busy() === a.id">
                <mat-icon aria-hidden="true">webhook</mat-icon>
                {{ a.webhookRegisteredAt ? 'Registrar de novo' : 'Registrar webhook' }}
              </button>
              <button mat-stroked-button type="button" (click)="rotate(a)" [disabled]="busy() === a.id" data-tour="pix-rotate">
                <mat-icon aria-hidden="true">sync_lock</mat-icon>
                Rotacionar credenciais
              </button>
              <button mat-icon-button type="button" [matMenuTriggerFor]="more" [attr.aria-label]="'Mais ações de ' + a.name">
                <mat-icon aria-hidden="true">more_vert</mat-icon>
              </button>
              <mat-menu #more="matMenu">
                <button mat-menu-item type="button" (click)="edit(a)"><mat-icon aria-hidden="true">edit</mat-icon>Editar dados</button>
                @if (a.active) {
                  <button mat-menu-item type="button" (click)="deactivate(a)"><mat-icon aria-hidden="true">block</mat-icon>Desativar</button>
                } @else {
                  <button mat-menu-item type="button" (click)="activate(a)"><mat-icon aria-hidden="true">check_circle</mat-icon>Ativar</button>
                }
              </mat-menu>
            </div>
          </section>
        }
      </div>
    }
  `,
  styles: `
    .pa-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 420px), 1fr));
      gap: var(--app-gap);
    }
    .pa-head {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 8px;
      margin-bottom: 8px;
    }
    .pa-title {
      margin-bottom: 2px;
    }
    .pa-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 10px;
    }
    .pa-fp {
      margin: 6px 0 0;
      font-size: var(--app-text-xs);
      word-break: break-all;
    }
    .pa-small {
      margin: 6px 0 0;
      font-size: var(--app-text-xs);
    }
    .pa-break {
      word-break: break-all;
    }
    .pa-url {
      display: flex;
      align-items: center;
      gap: 4px;
      margin-top: 6px;
      padding: 4px 4px 4px 10px;
      border-radius: var(--app-radius-sm);
      background: var(--app-surface-raised);
      font-size: var(--app-text-xs);
      code {
        flex: 1;
        min-width: 0;
      }
    }
    .pa-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-top: 16px;
    }
  `,
})
export class PixAccountList {
  private readonly api = inject(PixAccountsApi);
  private readonly dialog = inject(MatDialog);
  private readonly confirm = inject(ConfirmService);
  private readonly clipboard = inject(Clipboard);
  private readonly notifications = inject(NotificationService);
  protected readonly fingerprint = groupedFingerprint;
  protected readonly certificate = (a: PixAccountView) => certificateState(a);

  protected readonly resource = rxResource({ stream: () => this.api.list() });
  protected readonly accounts = valueOr(this.resource, []);
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.accounts().map((a) => a.creditorId));
  protected readonly busy = signal<string | null>(null);

  protected keys(a: PixAccountView): string {
    return a.credentialKeys.length ? a.credentialKeys.map((k) => CREDENTIAL_KEY_LABELS[k] ?? k).join(', ') : 'nenhum';
  }

  protected copy(text: string): void {
    if (this.clipboard.copy(text)) {
      this.notifications.info('URL do webhook copiada.');
    }
  }

  protected create(): void {
    this.open({});
  }

  protected edit(a: PixAccountView): void {
    this.open({ account: a });
  }

  protected rotate(a: PixAccountView): void {
    this.dialog
      .open<RotateDialog, RotateDialogData, PixAccountView>(RotateDialog, { data: { account: a }, width: '640px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((updated) => {
        if (updated) {
          this.notifications.success('Credenciais rotacionadas.');
          this.resource.reload();
        }
      });
  }

  protected registerWebhook(a: PixAccountView): void {
    this.confirm
      .confirm({
        title: 'Registrar webhook no PSP',
        message: 'O PSP passa a avisar os pagamentos desta conta na URL abaixo. Cada aviso é conferido com uma consulta ao PSP antes de baixar.',
        confirmLabel: 'Registrar',
        details: [
          { label: 'Conta', value: a.name },
          { label: 'URL', value: a.webhookUrl },
        ],
      })
      .subscribe(() => this.run(a, this.api.registerWebhook(a.id), 'Webhook registrado no PSP.'));
  }

  protected activate(a: PixAccountView): void {
    this.confirm
      .confirm({
        title: 'Ativar conta Pix',
        message: 'As novas instruções de pagamento deste credor passam a usar esta conta.',
        confirmLabel: 'Ativar',
        details: [{ label: 'Conta', value: a.name }],
      })
      .subscribe(() => this.run(a, this.api.activate(a.id), 'Conta ativada.'));
  }

  protected deactivate(a: PixAccountView): void {
    this.confirm
      .confirm({
        title: 'Desativar conta Pix',
        message: 'Novas instruções deixam de usar esta conta (sem conta própria, o credor passa a usar a da assessoria). QR Codes já emitidos continuam valendo no PSP.',
        confirmLabel: 'Desativar',
        destructive: true,
        details: [{ label: 'Conta', value: a.name }],
      })
      .subscribe(() => this.run(a, this.api.deactivate(a.id), 'Conta desativada.'));
  }

  private open(data: PixAccountDialogData): void {
    this.dialog
      .open<PixAccountDialog, PixAccountDialogData, PixAccountView>(PixAccountDialog, { data, width: '760px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((saved) => {
        if (saved) {
          this.notifications.success(data.account ? 'Conta atualizada.' : 'Conta cadastrada e ativa. Registre o webhook para o PSP avisar os pagamentos.');
          this.resource.reload();
        }
      });
  }

  private run(a: PixAccountView, call: Observable<PixAccountView>, message: string): void {
    this.busy.set(a.id);
    call.pipe(finalize(() => this.busy.set(null))).subscribe(() => {
      this.notifications.success(message);
      this.resource.reload();
    });
  }
}
