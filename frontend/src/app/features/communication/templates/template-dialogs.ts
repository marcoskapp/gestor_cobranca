import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbInputDatepicker, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { finalize, map, startWith } from 'rxjs';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { fromLocalDate, toLocalDate } from '../../../shared/models/dates';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { TemplatesApi } from '../data/communication.api';
import { CHANNEL_LABELS, PROVIDER_CATEGORY_LABELS, PURPOSE_LABELS, VARIABLE_EXAMPLES, VARIABLE_TYPE_LABELS } from '../data/communication.labels';
import { ApprovalRequest, ChannelType, ProviderCategory, TemplatePurpose, TemplateView, VariableType } from '../data/communication.models';
import { OTP_MAX_LENGTH, categoryProblems, variableProblems } from '../data/communication.rules';

export interface TemplateDialogData {
  /** Rascunho a editar; ou base de uma nova versão (`asNewVersion`). */
  template: TemplateView | null;
  asNewVersion?: boolean;
}

/**
 * Editor de template: código, canal e credor na criação; nome, finalidade, assunto (e-mail), corpo
 * com `{{variável}}` e variáveis tipadas (sem tipo para documento, telefone, e-mail ou endereço).
 * Publicado não se edita: cria-se a próxima versão com o mesmo código.
 */
@Component({
  selector: 'app-template-dialog',
  imports: [ReactiveFormsModule, NgbTooltip, ...DIALOG_DIRECTIVES, ...FORM_FIELD, EntityPicker, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ title }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <div class="row g-3">
          @if (creating) {
            <app-field class="col-sm-6" label="Código" hint="Maiúsculas, números e _ (ex.: LEMBRETE_VENCIMENTO)">
              <input appInput formControlName="code" maxlength="60" required [readonly]="!!data.asNewVersion" />
            </app-field>
            <app-field class="col-sm-6" label="Canal">
              <select appInput formControlName="channel" required>
                @for (c of channelOptions; track c.value) {
                  <option [ngValue]="c.value" [disabled]="!!data.asNewVersion && c.value !== data.template?.channel">{{ c.label }}</option>
                }
              </select>
            </app-field>
            @if (!data.asNewVersion) {
              <div class="col-12">
                <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor (vazio = template da assessoria)" [single]="true" />
              </div>
            }
          }
          <app-field class="col-sm-6" label="Nome">
            <input appInput formControlName="name" maxlength="120" required />
          </app-field>
          <app-field class="col-sm-6" label="Finalidade">
            <select appInput formControlName="purpose" required>
              @for (p of purposes; track p.value) {
                <option [ngValue]="p.value">{{ p.label }}</option>
              }
            </select>
          </app-field>
          @if (channel() === 'EMAIL') {
            <app-field class="col-12" label="Assunto">
              <input appInput formControlName="subject" maxlength="300" required />
            </app-field>
          }
          <app-field class="col-12" label="Texto" [hint]="'Use ' + example + ' para inserir valores. Não inclua CPF, telefone, e-mail ou endereço.'">
            <textarea appInput rows="5" formControlName="body" maxlength="8000" required></textarea>
          </app-field>
        </div>

        <h3 class="app-section-title mt-3">Variáveis</h3>
        <div class="d-grid gap-2" formArrayName="variables">
          @for (v of variables.controls; track v; let i = $index) {
            <div class="row g-2 align-items-start" [formGroupName]="i">
              <app-field class="col-6 col-sm-4" label="Nome">
                <input appInput formControlName="name" maxlength="40" required />
              </app-field>
              <app-field class="col-6 col-sm-4" label="Tipo">
                <select appInput formControlName="type">
                  @for (t of types; track t.value) {
                    <option [ngValue]="t.value">{{ t.label }}</option>
                  }
                </select>
              </app-field>
              <div class="col-auto app-control-offset">
                <div class="form-check mb-0">
                  <input class="form-check-input" type="checkbox" formControlName="required" [id]="'td-var-required-' + i" />
                  <label class="form-check-label" [for]="'td-var-required-' + i">Obrigatória</label>
                </div>
              </div>
              <div class="col-auto app-control-offset">
                <button class="btn btn-link" type="button" (click)="variables.removeAt(i)" aria-label="Remover variável" ngbTooltip="Remover">
                  <app-icon name="trash3" />
                </button>
              </div>
            </div>
          }
        </div>
        <button class="btn btn-link d-inline-flex align-items-center gap-2 px-0 mt-1" type="button" (click)="addVariable()"><app-icon name="plus-lg" />Adicionar variável</button>
        @if (channel() === 'WHATSAPP') {
          <div class="row g-3 mt-0">
            <app-field class="col-sm-6" label="Categoria na Meta" hint="Utilidade para avisos e lembretes; autenticação só para o código de acesso.">
              <select appInput formControlName="providerCategory">
                @for (c of categories; track c.value) {
                  <option [ngValue]="c.value">{{ c.label }}</option>
                }
              </select>
            </app-field>
            <app-field class="col-sm-6" label="Nome do template no WhatsApp (opcional)" hint="No WhatsApp, os parâmetros seguem a ordem das variáveis declaradas.">
              <input appInput formControlName="providerTemplateName" maxlength="120" />
            </app-field>
          </div>
          @if (category() === 'AUTHENTICATION') {
            <p class="alert alert-secondary d-flex gap-2 mt-3 mb-0" role="note">
              <app-icon name="info-circle" />
              <span>Autenticação: a Meta usa texto fixo e um botão "copiar código". Declare só a variável do código (tipo código, até {{ otpMax }} caracteres); a validade é configurada no template na Meta.</span>
            </p>
          }
        }
        @for (p of problems(); track p) {
          <div class="invalid-feedback d-block" role="alert">{{ p }}</div>
        }
        @if (channel() === 'WHATSAPP') {
          <p class="alert alert-secondary d-flex gap-2 mt-3 mb-0" role="note">
            <app-icon name="info-circle" />
            <span>Rascunho de WhatsApp fica aguardando a aprovação do provedor; editar volta a aguardar. Só o aprovado pode ser publicado.</span>
          </p>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Salvar rascunho</button>
      </div>
    </form>
  `,
})
export class TemplateDialog {
  protected readonly data = inject<TemplateDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<TemplateView>>(DialogRef);
  private readonly api = inject(TemplatesApi);
  protected readonly creditors = inject(CreditorLookup);
  protected readonly creating = !this.data.template || !!this.data.asNewVersion;
  protected readonly title = this.data.asNewVersion ? `Nova versão de ${this.data.template?.code}` : this.data.template ? 'Editar rascunho' : 'Novo template';
  protected readonly channelOptions = (Object.keys(CHANNEL_LABELS) as ChannelType[]).map((value) => ({ value, label: CHANNEL_LABELS[value] }));
  protected readonly purposes = (Object.keys(PURPOSE_LABELS) as TemplatePurpose[]).map((value) => ({ value, label: PURPOSE_LABELS[value] }));
  protected readonly types = (Object.keys(VARIABLE_TYPE_LABELS) as VariableType[]).map((value) => ({ value, label: VARIABLE_TYPE_LABELS[value] }));
  protected readonly saving = signal(false);
  protected readonly example = '{{primeiro_nome}}';

  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly t = this.data.template;
  protected readonly form = this.fb.group({
    code: [this.t?.code ?? '', [Validators.required, Validators.pattern(/^[A-Z0-9_]{2,60}$/)]],
    channel: this.fb.control<ChannelType>(this.t?.channel ?? 'WHATSAPP'),
    creditor: this.fb.control<string[]>(this.t?.creditorId ? [this.t.creditorId] : []),
    name: [this.t?.name ?? '', [Validators.required, Validators.maxLength(120)]],
    purpose: this.fb.control<TemplatePurpose>(this.t?.purpose ?? 'COLLECTION'),
    subject: [this.t?.subject ?? '', Validators.maxLength(300)],
    body: [this.t?.body ?? '', [Validators.required, Validators.maxLength(8000)]],
    variables: this.fb.array(
      (this.t?.variables ?? []).map((v) => this.variableGroup(v.name, v.type, v.required)),
    ),
    providerTemplateName: [this.t?.providerTemplateName ?? '', Validators.maxLength(120)],
    providerCategory: this.fb.control<ProviderCategory>(this.t?.providerCategory ?? 'UTILITY'),
  });
  protected readonly channel = toSignal(this.form.controls.channel.valueChanges, { initialValue: this.form.controls.channel.value });
  protected readonly category = toSignal(this.form.controls.providerCategory.valueChanges, { initialValue: this.form.controls.providerCategory.value });
  protected readonly categories = (Object.keys(PROVIDER_CATEGORY_LABELS) as ProviderCategory[]).map((value) => ({ value, label: PROVIDER_CATEGORY_LABELS[value] }));
  protected readonly otpMax = OTP_MAX_LENGTH;
  private readonly value = toSignal(this.form.valueChanges.pipe(startWith(null), map(() => this.form.getRawValue())), { initialValue: this.form.getRawValue() });
  protected readonly problems = computed(() => {
    const v = this.value();
    return v.body.trim()
      ? [...variableProblems(v.body, v.channel === 'EMAIL' ? v.subject : null, v.variables), ...categoryProblems(v.channel, v.providerCategory, v.variables)]
      : [];
  });

  protected get variables(): FormArray {
    return this.form.controls.variables;
  }

  private variableGroup(name = '', type: VariableType = 'TEXT', required = true) {
    return this.fb.group({ name: [name, [Validators.required, Validators.pattern(/^[A-Za-z0-9_]{1,40}$/)]], type: this.fb.control<VariableType>(type), required: [required] });
  }

  protected addVariable(): void {
    this.form.controls.variables.push(this.variableGroup());
  }

  protected save(): void {
    if (this.form.invalid || this.problems().length || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const content = {
      name: v.name.trim(),
      purpose: v.purpose,
      subject: v.channel === 'EMAIL' ? v.subject.trim() || null : null,
      body: v.body,
      variables: v.variables.map((x) => ({ name: x.name.trim(), type: x.type, required: x.required, maxLength: null })),
      language: 'pt_BR',
      providerTemplateName: v.channel === 'WHATSAPP' ? v.providerTemplateName.trim() || null : null,
      providerCategory: v.channel === 'WHATSAPP' ? v.providerCategory : null,
    };
    const call =
      this.t && !this.data.asNewVersion
        ? this.api.update(this.t.id, content)
        : this.api.create({
            creditorId: this.data.asNewVersion ? (this.t?.creditorId ?? null) : (v.creditor[0] ?? null),
            code: v.code.trim().toUpperCase(),
            channel: v.channel,
            content,
          });
    this.saving.set(true);
    call.pipe(finalize(() => this.saving.set(false))).subscribe((saved) => this.ref.close(saved));
  }
}

/** Registro da decisão do WhatsApp sobre o template (submetido, aprovado ou recusado). */
@Component({
  selector: 'app-approval-dialog',
  imports: [ReactiveFormsModule, NgbInputDatepicker, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Aprovação do WhatsApp</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body d-grid gap-3">
        <p class="app-dialog-message mb-0">{{ data.template!.name }} (v{{ data.template!.templateVersion }}). Registre o que o provedor respondeu no gerenciador do WhatsApp.</p>
        <app-field label="Decisão">
          <select appInput formControlName="decision" required>
            <option value="PENDING">Submetido, aguardando</option>
            <option value="APPROVED">Aprovado</option>
            <option value="REJECTED">Recusado</option>
          </select>
        </app-field>
        @if (form.controls.decision.value === 'APPROVED') {
          <app-field label="Nome do template no WhatsApp">
            <input appInput formControlName="providerTemplateName" maxlength="120" required />
          </app-field>
        }
        <app-field label="Referência (opcional)">
          <input appInput formControlName="reference" maxlength="120" />
        </app-field>
        <app-field label="Data da decisão">
          <div class="input-group">
            <input appInput formControlName="decidedAt" ngbDatepicker #picker="ngbDatepicker" container="body" placeholder="dd/mm/aaaa" autocomplete="off" />
            <button class="btn btn-outline-secondary" type="button" (click)="picker.toggle()" aria-label="Abrir calendário"><app-icon name="calendar-event" /></button>
          </div>
        </app-field>
        <app-field [label]="form.controls.decision.value === 'REJECTED' ? 'Motivo da recusa' : 'Observação (opcional)'">
          <textarea appInput rows="2" formControlName="note" maxlength="500" [required]="form.controls.decision.value === 'REJECTED'"></textarea>
        </app-field>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="saving()">Registrar</button>
      </div>
    </form>
  `,
})
export class ApprovalDialog {
  protected readonly data = inject<TemplateDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<TemplateView>>(DialogRef);
  private readonly api = inject(TemplatesApi);
  protected readonly saving = signal(false);
  private readonly fb = inject(FormBuilder).nonNullable;
  /** Hoje (data local): sem alteração, a decisão é registrada com o instante atual, como antes. */
  private readonly today = toLocalDate(new Date());
  protected readonly form = this.fb.group({
    decision: this.fb.control<ApprovalRequest['decision']>('APPROVED'),
    providerTemplateName: [this.data.template?.providerTemplateName ?? '', Validators.maxLength(120)],
    reference: ['', Validators.maxLength(120)],
    decidedAt: this.fb.control<string | null>(this.today),
    note: ['', Validators.maxLength(500)],
  });

  protected save(): void {
    const v = this.form.getRawValue();
    if ((v.decision === 'APPROVED' && !v.providerTemplateName.trim()) || (v.decision === 'REJECTED' && !v.note.trim()) || this.form.controls.decidedAt.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.api
      .approval(this.data.template!.id, {
        decision: v.decision,
        providerTemplateName: v.providerTemplateName.trim() || null,
        reference: v.reference.trim() || null,
        note: v.note.trim() || null,
        decidedAt: this.decidedAt(v.decidedAt),
      })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe((t) => this.ref.close(t));
  }

  /** Instante da decisão: agora (data não alterada) ou início do dia escolhido. */
  private decidedAt(value: string | null): string | null {
    if (!value) {
      return null;
    }
    return value === this.today ? new Date().toISOString() : (fromLocalDate(value)?.toISOString() ?? null);
  }
}

/** Valores de exemplo para a prévia, pelo tipo de cada variável. */
export function exampleValues(t: Pick<TemplateView, 'variables'>): Record<string, string> {
  return Object.fromEntries(t.variables.map((v) => [v.name, VARIABLE_EXAMPLES[v.type]]));
}
