import { Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { Observable, finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Icon } from '../../../shared/components/icon/icon';
import { valueOr } from '../../../shared/data/resource-value';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppValidators } from '../../../shared/validation/validators';
import {
  ComplaintOutcome,
  ComplaintSubject,
  ComplaintView,
  ComplaintsApi,
  OUTCOME_LABELS,
  SUBJECT_LABELS,
} from './complaint.data';

export interface ComplaintDialogData {
  complaint: ComplaintView;
}

const RESPONSE_MAX = 8000;

/** Base dos diálogos de tratamento: chamada, erro e fechamento com a reclamação atualizada. */
abstract class ComplaintDialogBase {
  protected readonly data = inject<ComplaintDialogData>(DIALOG_DATA);
  protected readonly api = inject(ComplaintsApi);
  private readonly ref = inject<DialogRef<ComplaintView>>(DialogRef);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly fb = inject(FormBuilder).nonNullable;

  protected run(call: Observable<ComplaintView>): void {
    this.busy.set(true);
    this.error.set(null);
    call.pipe(finalize(() => this.busy.set(false))).subscribe({
      next: (c) => this.ref.close(c),
      error: (e) => this.error.set(userMessage(toApiError(e))),
    });
  }
}

const ERROR_BLOCK = `
  @if (error()) {
    <div class="alert alert-danger d-flex gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
  }`;

// =================================================================== responder

/** Resposta enviada ao canal (texto que foi protocolado no Procon, consumidor.gov.br, ouvidoria…). */
@Component({
  selector: 'app-answer-complaint-dialog',
  imports: [ReactiveFormsModule, Icon, ...DIALOG_DIRECTIVES, ...FORM_FIELD],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Registrar resposta ao canal</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        ${ERROR_BLOCK}
        <p class="app-dialog-message">Registre o texto que foi enviado ao canal. Respondida até o prazo conta como "no prazo" no indicador.</p>
        <app-field class="mb-3" label="Resposta" [hint]="form.controls.response.value.length + '/' + max">
          <textarea appInput formControlName="response" rows="6" [attr.maxlength]="max" ngbAutofocus></textarea>
        </app-field>
        <app-field label="Enviada em (opcional)" hint="Em branco = agora. Use quando a resposta foi protocolada antes do registro aqui.">
          <input appInput type="datetime-local" formControlName="answeredAt" />
        </app-field>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-link" appDialogClose>Cancelar</button>
        <button type="submit" class="btn btn-primary" [disabled]="busy()">Registrar resposta</button>
      </div>
    </form>
  `,
})
export class AnswerComplaintDialog extends ComplaintDialogBase {
  protected readonly max = RESPONSE_MAX;
  protected readonly form = this.fb.group({
    response: ['', [AppValidators.notBlank, Validators.maxLength(RESPONSE_MAX)]],
    answeredAt: [''],
  });

  protected save(): void {
    if (this.form.invalid || this.busy()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const at = v.answeredAt ? new Date(v.answeredAt).toISOString() : null;
    this.run(this.api.answer(this.data.complaint.id, v.response.trim(), at));
  }
}

// =================================================================== encerrar

@Component({
  selector: 'app-close-complaint-dialog',
  imports: [ReactiveFormsModule, Icon, ...DIALOG_DIRECTIVES, ...FORM_FIELD],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Encerrar reclamação</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        ${ERROR_BLOCK}
        <app-field label="Resultado">
          <select appInput formControlName="outcome" ngbAutofocus>
            <option value="" disabled>Selecione</option>
            @for (o of outcomes; track o.value) {
              <option [ngValue]="o.value">{{ o.label }}</option>
            }
          </select>
        </app-field>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-link" appDialogClose>Cancelar</button>
        <button type="submit" class="btn btn-primary" [disabled]="busy()">Encerrar</button>
      </div>
    </form>
  `,
})
export class CloseComplaintDialog extends ComplaintDialogBase {
  protected readonly outcomes = Object.entries(OUTCOME_LABELS).map(([value, label]) => ({ value: value as ComplaintOutcome, label }));
  protected readonly form = this.fb.group({ outcome: this.fb.control<ComplaintOutcome | ''>('', [AppValidators.notBlank]) });

  protected save(): void {
    const outcome = this.form.getRawValue().outcome;
    if (!outcome || this.busy()) {
      this.form.markAllAsTouched();
      return;
    }
    this.run(this.api.close(this.data.complaint.id, outcome));
  }
}

// =================================================================== atribuir

@Component({
  selector: 'app-assign-complaint-dialog',
  imports: [ReactiveFormsModule, Icon, ...DIALOG_DIRECTIVES, ...FORM_FIELD],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Responsável pela reclamação</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        ${ERROR_BLOCK}
        <app-field label="Responsável" hint="Pessoas ativas que tratam reclamações e têm acesso ao credor, com as reclamações em aberto de cada uma.">
          <select appInput formControlName="assignee" ngbAutofocus>
            <option value="">Sem responsável</option>
            @for (o of optionsList(); track o.userId) {
              <option [ngValue]="o.userId">{{ o.name }} · {{ o.openComplaints }} em aberto</option>
            }
          </select>
        </app-field>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-link" appDialogClose>Cancelar</button>
        <button type="submit" class="btn btn-primary" [disabled]="busy()">Salvar</button>
      </div>
    </form>
  `,
})
export class AssignComplaintDialog extends ComplaintDialogBase {
  private readonly options = rxResource({ stream: () => this.api.assigneeOptions(this.data.complaint.id) });
  protected readonly optionsList = valueOr(this.options, []);
  protected readonly form = this.fb.group({ assignee: [this.data.complaint.assigneeId ?? ''] });

  protected save(): void {
    this.run(this.api.assign(this.data.complaint.id, this.form.getRawValue().assignee || null));
  }
}

// =================================================================== prazo

/** Novo prazo informado pelo canal (prorrogação concedida), sempre com o motivo (vai para a auditoria). */
@Component({
  selector: 'app-due-date-complaint-dialog',
  imports: [ReactiveFormsModule, Icon, NgbInputDatepicker, ...DIALOG_DIRECTIVES, ...FORM_FIELD],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Alterar prazo de resposta</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        ${ERROR_BLOCK}
        <app-field class="mb-3" label="Novo prazo">
          <div class="input-group">
            <input appInput ngbDatepicker #due="ngbDatepicker" placeholder="dd/mm/aaaa" formControlName="dueDate" ngbAutofocus />
            <button type="button" class="btn btn-outline-secondary" (click)="due.toggle()" aria-label="Abrir calendário">
              <app-icon name="calendar-event" />
            </button>
          </div>
        </app-field>
        <app-field label="Motivo" hint="Ex.: prorrogação concedida pelo Procon na notificação 2.">
          <textarea appInput formControlName="reason" rows="2" maxlength="300"></textarea>
        </app-field>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-link" appDialogClose>Cancelar</button>
        <button type="submit" class="btn btn-primary" [disabled]="busy()">Alterar prazo</button>
      </div>
    </form>
  `,
})
export class DueDateComplaintDialog extends ComplaintDialogBase {
  protected readonly form = this.fb.group({
    /** `LocalDate` ISO (yyyy-MM-dd) do `ngbDatepicker`. */
    dueDate: this.fb.control<string | null>(this.data.complaint.dueDate, [Validators.required]),
    reason: ['', [AppValidators.notBlank, Validators.maxLength(300)]],
  });

  protected save(): void {
    const v = this.form.getRawValue();
    if (this.form.invalid || !v.dueDate || this.busy()) {
      this.form.markAllAsTouched();
      return;
    }
    this.run(this.api.changeDueDate(this.data.complaint.id, v.dueDate, v.reason.trim()));
  }
}

// =================================================================== corrigir

/** Corrige o assunto e o protocolo do canal; os vínculos de devedor e caso são mantidos. */
@Component({
  selector: 'app-reclassify-complaint-dialog',
  imports: [ReactiveFormsModule, Icon, ...DIALOG_DIRECTIVES, ...FORM_FIELD],
  host: { class: 'd-flex flex-column overflow-hidden' },
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Corrigir assunto e protocolo</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        ${ERROR_BLOCK}
        <app-field class="mb-3" label="Assunto">
          <select appInput formControlName="subject" ngbAutofocus>
            @for (s of subjects; track s.value) {
              <option [ngValue]="s.value">{{ s.label }}</option>
            }
          </select>
        </app-field>
        <app-field label="Protocolo do canal">
          <input appInput formControlName="externalProtocol" maxlength="60" />
        </app-field>
      </div>
      <div class="modal-footer">
        <button type="button" class="btn btn-link" appDialogClose>Cancelar</button>
        <button type="submit" class="btn btn-primary" [disabled]="busy()">Salvar</button>
      </div>
    </form>
  `,
})
export class ReclassifyComplaintDialog extends ComplaintDialogBase {
  protected readonly subjects = Object.entries(SUBJECT_LABELS).map(([value, label]) => ({ value: value as ComplaintSubject, label }));
  protected readonly form = this.fb.group({
    subject: this.fb.control<ComplaintSubject>(this.data.complaint.subject),
    externalProtocol: [this.data.complaint.externalProtocol ?? '', [Validators.maxLength(60)]],
  });

  protected save(): void {
    const v = this.form.getRawValue();
    const c = this.data.complaint;
    this.run(this.api.reclassify(c.id, { debtorId: c.debtorId, caseId: c.caseId, externalProtocol: v.externalProtocol.trim() || null, subject: v.subject }));
  }
}
