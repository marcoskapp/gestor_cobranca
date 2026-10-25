import { Provider } from '@angular/core';
import { RestrictionActions } from '../../../features/compliance/restrictions/restriction-actions';
import { FormDialogService } from '../../../features/legal/components/form-dialog';
import { PaymentLauncher } from '../../../features/payment/data/payment-launcher';

/**
 * TRANSITÓRIO — serviços compartilhados entre telas que abrem diálogos do Angular Material.
 * Fornecidos no injetor das rotas do shell (junto de `provideMaterialDefaults()`), e não na raiz,
 * para que usem o `MatDialog` e os padrões do Material daquele injetor sem trazer o Material
 * para o bundle inicial. Nos testes, inclua `LEGACY_DIALOG_LAUNCHERS` nos providers.
 * Cada serviço sai desta lista (volta a `providedIn: 'root'`) quando passar a usar o DialogService.
 */
export const LEGACY_DIALOG_LAUNCHERS: Provider[] = [RestrictionActions, FormDialogService, PaymentLauncher];
