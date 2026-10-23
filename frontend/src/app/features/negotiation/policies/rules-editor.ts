import { Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
  COMPONENT_LABELS,
  FEE_TYPE_LABELS,
  FREQUENCY_LABELS,
  HONORARIUM_BASE_LABELS,
  INTEREST_MODE_LABELS,
  OVERRIDE_GROUP_LABELS,
  percent,
} from '../data/negotiation.labels';
import { DEBT_COMPONENTS, IndexView, OverrideGroup, PolicyRules } from '../data/negotiation.models';
import { NO_RATES } from '../data/negotiation.rules';

const options = <T extends string>(labels: Record<T, string>) => Object.entries(labels).map(([value, label]) => ({ value: value as T, label: label as string }));

/**
 * Editor dos grupos de regras de uma política (base ou sobreposição). Edita o objeto recebido no
 * lugar e avisa a tela a cada alteração (`edited`), que revalida os termos inteiros.
 */
@Component({
  selector: 'app-rules-editor',
  imports: [FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, MatTooltipModule],
  templateUrl: './rules-editor.html',
  styleUrl: './rules-editor.scss',
})
export class RulesEditor {
  readonly rules = input.required<PolicyRules>();
  readonly readonly = input(false);
  /** Grupos exibidos (sobreposições mostram só os que substituem). */
  readonly groups = input<readonly OverrideGroup[]>(['charges', 'discountTiers', 'installments', 'validity', 'ladder', 'breakRule']);
  readonly indexes = input<readonly IndexView[]>([]);
  readonly edited = output<void>();

  protected readonly components = DEBT_COMPONENTS;
  protected readonly componentLabels = COMPONENT_LABELS;
  protected readonly groupLabels = OVERRIDE_GROUP_LABELS;
  protected readonly interestModes = options(INTEREST_MODE_LABELS);
  protected readonly feeTypes = options(FEE_TYPE_LABELS);
  protected readonly honorariumBases = options(HONORARIUM_BASE_LABELS);
  protected readonly frequencies = options(FREQUENCY_LABELS);
  protected readonly percent = percent;

  protected has(group: OverrideGroup): boolean {
    return this.groups().includes(group);
  }

  protected changed(): void {
    this.edited.emit();
  }

  protected addFee(): void {
    this.rules().charges.fees.push({ code: '', description: '', type: 'FIXED', value: 0 });
    this.changed();
  }

  protected removeFee(i: number): void {
    this.rules().charges.fees.splice(i, 1);
    this.changed();
  }

  protected addTier(): void {
    const tiers = this.rules().discountTiers;
    const last = tiers[tiers.length - 1]?.upToInstallments ?? 0;
    tiers.push({ upToInstallments: Math.min(last + 6, 360), maxDiscount: { ...NO_RATES } });
    this.changed();
  }

  protected removeTier(i: number): void {
    this.rules().discountTiers.splice(i, 1);
    this.changed();
  }

  protected addLevel(): void {
    const ladder = this.rules().ladder;
    const previous = ladder[ladder.length - 1];
    ladder.push({ level: ladder.length, name: `Nível ${ladder.length}`, envelopeShare: { ...(previous?.envelopeShare ?? NO_RATES) }, maxInstallments: null });
    this.changed();
  }

  /** Só o último nível sai (a escada continua contígua). */
  protected removeLevel(): void {
    if (this.rules().ladder.length > 1) {
      this.rules().ladder.pop();
      this.changed();
    }
  }

  protected setFrequency(value: 'MONTHLY' | 'EVERY_N_DAYS'): void {
    const i = this.rules().installments;
    i.frequency = value;
    i.intervalDays = value === 'EVERY_N_DAYS' ? (i.intervalDays ?? 30) : null;
    this.changed();
  }

  protected upper(value: string): string {
    return (value ?? '').toUpperCase();
  }
}
