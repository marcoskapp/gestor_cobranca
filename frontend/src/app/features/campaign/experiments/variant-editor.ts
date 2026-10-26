import { Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { CHANNEL_LABELS, DAY_LABELS, DAYS } from '../data/campaign.labels';
import { CadenceStep, DayOfWeek, ExperimentVariant, MessageChannel, StepOverride } from '../data/campaign.models';
import { TemplateRef, pickTemplate, sendsMessage, stepSummary } from '../data/campaign.rules';

/**
 * Variante do teste A/B: nome, peso e alterações por etapa da régua publicada (template, canal,
 * horário ou oferta). Sem alterações, a variante é a própria régua publicada ("campeã" atual).
 */
@Component({
  selector: 'app-variant-editor',
  imports: [FormsModule, ...FORM_FIELD, Icon],
  template: `
    @let v = variant();
    @let ro = readonly();
    <div class="ve-row">
      <app-field class="ve-code" label="Código">
        <input appInput [ngModel]="v.code" (ngModelChange)="v.code = upper($event); changed()" maxlength="40" spellcheck="false" autocomplete="off" [disabled]="ro" />
      </app-field>
      <app-field class="ve-grow" label="Nome">
        <input appInput [(ngModel)]="v.name" (ngModelChange)="changed()" maxlength="120" [disabled]="ro" />
      </app-field>
      <app-field class="ve-weight" label="Peso">
        <input appInput type="number" min="1" max="100" step="1" [(ngModel)]="v.weight" (ngModelChange)="changed()" [disabled]="ro" />
      </app-field>
      @if (!ro) {
        <button class="btn btn-link" type="button" (click)="remove.emit()" [attr.aria-label]="'Remover variante ' + (v.code || '')">
          <app-icon name="trash3" />
        </button>
      }
    </div>

    @for (o of v.overrides; track $index; let j = $index) {
      @let step = stepOf(o.stepCode);
      <div class="ve-override">
        <div class="ve-row">
          <app-field class="ve-grow" label="Etapa alterada">
            <select appInput [(ngModel)]="o.stepCode" (ngModelChange)="changed()" [disabled]="ro">
              @for (s of steps(); track s.code) {
                <option [ngValue]="s.code">{{ s.code }} — {{ summary(s) }}</option>
              }
            </select>
          </app-field>
          @if (!ro) {
            <button class="btn btn-link" type="button" (click)="removeOverride(j)" aria-label="Remover alteração">
              <app-icon name="x-lg" />
            </button>
          }
        </div>
        @if (step && sendsMessage(step.action)) {
          <div class="ve-row">
            <app-field class="ve-channel" label="Canal">
              <select appInput [ngModel]="o.channel ?? ''" (ngModelChange)="o.channel = $event || null; changed()" [disabled]="ro">
                <option value="">Igual à régua ({{ channelLabel(step.action.channel) }})</option>
                @for (c of channels; track c) {
                  <option [ngValue]="c">{{ channelLabels[c] }}</option>
                }
              </select>
            </app-field>
            @if (templates(); as list) {
              <app-field class="ve-grow" label="Template" hint="As variáveis da etapa precisam servir também a este template.">
                <select appInput [ngModel]="o.templateCode ?? ''" (ngModelChange)="o.templateCode = $event || null; changed()" [disabled]="ro">
                  <option value="">Igual à régua ({{ step.action.templateCode }})</option>
                  @for (t of templatesFor(o.channel ?? step.action.channel); track t.code) {
                    <option [ngValue]="t.code">{{ t.code }} — {{ t.name }}</option>
                  }
                </select>
              </app-field>
            } @else {
              <app-field class="ve-grow" label="Template (código)" hint="Vazio = igual à régua. Conferido ao iniciar o teste.">
                <input appInput [ngModel]="o.templateCode" (ngModelChange)="o.templateCode = $event ? upper($event) : null; changed()" maxlength="60" [disabled]="ro" />
              </app-field>
            }
          </div>
        }
        <div class="form-check form-switch mt-2">
          <input class="form-check-input" type="checkbox" role="switch" [id]="uid + '-window-' + j" [ngModel]="!!o.window" (ngModelChange)="setWindow(o, $event)" [disabled]="ro" />
          <label class="form-check-label" [for]="uid + '-window-' + j">Outro horário de execução</label>
        </div>
        @if (o.window; as w) {
          <div class="ve-row ve-top">
            <app-field class="ve-time" label="Das">
              <input appInput type="time" [(ngModel)]="w.start" (ngModelChange)="changed()" [disabled]="ro" />
            </app-field>
            <app-field class="ve-time" label="Até">
              <input appInput type="time" [(ngModel)]="w.end" (ngModelChange)="changed()" [disabled]="ro" />
            </app-field>
            <div class="btn-group btn-group-sm flex-wrap app-control-offset" role="group" aria-label="Dias da semana">
              @for (d of days; track d) {
                <input class="btn-check" type="checkbox" [id]="uid + '-day-' + j + '-' + d" [checked]="(w.days ?? []).includes(d)" (change)="toggleDay(o, d)" [disabled]="ro" />
                <label class="btn btn-outline-primary" [for]="uid + '-day-' + j + '-' + d">{{ dayLabels[d] }}</label>
              }
            </div>
          </div>
        }
        @if (step?.action?.type === 'OFFER') {
          <div class="form-check form-switch mt-2">
            <input
              class="form-check-input"
              type="checkbox"
              role="switch"
              [id]="uid + '-offer-' + j"
              [ngModel]="!!o.offer"
              (ngModelChange)="o.offer = $event ? { installments: 1, concessionLevel: 0, validityDays: 3 } : null; changed()"
              [disabled]="ro"
            />
            <label class="form-check-label" [for]="uid + '-offer-' + j">Outra oferta</label>
          </div>
          @if (o.offer; as of) {
            <div class="ve-row ve-top">
              <app-field class="ve-weight" label="Pagamentos">
                <input appInput type="number" min="1" max="120" [(ngModel)]="of.installments" (ngModelChange)="changed()" [disabled]="ro" />
              </app-field>
              <app-field class="ve-weight" label="Nível">
                <input appInput type="number" min="0" max="99" [(ngModel)]="of.concessionLevel" (ngModelChange)="changed()" [disabled]="ro" />
              </app-field>
              <app-field class="ve-weight" label="Validade (dias)">
                <input appInput type="number" min="1" max="30" [(ngModel)]="of.validityDays" (ngModelChange)="changed()" [disabled]="ro" />
              </app-field>
            </div>
          }
        }
      </div>
    } @empty {
      <p class="app-muted ve-small">Sem alterações: esta variante segue a régua publicada.</p>
    }
    @if (!ro && steps().length) {
      <button class="btn btn-link d-inline-flex align-items-center gap-2" type="button" (click)="addOverride()"><app-icon name="plus-lg" />Alterar uma etapa</button>
    }
  `,
  styles: `
    .ve-row {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      gap: 8px;
    }
    .ve-top {
      margin-top: 8px;
    }
    .ve-grow {
      flex: 1 1 240px;
      min-width: 0;
    }
    .ve-code {
      flex: 0 1 140px;
    }
    .ve-weight {
      flex: 0 1 110px;
    }
    .ve-channel {
      flex: 0 1 220px;
    }
    .ve-time {
      flex: 0 1 120px;
    }
    .ve-override {
      margin: 0 0 8px;
      padding: 8px;
      border-left: 3px solid var(--bs-primary);
      border-radius: var(--app-radius-sm);
      background: var(--app-surface-raised);
    }
    .ve-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class VariantEditor {
  readonly variant = input.required<ExperimentVariant>();
  /** Etapas da régua publicada (as alterações referem-se a elas). */
  readonly steps = input<readonly CadenceStep[]>([]);
  readonly templates = input<readonly TemplateRef[] | null>(null);
  readonly creditorId = input.required<string>();
  readonly readonly = input(false);
  readonly edited = output<void>();
  readonly remove = output<void>();

  private static seq = 0;
  /** Prefixo dos ids das chaves e dos dias (várias variantes na mesma tela). */
  protected readonly uid = `ve-${++VariantEditor.seq}`;
  protected readonly channels: readonly MessageChannel[] = ['WHATSAPP', 'EMAIL', 'SMS'];
  protected readonly channelLabels = CHANNEL_LABELS;
  protected readonly days = DAYS;
  protected readonly dayLabels = DAY_LABELS;
  protected readonly sendsMessage = sendsMessage;

  protected changed(): void {
    this.edited.emit();
  }

  protected upper(value: string): string {
    return (value ?? '').toUpperCase().replace(/\s+/g, '_');
  }

  protected stepOf(code: string): CadenceStep | undefined {
    return this.steps().find((s) => s.code === code);
  }

  protected summary(s: CadenceStep): string {
    return stepSummary(s);
  }

  protected channelLabel(c: string | null): string {
    return c ? (CHANNEL_LABELS[c as MessageChannel] ?? c) : '—';
  }

  protected templatesFor(channel: string | null): TemplateRef[] {
    const list = this.templates() ?? [];
    const codes = [...new Set(list.filter((t) => t.channel === channel).map((t) => t.code))].sort();
    return codes.map((code) => pickTemplate(list, code, channel, this.creditorId())).filter((t): t is TemplateRef => !!t);
  }

  protected addOverride(): void {
    const used = new Set(this.variant().overrides.map((o) => o.stepCode));
    const step = this.steps().find((s) => !used.has(s.code) && sendsMessage(s.action)) ?? this.steps().find((s) => !used.has(s.code));
    this.variant().overrides.push({ stepCode: step?.code ?? '', channel: null, templateCode: null, window: null, offer: null });
    this.changed();
  }

  protected removeOverride(index: number): void {
    this.variant().overrides.splice(index, 1);
    this.changed();
  }

  protected setWindow(o: StepOverride, on: boolean): void {
    o.window = on ? { start: '09:00', end: '12:00', days: ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'] } : null;
    this.changed();
  }

  /** Marca/desmarca um dia (equivale ao grupo de botões de seleção múltipla anterior). */
  protected toggleDay(o: StepOverride, day: DayOfWeek): void {
    const current = o.window?.days ?? [];
    this.setDays(o, current.includes(day) ? current.filter((d) => d !== day) : [...current, day]);
  }

  protected setDays(o: StepOverride, days: DayOfWeek[]): void {
    if (o.window) {
      o.window.days = DAYS.filter((d) => days.includes(d));
      this.changed();
    }
  }
}
