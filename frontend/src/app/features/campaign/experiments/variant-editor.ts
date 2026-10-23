import { Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { CHANNEL_LABELS, DAY_LABELS, DAYS } from '../data/campaign.labels';
import { CadenceStep, DayOfWeek, ExperimentVariant, MessageChannel, StepOverride } from '../data/campaign.models';
import { TemplateRef, pickTemplate, sendsMessage, stepSummary } from '../data/campaign.rules';

/**
 * Variante do teste A/B: nome, peso e alterações por etapa da régua publicada (template, canal,
 * horário ou oferta). Sem alterações, a variante é a própria régua publicada ("campeã" atual).
 */
@Component({
  selector: 'app-variant-editor',
  imports: [FormsModule, MatButtonModule, MatButtonToggleModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, MatSlideToggleModule],
  template: `
    @let v = variant();
    @let ro = readonly();
    <div class="ve-row">
      <mat-form-field class="app-compact ve-code">
        <mat-label>Código</mat-label>
        <input matInput [ngModel]="v.code" (ngModelChange)="v.code = upper($event); changed()" maxlength="40" spellcheck="false" autocomplete="off" [disabled]="ro" />
      </mat-form-field>
      <mat-form-field class="app-compact ve-grow">
        <mat-label>Nome</mat-label>
        <input matInput [(ngModel)]="v.name" (ngModelChange)="changed()" maxlength="120" [disabled]="ro" />
      </mat-form-field>
      <mat-form-field class="app-compact ve-weight">
        <mat-label>Peso</mat-label>
        <input matInput type="number" min="1" max="100" step="1" [(ngModel)]="v.weight" (ngModelChange)="changed()" [disabled]="ro" />
      </mat-form-field>
      @if (!ro) {
        <button mat-icon-button type="button" (click)="remove.emit()" [attr.aria-label]="'Remover variante ' + (v.code || '')">
          <mat-icon aria-hidden="true">delete</mat-icon>
        </button>
      }
    </div>

    @for (o of v.overrides; track $index; let j = $index) {
      @let step = stepOf(o.stepCode);
      <div class="ve-override">
        <div class="ve-row">
          <mat-form-field class="app-compact ve-grow">
            <mat-label>Etapa alterada</mat-label>
            <mat-select [(ngModel)]="o.stepCode" (ngModelChange)="changed()" [disabled]="ro">
              @for (s of steps(); track s.code) {
                <mat-option [value]="s.code">{{ s.code }} — {{ summary(s) }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          @if (!ro) {
            <button mat-icon-button type="button" (click)="removeOverride(j)" aria-label="Remover alteração">
              <mat-icon aria-hidden="true">close</mat-icon>
            </button>
          }
        </div>
        @if (step && sendsMessage(step.action)) {
          <div class="ve-row">
            <mat-form-field class="app-compact ve-channel">
              <mat-label>Canal</mat-label>
              <mat-select [ngModel]="o.channel ?? ''" (ngModelChange)="o.channel = $event || null; changed()" [disabled]="ro">
                <mat-option value="">Igual à régua ({{ channelLabel(step.action.channel) }})</mat-option>
                @for (c of channels; track c) {
                  <mat-option [value]="c">{{ channelLabels[c] }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
            @if (templates(); as list) {
              <mat-form-field subscriptSizing="dynamic" class="app-compact ve-grow">
                <mat-label>Template</mat-label>
                <mat-select [ngModel]="o.templateCode ?? ''" (ngModelChange)="o.templateCode = $event || null; changed()" [disabled]="ro">
                  <mat-option value="">Igual à régua ({{ step.action.templateCode }})</mat-option>
                  @for (t of templatesFor(o.channel ?? step.action.channel); track t.code) {
                    <mat-option [value]="t.code">{{ t.code }} — {{ t.name }}</mat-option>
                  }
                </mat-select>
                <mat-hint>As variáveis da etapa precisam servir também a este template.</mat-hint>
              </mat-form-field>
            } @else {
              <mat-form-field subscriptSizing="dynamic" class="app-compact ve-grow">
                <mat-label>Template (código)</mat-label>
                <input matInput [ngModel]="o.templateCode" (ngModelChange)="o.templateCode = $event ? upper($event) : null; changed()" maxlength="60" [disabled]="ro" />
                <mat-hint>Vazio = igual à régua. Conferido ao iniciar o teste.</mat-hint>
              </mat-form-field>
            }
          </div>
        }
        <mat-slide-toggle [ngModel]="!!o.window" (ngModelChange)="setWindow(o, $event)" [disabled]="ro">Outro horário de execução</mat-slide-toggle>
        @if (o.window; as w) {
          <div class="ve-row ve-top">
            <mat-form-field class="app-compact ve-time">
              <mat-label>Das</mat-label>
              <input matInput type="time" [(ngModel)]="w.start" (ngModelChange)="changed()" [disabled]="ro" />
            </mat-form-field>
            <mat-form-field class="app-compact ve-time">
              <mat-label>Até</mat-label>
              <input matInput type="time" [(ngModel)]="w.end" (ngModelChange)="changed()" [disabled]="ro" />
            </mat-form-field>
            <mat-button-toggle-group multiple [ngModel]="w.days ?? []" (ngModelChange)="setDays(o, $event)" aria-label="Dias da semana" [disabled]="ro" class="ve-days">
              @for (d of days; track d) {
                <mat-button-toggle [value]="d">{{ dayLabels[d] }}</mat-button-toggle>
              }
            </mat-button-toggle-group>
          </div>
        }
        @if (step?.action?.type === 'OFFER') {
          <mat-slide-toggle [ngModel]="!!o.offer" (ngModelChange)="o.offer = $event ? { installments: 1, concessionLevel: 0, validityDays: 3 } : null; changed()" [disabled]="ro">Outra oferta</mat-slide-toggle>
          @if (o.offer; as of) {
            <div class="ve-row ve-top">
              <mat-form-field class="app-compact ve-weight">
                <mat-label>Pagamentos</mat-label>
                <input matInput type="number" min="1" max="120" [(ngModel)]="of.installments" (ngModelChange)="changed()" [disabled]="ro" />
              </mat-form-field>
              <mat-form-field class="app-compact ve-weight">
                <mat-label>Nível</mat-label>
                <input matInput type="number" min="0" max="99" [(ngModel)]="of.concessionLevel" (ngModelChange)="changed()" [disabled]="ro" />
              </mat-form-field>
              <mat-form-field class="app-compact ve-weight">
                <mat-label>Validade (dias)</mat-label>
                <input matInput type="number" min="1" max="30" [(ngModel)]="of.validityDays" (ngModelChange)="changed()" [disabled]="ro" />
              </mat-form-field>
            </div>
          }
        }
      </div>
    } @empty {
      <p class="app-muted ve-small">Sem alterações: esta variante segue a régua publicada.</p>
    }
    @if (!ro && steps().length) {
      <button mat-button type="button" (click)="addOverride()"><mat-icon aria-hidden="true">add</mat-icon>Alterar uma etapa</button>
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
    .ve-days {
      flex-wrap: wrap;
      margin-bottom: 16px;
    }
    .ve-override {
      margin: 0 0 8px;
      padding: 8px;
      border-left: 3px solid var(--mat-sys-primary);
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

  protected setDays(o: StepOverride, days: DayOfWeek[]): void {
    if (o.window) {
      o.window.days = DAYS.filter((d) => days.includes(d));
      this.changed();
    }
  }
}
