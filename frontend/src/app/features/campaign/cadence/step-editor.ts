import { Component, computed, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { Option } from '../../../shared/models/lookup';
import { ACTION_HINTS, ACTION_LABELS, CHANNEL_LABELS, DAY_LABELS, DAYS, REPLY_LABELS, TASK_LABELS, VARIABLE_SOURCE_LABELS } from '../data/campaign.labels';
import { ActionType, CadenceStep, DayOfWeek, MessageChannel, ReplyTransition, VariableSource } from '../data/campaign.models';
import {
  DurationUnit,
  OFFER_SOURCES,
  TemplateRef,
  UNIT_MINUTES,
  emptyAction,
  emptyCriteria,
  literalOf,
  pickTemplate,
  sendsMessage,
  sourceOf,
  splitDuration,
  windowIsAlways,
} from '../data/campaign.rules';
import { CriteriaEditor } from '../segments/criteria-editor';

const ACTIONS = Object.keys(ACTION_LABELS) as ActionType[];
const REPLIES = Object.keys(REPLY_LABELS) as ReplyTransition[];
const MESSAGE_CHANNELS: readonly MessageChannel[] = ['WHATSAPP', 'EMAIL', 'SMS'];
const SOURCES = Object.keys(VARIABLE_SOURCE_LABELS) as VariableSource[];

/** Sugestão de origem pelo tipo declarado no template. */
const SUGGESTED: Record<string, VariableSource> = {
  FIRST_NAME: 'DEBTOR_FIRST_NAME',
  MONEY: 'OPEN_BALANCE',
  DATE: 'OLDEST_DUE_DATE',
  INTEGER: 'DAYS_OVERDUE',
  TEXT: 'CREDITOR_NAME',
};

export interface StepMove {
  index: number;
  delta: -1 | 1;
}

/**
 * Editor de uma etapa da régua: espera, janela de horário, condição, ação (canal, template com
 * variáveis validadas contra o template publicado, tarefa, fila ou oferta) e a regra de resposta.
 * Edita a etapa recebida no lugar e avisa a tela (`edited`), que revalida a régua inteira.
 */
@Component({
  selector: 'app-step-editor',
  imports: [FormsModule, ...FORM_FIELD, Icon, CriteriaEditor],
  templateUrl: './step-editor.html',
  styleUrl: './step-editor.scss',
})
export class StepEditor {
  readonly step = input.required<CadenceStep>();
  readonly index = input.required<number>();
  readonly steps = input.required<readonly CadenceStep[]>();
  /** Templates publicados do credor e da assessoria; nulo = sem permissão para consultar (validação só na publicação). */
  readonly templates = input<readonly TemplateRef[] | null>(null);
  readonly creditorId = input.required<string>();
  readonly teams = input<readonly Option[]>([]);
  readonly readonly = input(false);
  /** Erros e avisos desta etapa (calculados pela tela). */
  readonly problems = input<readonly string[]>([]);
  readonly warnings = input<readonly string[]>([]);

  readonly edited = output<void>();
  readonly move = output<StepMove>();
  readonly duplicate = output<number>();
  readonly remove = output<number>();

  private static seq = 0;
  /** Prefixo dos ids das caixas de seleção e dos dias (rótulos ligados por `for`). */
  protected readonly uid = `se-${++StepEditor.seq}`;
  protected readonly actions = ACTIONS;
  protected readonly actionLabels = ACTION_LABELS;
  protected readonly actionHints = ACTION_HINTS;
  protected readonly replies = REPLIES;
  protected readonly replyLabels = REPLY_LABELS;
  protected readonly channels = MESSAGE_CHANNELS;
  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly taskLabels = TASK_LABELS;
  protected readonly sourceLabels = VARIABLE_SOURCE_LABELS;
  protected readonly days = DAYS;
  protected readonly dayLabels = DAY_LABELS;
  protected readonly units: { value: DurationUnit; label: string }[] = [
    { value: 'MINUTES', label: 'minutos' },
    { value: 'HOURS', label: 'horas' },
    { value: 'DAYS', label: 'dias' },
  ];
  protected readonly sendsMessage = sendsMessage;
  protected readonly literalOf = literalOf;
  protected readonly sourceOf = sourceOf;

  protected readonly otherSteps = computed(() => this.steps().filter((s) => s !== this.step()));
  protected readonly channelTemplates = computed(() => {
    const list = this.templates();
    const a = this.step().action;
    if (!list || !a.channel) {
      return [];
    }
    const codes = [...new Set(list.filter((t) => t.channel === a.channel && (t.creditorId === null || t.creditorId === this.creditorId())).map((t) => t.code))].sort();
    return codes.map((code) => pickTemplate(list, code, a.channel, this.creditorId())!);
  });

  // ------------------------------------------------------------- leitura (o template lê a etapa atual)

  protected wait(): { value: number; unit: DurationUnit } {
    return splitDuration(this.step().waitMinutes);
  }

  protected due(): { value: number; unit: DurationUnit } {
    return splitDuration(this.step().action.taskDueInMinutes);
  }

  protected restricted(): boolean {
    return !windowIsAlways(this.step().window);
  }

  protected template(): TemplateRef | null {
    const list = this.templates();
    const a = this.step().action;
    return list ? pickTemplate(list, a.templateCode, a.channel, this.creditorId()) : null;
  }

  /** Variáveis exibidas: as do template e as mapeadas fora dele (para remover). */
  protected variableRows(): { name: string; type: string | null; required: boolean; known: boolean }[] {
    const t = this.template();
    const mapped = Object.keys(this.step().action.variables ?? {});
    const rows: { name: string; type: string | null; required: boolean; known: boolean }[] = (t?.variables ?? []).map((v) => ({ name: v.name, type: v.type, required: v.required, known: true }));
    for (const name of mapped.filter((n) => !rows.some((r) => r.name === n))) {
      rows.push({ name, type: null, required: false, known: this.templates() === null });
    }
    return rows;
  }

  protected sourceOptions(): VariableSource[] {
    return this.step().action.type === 'OFFER' ? SOURCES : SOURCES.filter((s) => !OFFER_SOURCES.includes(s));
  }

  /** `{{nome}}` como aparece no texto do template. */
  protected token(name: string): string {
    return `{{${name}}}`;
  }

  protected variableSource(name: string): VariableSource | null {
    return sourceOf(this.step().action.variables?.[name]);
  }

  // ------------------------------------------------------------- edição

  protected changed(): void {
    this.edited.emit();
  }

  protected setCode(value: string): void {
    const previous = this.step().code;
    const code = value.toUpperCase().replace(/\s+/g, '_');
    this.step().code = code;
    // mantém as transições de resposta que apontavam para o código anterior
    for (const s of this.steps()) {
      if (s.onReplyStepCode === previous) s.onReplyStepCode = code;
    }
    this.changed();
  }

  protected setWait(value: number | null, unit: DurationUnit): void {
    this.step().waitMinutes = Math.max(0, Math.round((value ?? 0) * UNIT_MINUTES[unit]));
    this.changed();
  }

  protected setDue(value: number | null, unit: DurationUnit): void {
    this.step().action.taskDueInMinutes = Math.max(0, Math.round((value ?? 0) * UNIT_MINUTES[unit]));
    this.changed();
  }

  protected setRestricted(on: boolean): void {
    this.step().window = on ? { start: '09:00', end: '18:00', days: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'] } : null;
    this.changed();
  }

  protected setDays(days: DayOfWeek[]): void {
    const w = this.step().window;
    if (w) {
      w.days = DAYS.filter((d) => days.includes(d));
      this.changed();
    }
  }

  /** Marca/desmarca um dia da janela (equivale ao grupo de botões de seleção múltipla anterior). */
  protected toggleDay(day: DayOfWeek): void {
    const current = this.step().window?.days ?? [];
    this.setDays(current.includes(day) ? current.filter((d) => d !== day) : [...current, day]);
  }

  protected setCondition(field: 'requireNoReply' | 'requireNoEffectiveContact' | 'requirePreviousMessageUnread', value: boolean): void {
    const s = this.step();
    s.condition ??= { criteria: null, requireNoReply: false, requireNoEffectiveContact: false, requirePreviousMessageUnread: false };
    s.condition[field] = value;
    this.changed();
  }

  protected setCriteria(on: boolean): void {
    const s = this.step();
    s.condition ??= { criteria: null, requireNoReply: false, requireNoEffectiveContact: false, requirePreviousMessageUnread: false };
    s.condition.criteria = on ? { ...emptyCriteria(), eligibleOnly: true } : null;
    this.changed();
  }

  protected setAction(type: ActionType): void {
    const s = this.step();
    s.action = emptyAction(type, s.action);
    this.changed();
  }

  protected setChannel(channel: MessageChannel): void {
    const a = this.step().action;
    a.channel = channel;
    if (this.templates() && !this.template()) {
      a.templateCode = null;
    }
    this.changed();
  }

  /** Troca o template: descarta variáveis fora dele e sugere origens para as obrigatórias. */
  protected setTemplate(code: string): void {
    const a = this.step().action;
    a.templateCode = code ? code.trim().toUpperCase() : null;
    const t = this.template();
    if (t) {
      const next: Record<string, string> = {};
      for (const v of t.variables) {
        const current = a.variables?.[v.name];
        const suggested = a.type === 'OFFER' && v.type === 'MONEY' ? 'OFFER_AMOUNT' : a.type === 'OFFER' && v.type === 'URL' ? 'PAYMENT_LINK' : SUGGESTED[v.type];
        if (current) next[v.name] = current;
        else if (v.required && suggested) next[v.name] = suggested;
        else if (v.required) next[v.name] = 'LITERAL:';
      }
      a.variables = next;
    }
    this.changed();
  }

  protected setSource(name: string, source: VariableSource | ''): void {
    const a = this.step().action;
    const vars = { ...(a.variables ?? {}) };
    if (!source) delete vars[name];
    else vars[name] = source === 'LITERAL' ? `LITERAL:${literalOf(vars[name])}` : source;
    a.variables = vars;
    this.changed();
  }

  protected setLiteral(name: string, text: string): void {
    const a = this.step().action;
    a.variables = { ...(a.variables ?? {}), [name]: `LITERAL:${text}` };
    this.changed();
  }

  protected addVariable(name: string): void {
    const key = name.trim().toLowerCase();
    if (!key) {
      return;
    }
    const a = this.step().action;
    a.variables = { ...(a.variables ?? {}), [key]: a.variables?.[key] ?? 'DEBTOR_FIRST_NAME' };
    this.changed();
  }

  protected removeVariable(name: string): void {
    this.setSource(name, '');
  }

  protected setReply(value: ReplyTransition | null): void {
    const s = this.step();
    s.onReply = value;
    s.onReplyStepCode = value === 'GOTO_STEP' ? (s.onReplyStepCode ?? this.otherSteps()[0]?.code ?? null) : null;
    this.changed();
  }
}
