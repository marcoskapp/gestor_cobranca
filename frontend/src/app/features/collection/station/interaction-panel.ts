import { Component, ElementRef, computed, effect, inject, input, output, signal, untracked, viewChild } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { finalize, startWith } from 'rxjs';
import { NotificationService } from '../../../core/notification/notification.service';
import { DensityService } from '../../../core/preferences/density.service';
import { formatAmount } from '../../../shared/components/amount/amount';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { toDateStruct } from '../../../shared/models/dates';
import { CONTACT_LABEL_LABELS } from '../../debt/data/debt.labels';
import { InteractionsApi } from '../data/collection.api';
import { CHANNEL_LABELS, DIRECTION_LABELS } from '../data/collection.labels';
import { CaseSheet, InteractionChannel, InteractionDirection, RecordResult, SheetContact, TabulationView } from '../data/collection.models';
import { addDays, formatLocalDateTime, interactionProblems, toLocalDateTime, zonedNow } from '../data/collection.rules';

const NOTE_MAX = 2000;

/** Tipo de contato usado por canal (telefone para voz, SMS e WhatsApp). */
export function contactsForChannel(contacts: readonly SheetContact[], channel: InteractionChannel): SheetContact[] {
  const type = channel === 'EMAIL' ? 'EMAIL' : channel === 'PHONE' || channel === 'SMS' || channel === 'WHATSAPP' ? 'PHONE' : null;
  return contacts
    .filter((c) => c.type === type && c.status !== 'INVALID' && (channel !== 'WHATSAPP' || c.whatsapp))
    .sort((a, b) => a.priority - b.priority);
}

/**
 * Registro de interação com tabulação: as flags da tabulação definem os campos exigidos
 * (observação, promessa com valor e data, retorno com data e hora). A validação da tela orienta;
 * o servidor repete todas as regras.
 */
@Component({
  selector: 'app-interaction-panel',
  imports: [ReactiveFormsModule, NgbInputDatepicker, Icon, ...FORM_FIELD],
  templateUrl: './interaction-panel.html',
  styleUrl: './interaction-panel.scss',
})
export class InteractionPanel {
  readonly sheet = input.required<CaseSheet>();
  readonly tabulations = input.required<readonly TabulationView[]>();
  readonly promiseMaxDays = input(30);
  /** Início do atendimento (cronômetro da estação). */
  readonly startedAt = input<Date | null>(null);
  readonly recorded = output<RecordResult>();

  private readonly api = inject(InteractionsApi);
  private readonly notifications = inject(NotificationService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly tabulationSelect = viewChild<ElementRef<HTMLSelectElement>>('tabulationSelect');
  protected readonly density = inject(DensityService);

  protected readonly channels = Object.entries(CHANNEL_LABELS).map(([value, label]) => ({ value: value as InteractionChannel, label }));
  protected readonly directions = Object.entries(DIRECTION_LABELS).map(([value, label]) => ({ value: value as InteractionDirection, label }));
  protected readonly contactLabels = CONTACT_LABEL_LABELS;
  protected readonly noteMax = NOTE_MAX;
  protected readonly saving = signal(false);
  protected readonly submitted = signal(false);
  protected readonly formatLocal = formatLocalDateTime;

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    channel: this.fb.control<InteractionChannel>('PHONE'),
    direction: this.fb.control<InteractionDirection>('OUTBOUND'),
    contactId: this.fb.control<string>(''),
    tabulationId: this.fb.control<string>(''),
    note: ['', [Validators.maxLength(NOTE_MAX)]],
    promiseAmount: this.fb.control<number | null>(null),
    /** `LocalDate` ISO (yyyy-MM-dd) do `ngbDatepicker`. */
    promiseDate: this.fb.control<string | null>(null),
    callbackDate: this.fb.control<string | null>(null),
    callbackTime: [''],
    completesTaskId: [''],
  });

  private readonly values = toSignal(this.form.valueChanges.pipe(startWith(null)), { initialValue: null });
  protected readonly value = computed(() => {
    this.values();
    return this.form.getRawValue();
  });
  private readonly caseId = computed(() => this.sheet().collectionCase.id);

  protected readonly tabulation = computed(() => this.tabulations().find((t) => t.id === this.value().tabulationId) ?? null);
  protected readonly contacts = computed(() => contactsForChannel(this.sheet().debtor.contacts, this.value().channel));
  protected readonly openBalance = computed(() => this.sheet().collectionCase.openBalance);
  protected readonly openTasks = computed(() => this.sheet().openTasks);
  protected readonly today = computed(() => zonedNow().date);
  protected readonly minDate = computed(() => toDateStruct(this.today()));
  protected readonly maxPromiseDate = computed(() => toDateStruct(addDays(this.today(), this.promiseMaxDays())));

  protected readonly problems = computed(() => {
    const v = this.value();
    return interactionProblems(
      {
        tabulation: this.tabulation(),
        note: v.note,
        promiseAmount: v.promiseAmount,
        promiseDate: v.promiseDate,
        callbackAt: toLocalDateTime(v.callbackDate, v.callbackTime),
      },
      { openBalance: this.openBalance(), today: this.today(), promiseMaxDays: this.promiseMaxDays() },
      zonedNow(),
    );
  });

  protected readonly balanceText = computed(() => formatAmount(this.openBalance()));

  protected readonly tabulationHint = computed(() => {
    const t = this.tabulation();
    return t ? `${t.effectiveContact ? 'Contato efetivo.' : 'Sem contato efetivo.'}${t.requiresNote ? ' Observação obrigatória.' : ''}` : undefined;
  });

  /** Texto da opção com as marcas da tabulação (CPC, promessa, retorno). */
  protected tabulationOption(t: TabulationView): string {
    const flags = [t.effectiveContact ? 'CPC' : null, t.promise ? 'promessa' : null, t.callback ? 'retorno' : null].filter(Boolean);
    return flags.length ? `${t.name} · ${flags.join(' · ')}` : t.name;
  }

  constructor() {
    // Troca de caso: formulário limpo (nada de um devedor vaza para outro).
    effect(() => {
      this.caseId();
      untracked(() => this.reset());
    });
    // Contato padrão: o de maior prioridade do canal escolhido.
    effect(() => {
      const list = this.contacts();
      const current = this.form.controls.contactId.value;
      if (!list.some((c) => c.contactId === current)) {
        this.form.controls.contactId.setValue(list[0]?.contactId ?? '', { emitEvent: false });
      }
    });
  }

  /** Atalho "registrar interação": leva o foco à tabulação. */
  focus(): void {
    this.host.nativeElement.scrollIntoView({ block: 'nearest' });
    const select = this.tabulationSelect()?.nativeElement;
    select?.focus();
    // abre a lista nativa quando o navegador permite (o atalho de teclado é uma ativação do usuário)
    try {
      select?.showPicker?.();
    } catch {
      // sem ativação do usuário (ex.: chamada programática): o foco no campo basta
    }
  }

  /** Pré-seleciona a primeira tabulação com a flag (atalhos "registrar promessa"). */
  preselect(flag: 'promise' | 'callback'): void {
    const t = this.tabulations().find((x) => x[flag]);
    if (t) {
      this.form.controls.tabulationId.setValue(t.id);
    }
    this.tabulationSelect()?.nativeElement.focus();
  }

  protected onKeydown(event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
      event.preventDefault();
      this.submit();
    }
  }

  protected submit(): void {
    this.submitted.set(true);
    if (this.problems().length > 0 || this.saving()) {
      return;
    }
    const v = this.form.getRawValue();
    const t = this.tabulation()!;
    const callbackAt = toLocalDateTime(v.callbackDate, v.callbackTime);
    const started = this.startedAt();
    this.saving.set(true);
    this.api
      .record(this.sheet().collectionCase.id, {
        channel: v.channel,
        direction: v.direction,
        tabulationId: t.id,
        note: v.note.trim() || null,
        contactId: v.contactId || null,
        startedAt: started ? started.toISOString() : null,
        endedAt: started ? new Date().toISOString() : null,
        promise: t.promise && v.promiseAmount && v.promiseDate ? { amount: v.promiseAmount, dueDate: v.promiseDate } : null,
        callback: t.callback && callbackAt ? { dueAt: callbackAt, assigneeUserId: null, note: null } : null,
        completesTaskId: v.completesTaskId || null,
      })
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe((result) => {
        const extras = [
          result.promise ? `promessa de ${formatAmount(result.promise.amount)}` : null,
          result.callback ? `retorno em ${formatLocalDateTime(result.callback.dueAtLocal)}` : null,
        ].filter(Boolean);
        this.notifications.success(`Interação registrada${extras.length ? ' com ' + extras.join(' e ') : ''}.`);
        this.reset();
        this.recorded.emit(result);
      });
  }

  private reset(): void {
    this.submitted.set(false);
    this.form.reset({ channel: this.form.controls.channel.value, direction: 'OUTBOUND' });
  }
}
