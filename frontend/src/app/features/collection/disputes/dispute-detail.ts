import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { AppValidators } from '../../../shared/validation/validators';
import { EVIDENCE_ACCEPT, evidenceProblem } from '../../compliance/data/compliance.labels';
import { DisputesApi } from '../data/collection.api';
import { DISPUTE_ORIGIN_LABELS, DISPUTE_OUTCOME_LABELS, DISPUTE_TYPE_LABELS } from '../data/collection.labels';
import { DisputeOutcome, DisputeView, EvidenceView } from '../data/collection.models';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { DIALOG_DATA } from '../../../shared/dialog/dialog-ref';

export interface DisputeDetailData {
  dispute: DisputeView;
  debtorName: string;
}

const NOTE_MAX = 4000;

/** Ações disponíveis pela situação da contestação. */
export function disputeActions(status: string): ('review' | 'resolve' | 'withdraw')[] {
  if (status === 'OPEN') {
    return ['review', 'resolve', 'withdraw'];
  }
  return status === 'UNDER_REVIEW' ? ['resolve', 'withdraw'] : [];
}

/**
 * Detalhe da contestação: descrição, prazo, evidências (download registrado) e resolução com
 * parecer. Resolver ou retirar libera os títulos e reavalia a elegibilidade do caso; o efeito
 * financeiro de uma contestação procedente é ação explícita nos títulos.
 */
@Component({
  selector: 'app-dispute-detail',
  imports: [ReactiveFormsModule, RouterLink, Amount, StatusChip, AppDatePipe, Icon, ...FORM_FIELD, ...DIALOG_DIRECTIVES],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Contestação · {{ types[d().type] }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <div class="modal-body">
      <dl class="app-definition-list">
        <dt>Devedor</dt>
        <dd><a [routerLink]="['/cobranca/casos', d().caseId]" appDialogClose>{{ data.debtorName }}</a></dd>
        <dt>Situação</dt>
        <dd><app-status-chip domain="dispute" [status]="d().status" /></dd>
        <dt>Origem</dt>
        <dd>{{ origins[d().origin] }} · aberta em {{ d().openedAt | appDate }}</dd>
        <dt>Prazo de resposta</dt>
        <dd [class.dd-late]="d().overdue">{{ d().responseDueDate | appDate: 'date' }}{{ d().overdue ? ' (vencido)' : '' }}</dd>
        <dt>Títulos</dt>
        <dd>
          {{ d().titleIds.length }} · {{ d().blocksCollection ? 'cobrança e contato suspensos' : 'sem suspensão' }}
          @if (d().titles.length) {
            <ul class="dd-titles" aria-label="Títulos contestados">
              @for (t of d().titles; track t.titleId) {
                <li>
                  <span class="app-mono">{{ t.externalId }}{{ t.installmentNumber ? ' / ' + t.installmentNumber : '' }}</span>
                  <span class="app-muted">venc. {{ t.dueDate | appDate: 'date' }}</span>
                  <app-amount [value]="t.principalAmount" />
                </li>
              }
            </ul>
          }
        </dd>
        @if (d().disputedAmount !== null) {
          <dt>Valor contestado</dt>
          <dd><app-amount [value]="d().disputedAmount" /></dd>
        }
        <dt>Descrição</dt>
        <dd class="dd-pre">{{ d().description || '—' }}</dd>
        @if (d().outcome) {
          <dt>Resultado</dt>
          <dd>{{ outcomes[d().outcome!] }} · {{ d().resolvedAt | appDate }}</dd>
          <dt>Parecer</dt>
          <dd class="dd-pre">{{ d().resolutionNote }}</dd>
        }
      </dl>

      <h3 class="app-section-title">Evidências ({{ evidenceRows().length }})</h3>
      <ul class="dd-evidence">
        @for (e of evidenceRows(); track e.id) {
          <li>
            <app-icon name="file-earmark-text" />
            <span>{{ e.fileName }}</span>
            <span class="app-muted">{{ size(e) }} · {{ e.createdAt | appDate }}{{ e.createdByName ? ' · ' + e.createdByName : '' }}</span>
            <span class="app-muted app-mono" [attr.title]="'SHA-256 ' + e.sha256">{{ e.sha256.slice(0, 12) }}…</span>
            @if (canDownload()) {
              <button class="btn btn-sm btn-outline-secondary border-0" type="button" (click)="download(e)" [attr.aria-label]="'Baixar ' + e.fileName + ' (acesso registrado)'">
                <app-icon name="download" />
              </button>
            }
          </li>
        } @empty {
          <li class="app-muted">Nenhuma evidência anexada.</li>
        }
      </ul>
      @if (open() && canAttach()) {
        <input #file type="file" [accept]="accept" (change)="attach(file.files); file.value = ''" hidden />
        <button class="btn btn-outline-primary" type="button" (click)="file.click()" [disabled]="busy()">
          <app-icon name="paperclip" />
          Anexar evidência
        </button>
      }

      @if (resolving()) {
        <form class="dd-resolve" [formGroup]="form" (ngSubmit)="resolve()" id="resolve-form" novalidate>
          <h3 class="app-section-title">Resolver</h3>
          <app-field class="app-full-width" label="Resultado">
            <select appInput formControlName="outcome" required>
              @for (o of outcomeOptions; track o.value) {
                <option [ngValue]="o.value">{{ o.label }}</option>
              }
            </select>
          </app-field>
          <app-field class="app-full-width" label="Parecer" hint="Procedente não altera o título: ajuste ou cancele o título na tela de títulos.">
            <textarea appInput formControlName="note" rows="4" [maxlength]="noteMax" required></textarea>
          </app-field>
        </form>
      }
    </div>
    <div class="modal-footer">
      <button class="btn btn-link" type="button" [appDialogClose]="changed()">Fechar</button>
      @if (canManage()) {
        @if (actions().includes('withdraw')) {
          <button class="btn btn-outline-primary" type="button" (click)="withdraw()" [disabled]="busy()">Registrar desistência</button>
        }
        @if (actions().includes('review')) {
          <button class="btn btn-outline-primary" type="button" (click)="review()" [disabled]="busy()">Enviar para análise</button>
        }
        @if (actions().includes('resolve')) {
          @if (resolving()) {
            <button class="btn btn-primary" type="submit" form="resolve-form" [disabled]="busy()">Confirmar resolução</button>
          } @else {
            <button class="btn btn-primary" type="button" (click)="resolving.set(true)">Resolver</button>
          }
        }
      }
    </div>
  `,
  styles: `
    .dd-late {
      font-weight: 600;
      color: var(--app-danger-fg);
    }
    .dd-pre {
      white-space: pre-line;
    }
    .dd-titles {
      display: grid;
      gap: 2px;
      margin: 4px 0 0;
      padding: 0;
      list-style: none;
      li {
        display: flex;
        flex-wrap: wrap;
        gap: 4px 8px;
        align-items: baseline;
      }
    }
    .dd-evidence {
      display: grid;
      gap: 4px;
      margin: 0 0 8px;
      padding: 0;
      list-style: none;
      li {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 4px 8px;
        font-size: var(--app-text-sm);
      }
    }
    .dd-resolve {
      margin-top: 12px;
    }
  `,
})
export class DisputeDetail {
  protected readonly data = inject<DisputeDetailData>(DIALOG_DATA);
  private readonly api = inject(DisputesApi);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly types = DISPUTE_TYPE_LABELS;
  protected readonly origins = DISPUTE_ORIGIN_LABELS;
  protected readonly outcomes = DISPUTE_OUTCOME_LABELS;
  protected readonly outcomeOptions = Object.entries(DISPUTE_OUTCOME_LABELS).map(([value, label]) => ({ value: value as DisputeOutcome, label }));
  protected readonly accept = EVIDENCE_ACCEPT;
  protected readonly noteMax = NOTE_MAX;

  protected readonly d = signal(this.data.dispute);
  protected readonly changed = signal(false);
  protected readonly busy = signal(false);
  protected readonly resolving = signal(false);
  protected readonly open = computed(() => this.d().status === 'OPEN' || this.d().status === 'UNDER_REVIEW');
  protected readonly actions = computed(() => disputeActions(this.d().status));
  protected readonly canManage = computed(() => this.auth.hasPermission(Permission.DISPUTE_MANAGE));
  protected readonly canAttach = computed(() => this.auth.hasAnyPermission([Permission.DISPUTE_MANAGE, Permission.CASE_WORK]));
  protected readonly canDownload = this.canAttach;

  private readonly evidences = rxResource({ params: () => this.d().id, stream: ({ params }) => this.api.evidences(params) });
  protected readonly evidenceRows = valueOr(this.evidences, []);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    outcome: this.fb.control<DisputeOutcome | ''>('', [AppValidators.notBlank]),
    note: ['', [AppValidators.notBlank, Validators.maxLength(NOTE_MAX)]],
  });

  protected size(e: EvidenceView): string {
    return e.size >= 1024 * 1024 ? `${(e.size / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(e.size / 1024))} KB`;
  }

  protected download(e: EvidenceView): void {
    this.api.downloadEvidence(this.d().id, e).subscribe();
  }

  protected attach(list: FileList | null): void {
    const file = list?.[0];
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
      .attachEvidence(this.d().id, file)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe(() => {
        this.notifications.success('Evidência anexada.');
        this.evidences.reload();
        this.changed.set(true);
      });
  }

  protected review(): void {
    this.run(this.api.review(this.d().id), 'Contestação enviada para análise.');
  }

  protected resolve(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.confirm
      .confirm({
        title: 'Resolver contestação',
        message: 'Os títulos deixam de estar contestados e o caso volta à cobrança se não houver outra contestação bloqueante.',
        confirmLabel: 'Resolver',
        details: [
          { label: 'Devedor', value: this.data.debtorName },
          { label: 'Resultado', value: DISPUTE_OUTCOME_LABELS[v.outcome as DisputeOutcome] },
        ],
      })
      .subscribe(() => this.run(this.api.resolve(this.d().id, v.outcome as DisputeOutcome, v.note.trim()), 'Contestação resolvida.'));
  }

  protected withdraw(): void {
    this.confirm
      .confirm({
        title: 'Registrar desistência',
        message: 'O devedor (ou quem abriu) desistiu da contestação. A cobrança dos títulos é retomada.',
        confirmLabel: 'Registrar desistência',
        reason: 'required',
        reasonLabel: 'Parecer',
        details: [{ label: 'Devedor', value: this.data.debtorName }],
      })
      .subscribe((note) => this.run(this.api.withdraw(this.d().id, note), 'Desistência registrada.'));
  }

  private run(call: ReturnType<DisputesApi['review']>, message: string): void {
    this.busy.set(true);
    call.pipe(finalize(() => this.busy.set(false))).subscribe((updated) => {
      this.d.set(updated);
      this.changed.set(true);
      this.resolving.set(false);
      this.notifications.success(message);
    });
  }
}
