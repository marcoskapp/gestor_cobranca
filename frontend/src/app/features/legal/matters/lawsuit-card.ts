import { Component, computed, inject, input, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { catchError, of } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { FormDialogService, options } from '../components/form-dialog';
import { LegalProceedingsApi } from '../data/legal.api';
import {
  HEARING_MODE_LABELS,
  HEARING_OUTCOME_LABELS,
  HEARING_TYPE_LABELS,
  LAWSUIT_STATUS_NEXT,
  MOVEMENT_LABELS,
  ROUTE_LABELS,
} from '../data/legal.labels';
import { HearingView, LawsuitView, MovementResult } from '../data/legal.models';
import { describeStatus } from '../../../shared/status/status';

/** Processo com andamentos (linha do tempo), audiências e comandos (andamento, audiência, situação). */
@Component({
  selector: 'app-lawsuit-card',
  imports: [MatButtonModule, MatIconModule, MatMenuModule, Amount, StatusChip, AppDatePipe],
  template: `
    @let l = lawsuit();
    <article class="lc" [attr.aria-label]="'Processo ' + l.cnjFormatted">
      <header class="lc-head">
        <div>
          <h3 class="lc-cnj app-mono">{{ l.cnjFormatted }}</h3>
          <div class="app-muted">
            {{ routes[l.route] }}{{ l.actionClass ? ' · ' + l.actionClass : '' }} · {{ l.courtName ?? 'Tribunal ' + l.courtCode }}{{ l.courtUnit ? ' · ' + l.courtUnit : '' }}
          </div>
          <div class="lc-meta">
            <app-status-chip domain="lawsuit" [status]="l.status" />
            <span>distribuído em {{ l.filedOn | appDate: 'date' }}</span>
            @if (l.claimAmount) {
              <span>causa <app-amount [value]="l.claimAmount" /></span>
            }
            <span class="app-muted">credor no polo {{ l.creditorPole === 'ACTIVE' ? 'ativo' : 'passivo' }}</span>
          </div>
        </div>
        @if (canManage()) {
          <div class="lc-actions">
            <button mat-stroked-button type="button" (click)="addMovement()">
              <mat-icon aria-hidden="true">add</mat-icon>
              Andamento
            </button>
            <button mat-icon-button type="button" [matMenuTriggerFor]="menu" [attr.aria-label]="'Mais ações do processo ' + l.cnjFormatted">
              <mat-icon aria-hidden="true">more_vert</mat-icon>
            </button>
            <mat-menu #menu="matMenu">
              <button mat-menu-item type="button" (click)="scheduleHearing(null)"><mat-icon aria-hidden="true">event</mat-icon>Agendar audiência</button>
              @if (nextStatuses().length) {
                <button mat-menu-item type="button" (click)="changeStatus()"><mat-icon aria-hidden="true">swap_horiz</mat-icon>Alterar situação</button>
              }
            </mat-menu>
          </div>
        }
      </header>

      <button mat-button type="button" (click)="expanded.set(!expanded())" [attr.aria-expanded]="expanded()">
        <mat-icon aria-hidden="true">{{ expanded() ? 'expand_less' : 'timeline' }}</mat-icon>
        {{ expanded() ? 'Ocultar andamentos e audiências' : 'Ver andamentos e audiências' }}
      </button>

      @if (expanded()) {
        <h4 class="app-section-title">Audiências</h4>
        <ul class="lc-list">
          @for (h of hearingRows(); track h.id) {
            <li>
              <app-status-chip domain="hearing" [status]="h.status" />
              <strong>{{ hearingTypes[h.type] ?? h.type }}</strong>
              <span>{{ h.scheduledAt | appDate }} · {{ modes[h.mode] }}</span>
              @if (h.link) {
                <a [href]="h.link" target="_blank" rel="noopener noreferrer">Link da sala</a>
              } @else if (h.location) {
                <span class="app-muted">{{ h.location }}</span>
              }
              @if (h.outcome) {
                <span class="app-muted">resultado: {{ outcomes[h.outcome] ?? h.outcome }}</span>
              }
              @if (canManage() && h.status === 'SCHEDULED') {
                <span class="lc-inline">
                  <button mat-button type="button" (click)="hearingResult(h)">Registrar resultado</button>
                  <button mat-button type="button" (click)="scheduleHearing(h)">Remarcar</button>
                  <button mat-button type="button" (click)="cancelHearing(h)">Cancelar</button>
                </span>
              }
            </li>
          } @empty {
            <li class="app-muted">Nenhuma audiência.</li>
          }
        </ul>

        <h4 class="app-section-title">Andamentos</h4>
        <ol class="lc-timeline">
          @for (m of movementRows(); track m.id) {
            <li>
              <span class="lc-date">{{ m.occurredOn | appDate: 'date' }}</span>
              <div>
                <strong>{{ movementLabels[m.type] ?? m.type }}</strong>
                <span class="app-muted"> · {{ m.source === 'PROVIDER' ? 'capturado' : 'lançado manualmente' }}</span>
                @if (m.amount) {
                  · <app-amount [value]="m.amount" />
                }
                @if (m.description) {
                  <p class="lc-desc">{{ m.description }}</p>
                }
              </div>
            </li>
          } @empty {
            <li class="app-muted">Nenhum andamento registrado.</li>
          }
        </ol>
      }
    </article>
  `,
  styles: `
    .lc {
      padding: 12px 0;
      border-bottom: 1px solid var(--app-border);
    }
    .lc-head {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: 8px;
    }
    .lc-cnj {
      margin: 0;
      font-size: var(--app-text-md);
      font-weight: 600;
    }
    .lc-meta,
    .lc-actions,
    .lc-list li,
    .lc-inline {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 12px;
      font-size: var(--app-text-sm);
    }
    .lc-meta {
      margin-top: 4px;
    }
    .lc-list,
    .lc-timeline {
      margin: 0 0 12px;
      padding: 0;
      list-style: none;
      li {
        padding: 6px 0;
        border-bottom: 1px solid var(--app-border);
      }
    }
    .lc-timeline li {
      display: grid;
      grid-template-columns: 96px 1fr;
      gap: 8px;
      font-size: var(--app-text-sm);
    }
    .lc-date {
      font-variant-numeric: tabular-nums;
      color: var(--mat-sys-on-surface-variant);
    }
    .lc-desc {
      margin: 4px 0 0;
      white-space: pre-wrap;
    }
  `,
})
export class LawsuitCard {
  readonly lawsuit = input.required<LawsuitView>();
  readonly canManage = input(false);
  readonly changed = output<void>();

  private readonly api = inject(LegalProceedingsApi);
  private readonly forms = inject(FormDialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly routes = ROUTE_LABELS;
  protected readonly movementLabels = MOVEMENT_LABELS;
  protected readonly hearingTypes = HEARING_TYPE_LABELS;
  protected readonly modes = HEARING_MODE_LABELS;
  protected readonly outcomes = HEARING_OUTCOME_LABELS;
  protected readonly expanded = signal(false);
  protected readonly nextStatuses = computed(() => LAWSUIT_STATUS_NEXT[this.lawsuit().status] ?? []);

  private readonly movements = rxResource({
    params: () => (this.expanded() ? this.lawsuit().id : undefined),
    stream: ({ params }) => this.api.movements(params, { page: 0, size: 100 }).pipe(catchError(() => of({ content: [], page: 0, size: 0, totalElements: 0, totalPages: 0 }))),
  });
  protected readonly movementRows = computed(() => (this.movements.hasValue() ? [...this.movements.value()!.content].sort((a, b) => b.occurredOn.localeCompare(a.occurredOn)) : []));
  private readonly hearings = rxResource({
    params: () => (this.expanded() ? this.lawsuit().id : undefined),
    stream: ({ params }) => this.api.hearings(params).pipe(catchError(() => of<HearingView[]>([]))),
  });
  protected readonly hearingRows = valueOr(this.hearings, []);

  private done(message: string): void {
    this.notifications.success(message);
    this.movements.reload();
    this.hearings.reload();
    this.changed.emit();
  }

  protected addMovement(): void {
    this.forms
      .open<MovementResult>({
        title: 'Registrar andamento',
        message: 'Citação move o caso para "em andamento" e interrompe a prescrição dos títulos.',
        submitLabel: 'Registrar',
        fields: [
          { key: 'occurredOn', label: 'Data', type: 'date', required: true },
          { key: 'type', label: 'Andamento', type: 'select', required: true, options: options(MOVEMENT_LABELS) },
          { key: 'description', label: 'Descrição (opcional)', type: 'textarea', maxLength: 6000, hint: 'Guardada criptografada.' },
          { key: 'amount', label: 'Valor (penhora, levantamento…)', type: 'money', min: 0.01 },
        ],
        submit: (v) => this.api.recordMovement(this.lawsuit().id, v),
      })
      .subscribe((r) =>
        this.done(r.prescriptionInterruptions ? `Andamento registrado; prescrição interrompida em ${r.prescriptionInterruptions} título(s).` : 'Andamento registrado.'),
      );
  }

  protected scheduleHearing(h: HearingView | null): void {
    this.forms
      .open<HearingView>({
        title: h ? 'Remarcar audiência' : 'Agendar audiência',
        submitLabel: h ? 'Remarcar' : 'Agendar',
        fields: [
          { key: 'type', label: 'Tipo', type: 'select', required: true, options: options(HEARING_TYPE_LABELS), value: h?.type ?? 'CONCILIATION' },
          { key: 'scheduledAt', label: 'Data e hora', type: 'datetime', required: true, value: h?.scheduledAt },
          { key: 'mode', label: 'Forma', type: 'select', required: true, options: options(HEARING_MODE_LABELS), value: h?.mode ?? 'VIDEO' },
          { key: 'location', label: 'Local', type: 'text', maxLength: 300, value: h?.location, showIf: (v) => v['mode'] !== 'VIDEO' },
          {
            key: 'link',
            label: 'Link da sala (https)',
            type: 'text',
            maxLength: 500,
            value: h?.link,
            showIf: (v) => v['mode'] !== 'IN_PERSON',
            validate: (v) => (/^https:\/\/\S+$/.test(String(v)) ? null : 'Informe um endereço https://'),
          },
          { key: 'externalParticipants', label: 'Participantes externos (opcional)', type: 'textarea', maxLength: 2000, hint: 'Guardado criptografado.' },
        ],
        submit: (v) => {
          const body = { ...v, participantUserIds: h?.participantUserIds ?? [] };
          return h ? this.api.rescheduleHearing(h.id, body) : this.api.scheduleHearing(this.lawsuit().id, body);
        },
      })
      .subscribe(() => this.done(h ? 'Audiência remarcada.' : 'Audiência agendada.'));
  }

  protected hearingResult(h: HearingView): void {
    this.forms
      .open<HearingView>({
        title: 'Resultado da audiência',
        submitLabel: 'Registrar',
        fields: [
          { key: 'outcome', label: 'Resultado', type: 'select', required: true, options: options(HEARING_OUTCOME_LABELS) },
          { key: 'notes', label: 'Observações (opcional)', type: 'textarea', maxLength: 6000, hint: 'Guardadas criptografadas.' },
        ],
        submit: (v) => this.api.hearingResult(h.id, v),
      })
      .subscribe(() => this.done('Resultado registrado.'));
  }

  protected cancelHearing(h: HearingView): void {
    this.confirm
      .confirm({ title: 'Cancelar audiência', message: 'A audiência deixa de constar da agenda do processo.', confirmLabel: 'Cancelar audiência', destructive: true, reason: 'required' })
      .subscribe((reason) => this.api.cancelHearing(h.id, reason).subscribe(() => this.done('Audiência cancelada.')));
  }

  protected changeStatus(): void {
    const l = this.lawsuit();
    this.forms
      .open<LawsuitView>({
        title: 'Alterar situação do processo',
        submitLabel: 'Alterar',
        details: [{ label: 'Situação atual', value: describeStatus('lawsuit', l.status).label }],
        fields: [
          { key: 'status', label: 'Nova situação', type: 'select', required: true, options: this.nextStatuses().map((s) => ({ value: s, label: describeStatus('lawsuit', s).label })) },
          { key: 'reason', label: 'Motivo', type: 'textarea', required: true, maxLength: 500 },
        ],
        submit: (v) => this.api.lawsuitStatus(l.id, v),
      })
      .subscribe(() => this.done('Situação do processo alterada.'));
  }
}
