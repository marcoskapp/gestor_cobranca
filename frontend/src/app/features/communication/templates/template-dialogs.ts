import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { finalize, map, startWith } from 'rxjs';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { CreditorLookup } from '../../organization/data/organization.lookups';
import { TemplatesApi } from '../data/communication.api';
import { CHANNEL_LABELS, PURPOSE_LABELS, VARIABLE_EXAMPLES, VARIABLE_TYPE_LABELS } from '../data/communication.labels';
import { ApprovalRequest, ChannelType, TemplatePurpose, TemplateView, VariableType } from '../data/communication.models';
import { variableProblems } from '../data/communication.rules';

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
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
    EntityPicker,
  ],
  template: `
    <h2 mat-dialog-title>{{ title }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content class="app-form-stack">
        @if (creating) {
          <div class="td-row">
            <mat-form-field>
              <mat-label>Código</mat-label>
              <input matInput formControlName="code" maxlength="60" required [readonly]="!!data.asNewVersion" />
              <mat-hint>Maiúsculas, números e _ (ex.: LEMBRETE_VENCIMENTO)</mat-hint>
            </mat-form-field>
            <mat-form-field>
              <mat-label>Canal</mat-label>
              <mat-select formControlName="channel" required>
                @for (c of channelOptions; track c.value) {
                  <mat-option [value]="c.value" [disabled]="!!data.asNewVersion && c.value !== data.template?.channel">{{ c.label }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
          </div>
          @if (!data.asNewVersion) {
            <app-entity-picker [control]="form.controls.creditor" [source]="creditors" label="Credor (vazio = template da assessoria)" [single]="true" />
          }
        }
        <div class="td-row">
          <mat-form-field>
            <mat-label>Nome</mat-label>
            <input matInput formControlName="name" maxlength="120" required />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Finalidade</mat-label>
            <mat-select formControlName="purpose" required>
              @for (p of purposes; track p.value) {
                <mat-option [value]="p.value">{{ p.label }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
        </div>
        @if (channel() === 'EMAIL') {
          <mat-form-field>
            <mat-label>Assunto</mat-label>
            <input matInput formControlName="subject" maxlength="300" required />
          </mat-form-field>
        }
        <mat-form-field>
          <mat-label>Texto</mat-label>
          <textarea matInput rows="5" formControlName="body" maxlength="8000" required></textarea>
          <mat-hint>Use {{ example }} para inserir valores. Não inclua CPF, telefone, e-mail ou endereço.</mat-hint>
        </mat-form-field>

        <h3 class="app-section-title">Variáveis</h3>
        <div formArrayName="variables">
          @for (v of variables.controls; track v; let i = $index) {
            <div class="td-var" [formGroupName]="i">
              <mat-form-field class="app-compact">
                <mat-label>Nome</mat-label>
                <input matInput formControlName="name" maxlength="40" required />
              </mat-form-field>
              <mat-form-field class="app-compact">
                <mat-label>Tipo</mat-label>
                <mat-select formControlName="type">
                  @for (t of types; track t.value) {
                    <mat-option [value]="t.value">{{ t.label }}</mat-option>
                  }
                </mat-select>
              </mat-form-field>
              <mat-checkbox formControlName="required">Obrigatória</mat-checkbox>
              <button mat-icon-button type="button" (click)="variables.removeAt(i)" aria-label="Remover variável" matTooltip="Remover">
                <mat-icon aria-hidden="true">delete</mat-icon>
              </button>
            </div>
          }
        </div>
        <button mat-button type="button" (click)="addVariable()"><mat-icon aria-hidden="true">add</mat-icon>Adicionar variável</button>
        @if (channel() === 'WHATSAPP') {
          <mat-form-field>
            <mat-label>Nome do template no WhatsApp (opcional)</mat-label>
            <input matInput formControlName="providerTemplateName" maxlength="120" />
            <mat-hint>No WhatsApp, os parâmetros seguem a ordem das variáveis declaradas.</mat-hint>
          </mat-form-field>
        }
        @for (p of problems(); track p) {
          <p class="app-field-error" role="alert">{{ p }}</p>
        }
        @if (channel() === 'WHATSAPP') {
          <p class="app-alert app-alert--neutral" role="note">
            <mat-icon aria-hidden="true">info</mat-icon>
            <span>Rascunho de WhatsApp fica aguardando a aprovação do provedor; editar volta a aguardar. Só o aprovado pode ser publicado.</span>
          </p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Salvar rascunho</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .td-row {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
      gap: 0 12px;
    }
    .td-var {
      display: grid;
      grid-template-columns: minmax(120px, 1fr) minmax(160px, 1fr) auto auto;
      align-items: center;
      gap: 0 8px;
    }
    @media (max-width: 599.98px) {
      .td-var {
        grid-template-columns: 1fr 1fr;
      }
    }
  `,
})
export class TemplateDialog {
  protected readonly data = inject<TemplateDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<TemplateDialog, TemplateView>>(MatDialogRef);
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
  });
  protected readonly channel = toSignal(this.form.controls.channel.valueChanges, { initialValue: this.form.controls.channel.value });
  private readonly value = toSignal(this.form.valueChanges.pipe(startWith(null), map(() => this.form.getRawValue())), { initialValue: this.form.getRawValue() });
  protected readonly problems = computed(() => {
    const v = this.value();
    return v.body.trim() ? variableProblems(v.body, v.channel === 'EMAIL' ? v.subject : null, v.variables) : [];
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
  imports: [ReactiveFormsModule, MatButtonModule, MatDatepickerModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule],
  template: `
    <h2 mat-dialog-title>Aprovação do WhatsApp</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content class="app-form-stack">
        <p class="app-dialog-message">{{ data.template!.name }} (v{{ data.template!.templateVersion }}). Registre o que o provedor respondeu no gerenciador do WhatsApp.</p>
        <mat-form-field>
          <mat-label>Decisão</mat-label>
          <mat-select formControlName="decision" required>
            <mat-option value="PENDING">Submetido, aguardando</mat-option>
            <mat-option value="APPROVED">Aprovado</mat-option>
            <mat-option value="REJECTED">Recusado</mat-option>
          </mat-select>
        </mat-form-field>
        @if (form.controls.decision.value === 'APPROVED') {
          <mat-form-field>
            <mat-label>Nome do template no WhatsApp</mat-label>
            <input matInput formControlName="providerTemplateName" maxlength="120" required />
          </mat-form-field>
        }
        <mat-form-field>
          <mat-label>Referência (opcional)</mat-label>
          <input matInput formControlName="reference" maxlength="120" />
        </mat-form-field>
        <mat-form-field>
          <mat-label>Data da decisão</mat-label>
          <input matInput [matDatepicker]="picker" formControlName="decidedAt" />
          <mat-datepicker-toggle matIconSuffix [for]="picker" />
          <mat-datepicker #picker />
        </mat-form-field>
        <mat-form-field>
          <mat-label>{{ form.controls.decision.value === 'REJECTED' ? 'Motivo da recusa' : 'Observação (opcional)' }}</mat-label>
          <textarea matInput rows="2" formControlName="note" maxlength="500" [required]="form.controls.decision.value === 'REJECTED'"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="saving()">Registrar</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class ApprovalDialog {
  protected readonly data = inject<TemplateDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ApprovalDialog, TemplateView>>(MatDialogRef);
  private readonly api = inject(TemplatesApi);
  protected readonly saving = signal(false);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    decision: this.fb.control<ApprovalRequest['decision']>('APPROVED'),
    providerTemplateName: [this.data.template?.providerTemplateName ?? '', Validators.maxLength(120)],
    reference: ['', Validators.maxLength(120)],
    decidedAt: this.fb.control<Date | null>(new Date()),
    note: ['', Validators.maxLength(500)],
  });

  protected save(): void {
    const v = this.form.getRawValue();
    if ((v.decision === 'APPROVED' && !v.providerTemplateName.trim()) || (v.decision === 'REJECTED' && !v.note.trim()) || this.saving()) {
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
        decidedAt: v.decidedAt ? v.decidedAt.toISOString() : null,
      })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe((t) => this.ref.close(t));
  }
}

/** Valores de exemplo para a prévia, pelo tipo de cada variável. */
export function exampleValues(t: Pick<TemplateView, 'variables'>): Record<string, string> {
  return Object.fromEntries(t.variables.map((v) => [v.name, VARIABLE_EXAMPLES[v.type]]));
}
