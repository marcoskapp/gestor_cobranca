import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount } from '../../../shared/components/amount/amount';
import { Icon } from '../../../shared/components/icon/icon';
import { valueOr } from '../../../shared/data/resource-value';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { toDateStruct } from '../../../shared/models/dates';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { AppValidators } from '../../../shared/validation/validators';
import { EVIDENCE_ACCEPT, evidenceProblem } from '../../compliance/data/compliance.labels';
import { DisputesApi } from '../data/collection.api';
import {
  ADJUSTMENT_ACTION_LABELS,
  CREDITOR_POSITION_LABELS,
  DISPUTE_OUTCOME_LABELS,
  NOTICE_CHANNEL_LABELS,
  NOTICE_REASON_LABELS,
} from '../data/collection.labels';
import {
  AdjustmentAction,
  CreditorPosition,
  DisputeOutcome,
  CreditorRequestDraft,
  CreditorRequestView,
  CreditorResponseView,
  DebtorNoticeDraft,
  DebtorNoticeView,
  DisputeView,
  NoticeChannel,
} from '../data/collection.models';
import { addDays, zonedNow } from '../data/collection.rules';
import { suggestedAdjustment, unresolvedPlaceholders } from './dispute.rules';

const TEXT_MAX = 8000;
const NOTE_MAX = 4000;

// =================================================================== atribuir

export interface AssignDisputeData {
  dispute: DisputeView;
}

/** Atribuir a um analista (com a carga atual de cada um) ou devolver sem responsável. */
@Component({
  selector: 'app-assign-dispute-dialog',
  imports: [ReactiveFormsModule, Icon, ...DIALOG_DIRECTIVES, ...FORM_FIELD],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Responsável pela contestação</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        <app-field class="mb-3" label="Analista" hint="Analistas ativos que trabalham contestações e têm acesso ao credor, com as contestações abertas de cada um.">
          <select appInput formControlName="assignee" ngbAutofocus>
            <option value="">Sem responsável (fila)</option>
            @for (o of optionsList(); track o.userId) {
              <option [ngValue]="o.userId">{{ o.name }} · {{ o.openDisputes }} em aberto</option>
            }
          </select>
        </app-field>
        <app-field label="Motivo (opcional)">
          <textarea appInput formControlName="reason" rows="2" maxlength="500"></textarea>
        </app-field>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-link" appDialogClose>Cancelar</button>
        <button type="submit" class="btn btn-primary" [disabled]="busy()">Salvar</button>
      </div>
    </form>
  `,
})
export class AssignDisputeDialog {
  protected readonly data = inject<AssignDisputeData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<DisputeView>>(DialogRef);
  private readonly api = inject(DisputesApi);
  private readonly options = rxResource({ stream: () => this.api.assigneeOptions(this.data.dispute.id) });
  protected readonly optionsList = valueOr(this.options, []);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({ assignee: [this.data.dispute.assigneeId ?? ''], reason: [''] });

  protected save(): void {
    const v = this.form.getRawValue();
    this.busy.set(true);
    this.api
      .assign(this.data.dispute.id, v.assignee || null, v.reason.trim() || null)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({ next: (d) => this.ref.close(d), error: (e) => this.error.set(userMessage(toApiError(e))) });
  }
}

// =================================================================== resolver

export interface ResolveDisputeData {
  dispute: DisputeView;
  debtorName: string;
}

/**
 * Resolução com parecer e o ajuste sugerido no título. A sugestão não altera o título: fica pendente na contestação
 * até ser feita pelo fluxo existente (cancelamento, correção pela carga do credor ou pagamento comprovado) ou
 * dispensada com motivo.
 */
@Component({
  selector: 'app-resolve-dispute-dialog',
  imports: [ReactiveFormsModule, Icon, ...DIALOG_DIRECTIVES, ...FORM_FIELD],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Resolver contestação · {{ data.debtorName }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        <app-field class="mb-3" label="Resultado" error="Escolha o resultado.">
          <select appInput formControlName="outcome" required ngbAutofocus>
            <option value="" disabled>Selecione</option>
            @for (o of outcomes; track o.value) {
              <option [ngValue]="o.value">{{ o.label }}</option>
            }
          </select>
        </app-field>
        <app-field class="mb-3" label="Parecer" [hintEnd]="form.controls.note.value.length + ' / ' + noteMax" error="Registre o parecer.">
          <textarea appInput formControlName="note" rows="5" [maxlength]="noteMax" required></textarea>
        </app-field>
        @if (suggested()) {
          <app-field label="Ajuste no título" hint="Fica pendente na contestação até ser feito pelo fluxo próprio ou dispensado com motivo. Nada é alterado no título agora.">
            <select appInput formControlName="adjustment">
              @for (a of adjustments; track a.value) {
                <option [ngValue]="a.value">{{ a.label }}{{ a.value === suggested() ? ' (sugerido)' : '' }}</option>
              }
              <option value="NONE">Nenhum ajuste</option>
            </select>
          </app-field>
        }
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-link" appDialogClose>Cancelar</button>
        <button type="submit" class="btn btn-primary" [disabled]="busy()">Resolver</button>
      </div>
    </form>
  `,
})
export class ResolveDisputeDialog {
  protected readonly data = inject<ResolveDisputeData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<DisputeView>>(DialogRef);
  private readonly api = inject(DisputesApi);
  protected readonly outcomes = Object.entries(DISPUTE_OUTCOME_LABELS).map(([value, label]) => ({ value: value as DisputeOutcome, label }));
  protected readonly adjustments = Object.entries(ADJUSTMENT_ACTION_LABELS).map(([value, label]) => ({ value: value as AdjustmentAction, label }));
  protected readonly noteMax = NOTE_MAX;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    outcome: this.fb.control<DisputeOutcome | ''>('', [AppValidators.notBlank]),
    note: ['', [AppValidators.notBlank, Validators.maxLength(NOTE_MAX)]],
    adjustment: this.fb.control<AdjustmentAction | 'NONE'>('NONE'),
  });
  private readonly outcome = toSignal(this.form.controls.outcome.valueChanges, { initialValue: this.form.controls.outcome.value });
  protected readonly suggested = computed(() => suggestedAdjustment(this.data.dispute.type, this.outcome()));

  constructor() {
    this.form.controls.outcome.valueChanges.subscribe((o) => this.form.controls.adjustment.setValue(suggestedAdjustment(this.data.dispute.type, o) ?? 'NONE'));
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.busy.set(true);
    this.api
      .resolve(this.data.dispute.id, v.outcome as DisputeOutcome, v.note.trim(), this.suggested() ? v.adjustment : 'NONE')
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({ next: (d) => this.ref.close(d), error: (e) => this.error.set(userMessage(toApiError(e))) });
  }
}

// =================================================================== pedido ao credor

export interface CreditorRequestData {
  disputeId: string;
  creditorName: string;
}

/**
 * Pedido formal ao credor: texto do modelo do tipo (editável), destinatários do cadastro do credor, títulos citados,
 * evidências compartilhadas por link seguro com validade (não vão anexadas ao e-mail) e prazo pedido ao credor.
 */
@Component({
  selector: 'app-creditor-request-dialog',
  imports: [ReactiveFormsModule, NgbInputDatepicker, Amount, AppDatePipe, Icon, ...DIALOG_DIRECTIVES, ...FORM_FIELD],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Solicitar ao credor · {{ data.creditorName }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    @if (draft.error()) {
      <div class="modal-body">
        <div class="alert alert-danger d-flex gap-2" role="alert"><app-icon name="exclamation-circle" /><span>Não foi possível montar o pedido.</span></div>
      </div>
    } @else if (!d()) {
      <div class="modal-body"><div class="spinner-border spinner-border-sm" role="status" aria-label="Carregando o pedido"></div></div>
    } @else {
      @let dr = d()!;
      <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="send(dr)" novalidate>
        <div class="modal-body">
          @if (error()) {
            <div class="alert alert-danger d-flex gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
          }
          @if (!dr.to.length) {
            <div class="alert alert-warning d-flex gap-2" role="alert">
              <app-icon name="exclamation-triangle" />
              <span>O credor não tem contato de contestação cadastrado. Cadastre o contato principal no credor antes de enviar.</span>
            </div>
          }
          <dl class="app-definition-list cr-recipients">
            <dt>Para</dt>
            <dd>{{ dr.to.join(', ') || '—' }}{{ dr.recipientsFromPortfolio ? ' (contatos da carteira)' : '' }}</dd>
            <dt>Cópia</dt>
            <dd>{{ dr.cc.join(', ') || '—' }}</dd>
            <dt>Respostas para</dt>
            <dd>{{ dr.replyTo || 'sem e-mail do responsável' }}</dd>
          </dl>
          <app-field class="mb-3" label="Assunto">
            <input appInput formControlName="subject" maxlength="200" required />
          </app-field>
          <app-field class="mb-3" label="Texto do pedido" [hintEnd]="form.controls.body.value.length + ' / ' + textMax"
                     hint="O link das evidências e a validade são preenchidos pelo sistema no envio." [error]="placeholderError()">
            <textarea appInput formControlName="body" rows="12" [maxlength]="textMax" required></textarea>
          </app-field>
          <div class="row g-3 mb-3">
            <app-field class="col-sm-6" label="Prazo pedido ao credor">
              <div class="input-group">
                <input appInput ngbDatepicker #due="ngbDatepicker" formControlName="requestedDueDate" placeholder="dd/mm/aaaa" [minDate]="tomorrow" />
                <button type="button" class="btn btn-outline-secondary" (click)="due.toggle()" aria-label="Abrir calendário"><app-icon name="calendar-event" /></button>
              </div>
            </app-field>
            <div class="col-sm-6 d-flex align-items-center">
              <div class="form-check form-switch">
                <input class="form-check-input" type="checkbox" role="switch" id="cr-copy" formControlName="copyAssignee" />
                <label class="form-check-label" for="cr-copy">Cópia para o responsável</label>
              </div>
            </div>
          </div>
          @if (dr.titles.length > 1) {
            <fieldset class="cr-box">
              <legend class="form-label">Títulos citados <span class="app-muted">(nenhum marcado = todos da contestação)</span></legend>
              @for (t of dr.titles; track t.titleId) {
                <div class="form-check">
                  <input class="form-check-input" type="checkbox" [id]="'cr-t-' + t.titleId" #tb [checked]="titleIds().has(t.titleId)" (change)="toggleTitle(t.titleId, tb.checked)" />
                  <label class="form-check-label" [for]="'cr-t-' + t.titleId">
                    <span class="text-nowrap">{{ t.externalId }}{{ t.installmentNumber ? '/' + t.installmentNumber : '' }}</span> · venc. {{ t.dueDate | appDate: 'date' }} ·
                    <app-amount [value]="t.principalAmount" />
                  </label>
                </div>
              }
            </fieldset>
          }
          <fieldset class="cr-box">
            <legend class="form-label">Evidências compartilhadas</legend>
            <p class="app-muted mb-2">
              Vão por link de acesso restrito, válido até {{ dr.linkExpiresAt | appDate: 'date' }}, e não anexadas ao e-mail. Compartilhe só o necessário para a análise (dados pessoais).
            </p>
            @for (e of dr.evidences; track e.id) {
              <div class="form-check">
                <input class="form-check-input" type="checkbox" [id]="'cr-e-' + e.id" #eb [checked]="evidenceIds().has(e.id)" (change)="toggleEvidence(e.id, eb.checked)" />
                <label class="form-check-label" [for]="'cr-e-' + e.id">{{ e.fileName }}</label>
              </div>
            } @empty {
              <p class="app-muted mb-0">Nenhuma evidência anexada à contestação.</p>
            }
          </fieldset>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-link" appDialogClose>Cancelar</button>
          <button type="submit" class="btn btn-primary" [disabled]="busy() || !dr.to.length">
            <app-icon name="send" />
            Enviar ao credor
          </button>
        </div>
      </form>
    }
  `,
  styles: `
    .cr-box {
      margin: 0 0 12px;
      padding: 8px 12px;
      border: var(--bs-border-width) solid var(--bs-border-color);
      border-radius: var(--bs-border-radius);
      legend {
        float: none;
        width: auto;
        margin: 0;
        padding: 0 4px;
        font-size: var(--bs-body-font-size);
      }
    }
    .cr-recipients {
      margin-bottom: 12px;
    }
  `,
})
export class CreditorRequestDialog {
  protected readonly data = inject<CreditorRequestData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<CreditorRequestView>>(DialogRef);
  private readonly api = inject(DisputesApi);
  private readonly notifications = inject(NotificationService);
  protected readonly textMax = TEXT_MAX;
  protected readonly tomorrow = toDateStruct(addDays(zonedNow().date, 1));
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly titleIds = signal<ReadonlySet<string>>(new Set());
  protected readonly evidenceIds = signal<ReadonlySet<string>>(new Set());

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    subject: ['', [AppValidators.notBlank, Validators.maxLength(200)]],
    body: ['', [AppValidators.notBlank, Validators.maxLength(TEXT_MAX)]],
    requestedDueDate: this.fb.control<string | null>(null),
    copyAssignee: [true],
  });

  protected readonly draft = rxResource<CreditorRequestDraft, string>({
    params: () => this.data.disputeId,
    stream: ({ params }) => this.api.creditorRequestDraft(params),
  });
  protected readonly d = computed(() => {
    const value = this.draft.hasValue() ? this.draft.value() : undefined;
    if (value && !this.form.dirty && !this.form.controls.subject.value) {
      this.form.patchValue({ subject: value.subject, body: value.body, requestedDueDate: value.requestedDueDate, copyAssignee: value.copyAssignee });
    }
    return value;
  });
  protected readonly placeholderError = signal<string>('');

  protected toggleTitle(id: string, on: boolean): void {
    this.titleIds.update((s) => toggled(s, id, on));
  }

  protected toggleEvidence(id: string, on: boolean): void {
    this.evidenceIds.update((s) => toggled(s, id, on));
  }

  protected send(draft: CreditorRequestDraft): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const left = unresolvedPlaceholders(v.subject + ' ' + v.body);
    if (left.length) {
      this.placeholderError.set(`Preencha no texto: ${left.map((x) => `{{${x}}}`).join(', ')}`);
      return;
    }
    this.placeholderError.set('');
    this.busy.set(true);
    this.api
      .requestCreditor(this.data.disputeId, {
        subject: v.subject.trim(),
        body: v.body.trim(),
        requestedDueDate: v.requestedDueDate ?? draft.requestedDueDate,
        titleIds: [...this.titleIds()],
        evidenceIds: [...this.evidenceIds()],
        copyAssignee: v.copyAssignee,
      })
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (r) => {
          this.notifications.success('Pedido enviado à fila de e-mail do credor.');
          this.ref.close(r);
        },
        error: (e) => this.error.set(userMessage(toApiError(e))),
      });
  }
}

// =================================================================== resposta do credor

export interface CreditorResponseData {
  disputeId: string;
  requests: CreditorRequestView[];
}

/** Resposta do credor recebida por e-mail ou outro canal, com os documentos enviados por ele. */
@Component({
  selector: 'app-creditor-response-dialog',
  imports: [ReactiveFormsModule, AppDatePipe, Icon, ...DIALOG_DIRECTIVES, ...FORM_FIELD],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Registrar resposta do credor</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        <app-field class="mb-3" label="Posição do credor">
          <select appInput formControlName="position" required ngbAutofocus>
            <option value="" disabled>Selecione</option>
            @for (p of positions; track p.value) {
              <option [ngValue]="p.value">{{ p.label }}</option>
            }
          </select>
        </app-field>
        @if (data.requests.length) {
          <app-field class="mb-3" label="Pedido respondido">
            <select appInput formControlName="requestId">
              <option value="">O mais recente aguardando</option>
              @for (r of data.requests; track r.id) {
                <option [ngValue]="r.id">Pedido {{ r.sequenceNumber }} · {{ r.createdAt | appDate: 'date' }}</option>
              }
            </select>
          </app-field>
        }
        <app-field class="mb-3" label="Resumo da resposta" [hintEnd]="form.controls.note.value.length + ' / ' + noteMax" error="Resuma o que o credor respondeu.">
          <textarea appInput formControlName="note" rows="5" [maxlength]="noteMax" required></textarea>
        </app-field>
        <input #file type="file" multiple [accept]="accept" (change)="pick(file.files); file.value = ''" hidden />
        <button type="button" class="btn btn-outline-primary" (click)="file.click()">
          <app-icon name="paperclip" />
          Documentos do credor
        </button>
        @for (f of files(); track f.name) {
          <span class="badge rounded-pill bg-secondary-subtle text-secondary-emphasis border border-secondary-subtle ms-2">{{ f.name }}</span>
        }
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-link" appDialogClose>Cancelar</button>
        <button type="submit" class="btn btn-primary" [disabled]="busy()">Registrar resposta</button>
      </div>
    </form>
  `,
})
export class CreditorResponseDialog {
  protected readonly data = inject<CreditorResponseData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<CreditorResponseView>>(DialogRef);
  private readonly api = inject(DisputesApi);
  private readonly notifications = inject(NotificationService);
  protected readonly positions = Object.entries(CREDITOR_POSITION_LABELS).map(([value, label]) => ({ value: value as CreditorPosition, label }));
  protected readonly accept = EVIDENCE_ACCEPT;
  protected readonly noteMax = NOTE_MAX;
  protected readonly files = signal<readonly File[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    position: this.fb.control<CreditorPosition | ''>('', [AppValidators.notBlank]),
    requestId: [''],
    note: ['', [AppValidators.notBlank, Validators.maxLength(NOTE_MAX)]],
  });

  protected pick(list: FileList | null): void {
    for (const f of Array.from(list ?? [])) {
      const problem = evidenceProblem(f);
      if (problem) {
        this.notifications.error(problem);
        continue;
      }
      this.files.update((all) => [...all.filter((x) => x.name !== f.name), f]);
    }
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.busy.set(true);
    this.api
      .registerCreditorResponse(this.data.disputeId, { position: v.position as CreditorPosition, note: v.note.trim(), requestId: v.requestId || null, receivedAt: null }, [...this.files()])
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (r) => {
          this.notifications.success('Resposta do credor registrada.');
          this.ref.close(r);
        },
        error: (e) => this.error.set(userMessage(toApiError(e))),
      });
  }
}

// =================================================================== resposta ao devedor

export interface DebtorNoticeData {
  disputeId: string;
  debtorName: string;
}

/**
 * Resposta fundamentada ao devedor (CDC) pelo modelo do resultado, editável. E-mail ou WhatsApp passam pelo porteiro de
 * contato como comunicação transacional: a suspensão da cobrança não impede, mas descadastro, restrições e horário
 * permitido continuam valendo. Sem canal permitido, registre a entrega por outro meio.
 */
@Component({
  selector: 'app-debtor-notice-dialog',
  imports: [ReactiveFormsModule, AppDatePipe, Icon, ...DIALOG_DIRECTIVES, ...FORM_FIELD],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Responder ao devedor · {{ data.debtorName }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    @if (draft.error()) {
      <div class="modal-body">
        <div class="alert alert-danger d-flex gap-2" role="alert"><app-icon name="exclamation-circle" /><span>Não foi possível montar a resposta.</span></div>
      </div>
    } @else if (!d()) {
      <div class="modal-body"><div class="spinner-border spinner-border-sm" role="status" aria-label="Carregando a resposta"></div></div>
    } @else {
      @let dr = d()!;
      <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="send()" novalidate>
        <div class="modal-body">
          @if (error()) {
            <div class="alert alert-danger d-flex gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
          }
          <fieldset class="mb-3" data-tour="notice-channels">
            <legend class="form-label">Canal</legend>
            @for (c of dr.channels; track c.channel) {
              <div class="form-check">
                <input class="form-check-input" type="radio" formControlName="channel" [value]="c.channel" [id]="'dn-' + c.channel" [attr.disabled]="c.allowed ? null : true" />
                <label class="form-check-label" [for]="'dn-' + c.channel">
                  {{ channelLabels[c.channel] }}{{ c.maskedContact ? ' · ' + c.maskedContact : '' }}
                  @if (!c.allowed) {
                    <span class="app-muted">— não permitido agora: {{ reasons(c.reasons) }}{{ c.nextAllowedAt ? '; liberado em ' : '' }}{{ c.nextAllowedAt ? (c.nextAllowedAt | appDate) : '' }}</span>
                  }
                </label>
              </div>
            }
            <div class="form-check">
              <input class="form-check-input" type="radio" formControlName="channel" value="OTHER" id="dn-OTHER" />
              <label class="form-check-label" for="dn-OTHER">{{ channelLabels.OTHER }}</label>
            </div>
          </fieldset>
          @if (form.controls.channel.value === 'EMAIL') {
            <app-field class="mb-3" label="Assunto">
              <input appInput formControlName="subject" maxlength="200" />
            </app-field>
          }
          <app-field
            label="{{ form.controls.channel.value === 'OTHER' ? 'Como e quando a resposta foi entregue (e o texto entregue)' : 'Texto da resposta' }}"
            [hintEnd]="form.controls.body.value.length + ' / ' + textMax"
          >
            <textarea appInput formControlName="body" rows="12" [maxlength]="textMax" required></textarea>
          </app-field>
        </div>
        <div class="modal-footer">
          <button type="button" class="btn btn-link" appDialogClose>Cancelar</button>
          <button type="submit" class="btn btn-primary" [disabled]="busy() || !form.controls.channel.value">
            {{ form.controls.channel.value === 'OTHER' ? 'Registrar entrega' : 'Enviar resposta' }}
          </button>
        </div>
      </form>
    }
  `,
})
export class DebtorNoticeDialog {
  protected readonly data = inject<DebtorNoticeData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<DebtorNoticeView>>(DialogRef);
  private readonly api = inject(DisputesApi);
  private readonly notifications = inject(NotificationService);
  protected readonly channelLabels = NOTICE_CHANNEL_LABELS;
  protected readonly textMax = TEXT_MAX;
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    channel: this.fb.control<NoticeChannel | ''>(''),
    subject: [''],
    body: ['', [AppValidators.notBlank, Validators.maxLength(TEXT_MAX)]],
  });

  protected readonly draft = rxResource<DebtorNoticeDraft, string>({
    params: () => this.data.disputeId,
    stream: ({ params }) => this.api.debtorNoticeDraft(params),
  });
  protected readonly d = computed(() => {
    const value = this.draft.hasValue() ? this.draft.value() : undefined;
    if (value && !this.form.dirty && !this.form.controls.body.value) {
      const first = value.channels.find((c) => c.allowed)?.channel ?? 'OTHER';
      this.form.patchValue({ channel: first, subject: value.subject, body: value.body });
    }
    return value;
  });

  protected reasons(list: string[]): string {
    return list.map((r) => NOTICE_REASON_LABELS[r] ?? r).join(', ');
  }

  protected send(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const channel = v.channel as NoticeChannel;
    const contact = this.d()?.channels.find((c) => c.channel === channel)?.contactId ?? null;
    this.busy.set(true);
    this.api
      .notifyDebtor(this.data.disputeId, { channel, contactId: channel === 'OTHER' ? null : contact, subject: channel === 'EMAIL' ? v.subject.trim() : null, body: v.body.trim() })
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (n) => {
          if (n.status === 'BLOCKED') {
            this.notifications.error(`Resposta bloqueada pelo porteiro de contato: ${this.reasons((n.failureDetail ?? '').split(','))}.`);
          } else {
            this.notifications.success(channel === 'OTHER' ? 'Entrega da resposta registrada.' : 'Resposta enviada à fila do canal.');
          }
          this.ref.close(n);
        },
        error: (e) => this.error.set(userMessage(toApiError(e))),
      });
  }
}

function toggled(set: ReadonlySet<string>, id: string, on: boolean): ReadonlySet<string> {
  const next = new Set(set);
  if (on) {
    next.add(id);
  } else {
    next.delete(id);
  }
  return next;
}
