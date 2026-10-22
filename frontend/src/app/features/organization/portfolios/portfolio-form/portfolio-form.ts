import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { finalize, startWith } from 'rxjs';
import { toApiError } from '../../../../core/http/problem';
import { NotificationService } from '../../../../core/notification/notification.service';
import { EntityPicker } from '../../../../shared/components/entity-picker/entity-picker';
import { valueOr } from '../../../../shared/data/resource-value';
import { fromLocalDate, toLocalDate } from '../../../../shared/models/dates';
import { errorMessage } from '../../../../shared/validation/error-messages';
import { applyServerErrors } from '../../../../shared/validation/server-errors';
import { AppValidators, Limits, Patterns } from '../../../../shared/validation/validators';
import { ContractsApi, PortfoliosApi } from '../../data/organization.api';
import { CreditorLookup } from '../../data/organization.lookups';
import { PortfolioView } from '../../data/organization.models';

export interface PortfolioFormData {
  portfolio: PortfolioView | null;
  creditorId?: string | null;
}

type Field = 'creditor' | 'contractId' | 'code' | 'name' | 'description' | 'receivedOn' | 'validUntil';

@Component({
  selector: 'app-portfolio-form',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatDatepickerModule,
    EntityPicker,
  ],
  templateUrl: './portfolio-form.html',
})
export class PortfolioForm {
  protected readonly data = inject<PortfolioFormData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<PortfolioForm, PortfolioView>>(MatDialogRef);
  private readonly api = inject(PortfoliosApi);
  private readonly contractsApi = inject(ContractsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly creditors = inject(CreditorLookup);

  protected readonly limits = Limits;
  protected readonly editing = this.data.portfolio !== null;
  protected readonly saving = signal(false);
  protected readonly generalErrors = signal<string[]>([]);

  private readonly fb = inject(FormBuilder);
  protected readonly form = this.fb.group(
    {
      creditor: this.fb.nonNullable.control<string[]>(
        this.data.portfolio ? [this.data.portfolio.creditorId] : this.data.creditorId ? [this.data.creditorId] : [],
        [AppValidators.notBlank],
      ),
      contractId: [this.data.portfolio?.contractId ?? '', [AppValidators.notBlank]],
      code: [
        this.data.portfolio?.code ?? '',
        [AppValidators.notBlank, Validators.maxLength(Limits.portfolioCode), Validators.pattern(Patterns.code)],
      ],
      name: [this.data.portfolio?.name ?? '', [AppValidators.notBlank, Validators.maxLength(Limits.portfolioName)]],
      description: [this.data.portfolio?.description ?? '', [Validators.maxLength(Limits.portfolioDescription)]],
      receivedOn: [fromLocalDate(this.data.portfolio?.receivedOn) ?? new Date(), [Validators.required]],
      validUntil: [fromLocalDate(this.data.portfolio?.validUntil)],
    },
    { validators: AppValidators.dateRange('receivedOn', 'validUntil') },
  );

  private readonly creditorId = toSignal(
    this.form.controls.creditor.valueChanges.pipe(startWith(this.form.controls.creditor.value)),
    { requireSync: true },
  );
  private readonly contractsResource = rxResource({
    params: () => this.creditorId()[0],
    stream: ({ params }) => this.contractsApi.listByCreditor(params),
  });
  private readonly contracts = valueOr(this.contractsResource, []);
  protected readonly activeContracts = computed(() => this.contracts().filter((c) => c.status === 'ACTIVE'));
  protected readonly contractsLoading = this.contractsResource.isLoading;

  constructor() {
    if (this.editing) {
      this.form.controls.creditor.disable();
      this.form.controls.contractId.disable();
      this.form.controls.code.disable();
    } else {
      this.form.controls.creditor.valueChanges
        .pipe(takeUntilDestroyed())
        .subscribe(() => this.form.controls.contractId.setValue(''));
    }
  }

  protected message(field: Field): string {
    const errors = this.form.controls[field].errors;
    if (field === 'code' && errors?.['pattern']) {
      return 'Use letras, números, "-" ou "_".';
    }
    return errorMessage(errors);
  }

  protected save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const common = {
      name: (v.name ?? '').trim(),
      description: v.description?.trim() || null,
      receivedOn: toLocalDate(v.receivedOn) as string,
      validUntil: toLocalDate(v.validUntil),
    };
    const request$ = this.data.portfolio
      ? this.api.update(this.data.portfolio.id, common)
      : this.api.create({
          ...common,
          creditorId: v.creditor[0],
          contractId: v.contractId ?? '',
          code: (v.code ?? '').trim(),
        });
    this.saving.set(true);
    this.generalErrors.set([]);
    request$.pipe(finalize(() => this.saving.set(false))).subscribe({
      next: (saved) => {
        this.notifications.success(this.editing ? 'Carteira atualizada.' : 'Carteira cadastrada.');
        this.ref.close(saved);
      },
      error: (err: unknown) =>
        this.generalErrors.set(applyServerErrors(this.form, toApiError(err), { creditorId: 'creditor' })),
    });
  }
}
