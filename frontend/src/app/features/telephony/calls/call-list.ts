import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { NgbInputDatepicker, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { RouterLink } from '@angular/router';
import { debounceTime, map, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { ConfirmService } from '../../../shared/components/confirm-dialog/confirm.service';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { pagedQuery } from '../../../shared/data/paged-query';
import { sameValue } from '../../../shared/data/same-value';
import { DialogService } from '../../../shared/dialog/dialog.service';
import { FORM_FIELD } from '../../../shared/forms/field';
import { endOfDayInstant, fromLocalDate, startOfDayInstant } from '../../../shared/models/dates';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { DebtorNames } from '../../debt/data/debt.lookups';
import { CreditorLookup, CreditorNames } from '../../organization/data/organization.lookups';
import { CallsApi } from '../data/telephony.api';
import { CALL_RESULT_LABELS, HANGUP_SIDE_LABELS } from '../data/telephony.labels';
import { CallFilter, CallResult, CallView } from '../data/telephony.models';
import { durationText, isCallActive } from '../data/telephony.rules';
import { RecordingDialog, RecordingDialogData } from './recording-dialog';

/**
 * Chamadas do escopo (supervisão): resultado, durações, tabulação e gravação. Ouvir exige
 * `CALL_RECORDING_LISTEN` e a finalidade declarada; encerrar uma chamada de outro operador é
 * auditado.
 */
@Component({
  selector: 'app-call-list',
  imports: [ReactiveFormsModule, RouterLink, NgbInputDatepicker, NgbTooltip, ...FORM_FIELD, Icon, AppDatePipe, CellDef, DataTable, EntityPicker, PageHeader, StatusChip],
  template: `
    <app-page-header title="Chamadas e gravações" subtitle="Clique para ligar, discador e chamadas recebidas do seu escopo, com o resultado, a tabulação e a gravação." />
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar chamadas">
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      <app-field label="Resultado">
        <select appInput formControlName="result">
          <option value="">Todos</option>
          @for (r of results; track r[0]) {
            <option [value]="r[0]">{{ r[1] }}</option>
          }
        </select>
      </app-field>
      <app-field label="Direção">
        <select appInput formControlName="direction">
          <option value="">Todas</option>
          <option value="OUTBOUND">Feitas</option>
          <option value="INBOUND">Recebidas</option>
        </select>
      </app-field>
      <app-field label="De">
        <div class="input-group">
          <input appInput formControlName="from" ngbDatepicker #from="ngbDatepicker" placeholder="dd/mm/aaaa" autocomplete="off" />
          <button class="btn btn-outline-secondary" type="button" (click)="from.toggle()" aria-label="Abrir calendário da data inicial"><app-icon name="calendar-event" /></button>
        </div>
      </app-field>
      <app-field label="Até">
        <div class="input-group">
          <input appInput formControlName="to" ngbDatepicker #to="ngbDatepicker" placeholder="dd/mm/aaaa" autocomplete="off" />
          <button class="btn btn-outline-secondary" type="button" (click)="to.toggle()" aria-label="Abrir calendário da data final"><app-icon name="calendar-event" /></button>
        </div>
      </app-field>
    </form>
    @if (!canListen()) {
      <p class="alert alert-secondary d-flex gap-2" role="note"><app-icon name="lock" /><span>Seu perfil não ouve gravações (permissão própria, com finalidade declarada e registro de acesso).</span></p>
    }
    <app-data-table
      caption="Chamadas"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="telephone"
      emptyMessage="Nenhuma chamada encontrada."
    >
      <ng-template appCell="when" [appCellOf]="query.rows()" let-c>
        {{ (c.answeredAt ?? c.dialStartedAt ?? c.queuedAt) | appDate }}
        <div class="app-muted small">{{ c.direction === 'INBOUND' ? 'recebida' : c.origin === 'DIALER' ? 'discador' : 'clique para ligar' }}</div>
      </ng-template>
      <ng-template appCell="debtor" [appCellOf]="query.rows()" let-c>
        @if (c.caseId) {
          <a [routerLink]="['/cobranca/casos', c.caseId]">{{ c.debtorId ? debtors.name(c.debtorId) : 'Caso' }}</a>
        } @else {
          <span class="app-muted">Não identificado</span>
        }
        <div class="app-muted small">{{ c.numberMasked ?? '—' }}@if (c.creditorId) { · {{ creditors.name(c.creditorId) }}}</div>
      </ng-template>
      <ng-template appCell="operator" [appCellOf]="query.rows()" let-c>{{ c.operatorId ? users.name(c.operatorId) : '—' }}</ng-template>
      <ng-template appCell="result" [appCellOf]="query.rows()" let-c>
        @if (c.result) {
          {{ resultText(c.result) }}
        } @else {
          <app-status-chip domain="call" [status]="c.status" />
        }
        <div class="app-muted small">conversa {{ duration(c.talkSeconds) }}{{ c.hangupSide ? ' · desligou: ' + (hangups[c.hangupSide] || c.hangupSide) : '' }}</div>
      </ng-template>
      <ng-template appCell="tabulation" [appCellOf]="query.rows()" let-c>
        {{ c.tabulationCode ?? (c.interactionState === 'AWAITING_TABULATION' ? 'aguardando operador' : '—') }}
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query.rows()" let-c>
        @if (c.recording; as r) {
          @if (r.status === 'STORED' && canListen()) {
            <button class="btn btn-link" type="button" (click)="listen(c)" aria-label="Ouvir gravação" ngbTooltip="Ouvir gravação (acesso registrado)" data-tour="recording-listen">
              <app-icon name="play-circle" />
            </button>
          } @else if (r.status !== 'STORED') {
            <app-status-chip domain="recording" [status]="r.status" />
          }
        }
        @if (active(c)) {
          <button class="btn btn-link" type="button" (click)="hangup(c)" aria-label="Encerrar chamada" ngbTooltip="Encerrar chamada (auditado)">
            <app-icon name="telephone-x" />
          </button>
        }
      </ng-template>
    </app-data-table>
  `,
})
export class CallList {
  private readonly api = inject(CallsApi);
  private readonly auth = inject(AuthService);
  private readonly dialog = inject(DialogService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly results = Object.entries(CALL_RESULT_LABELS) as [CallResult, string][];
  protected readonly hangups = HANGUP_SIDE_LABELS;
  protected readonly duration = durationText;
  protected readonly active = isCallActive;
  protected readonly canListen = computed(() => this.auth.hasPermission(Permission.CALL_RECORDING_LISTEN));

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly filters = this.fb.group({
    creditor: this.fb.control<string[]>([]),
    result: [''],
    direction: [''],
    from: this.fb.control<string | null>(null),
    to: this.fb.control<string | null>(null),
  });
  private readonly filter = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map(() => this.current()),
    ),
    { initialValue: this.current(), equal: sameValue },
  );
  protected readonly query = pagedQuery(this.filter, (f, page) => this.api.search(f, page));
  protected readonly debtors = nameMap(inject(DebtorNames), () => this.query.rows().map((c) => c.debtorId), { batch: 100 });
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.query.rows().map((c) => c.creditorId));
  protected readonly users = nameMap(inject(UserNames), () => this.query.rows().map((c) => c.operatorId), { batch: USER_NAMES_BATCH });

  protected readonly columns: TableColumn[] = [
    { key: 'when', header: 'Quando' },
    { key: 'debtor', header: 'Devedor e número' },
    { key: 'operator', header: 'Operador' },
    { key: 'result', header: 'Resultado' },
    { key: 'tabulation', header: 'Tabulação' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected resultText(result: CallResult): string {
    return CALL_RESULT_LABELS[result] ?? result;
  }

  protected listen(c: CallView): void {
    this.dialog.open<RecordingDialog, RecordingDialogData>(RecordingDialog, {
      data: { call: c, debtorName: c.debtorId ? this.debtors.name(c.debtorId) : 'Não identificado' },
    });
  }

  protected hangup(c: CallView): void {
    this.confirm
      .confirm({
        title: 'Encerrar chamada',
        message: 'A chamada é encerrada no provedor. O encerramento pela supervisão fica registrado na auditoria.',
        confirmLabel: 'Encerrar',
        destructive: true,
        details: [
          { label: 'Operador', value: c.operatorId ? this.users.name(c.operatorId) : '—' },
          { label: 'Número', value: c.numberMasked ?? '—' },
        ],
      })
      .subscribe(() =>
        this.api.hangup(c.id).subscribe(() => {
          this.notifications.success('Chamada encerrada.');
          this.query.reload();
        }),
      );
  }

  private current(): CallFilter {
    const v = this.filters.getRawValue();
    return {
      creditorId: v.creditor[0] ?? null,
      result: v.result || null,
      direction: v.direction || null,
      // data digitada incompleta ou inválida (erro do datepicker) não filtra
      from: this.filters.controls.from.valid ? startOfDayInstant(fromLocalDate(v.from)) : null,
      to: this.filters.controls.to.valid ? endOfDayInstant(fromLocalDate(v.to)) : null,
    };
  }
}
