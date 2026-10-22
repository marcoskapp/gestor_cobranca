import { FormBuilder, FormControl, FormGroup } from '@angular/forms';
import { AppValidators } from '../../shared/validation/validators';

export type NewPasswordForm = FormGroup<{
  newPassword: FormControl<string>;
  confirmation: FormControl<string>;
}>;

/** Nova senha + confirmação, com a política de senha do backend. */
export function buildNewPasswordForm(
  fb: FormBuilder,
  personal?: () => { name?: string | null; email?: string | null },
): NewPasswordForm {
  return fb.nonNullable.group(
    {
      newPassword: ['', [AppValidators.notBlank, AppValidators.password(personal)]],
      confirmation: ['', [AppValidators.notBlank]],
    },
    { validators: AppValidators.matching('newPassword', 'confirmation') },
  );
}
