import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { TenantView } from '../../organization/data/organization.models';

/** `PlatformTenantController.ProvisionRequest` */
export interface ProvisionTenantRequest {
  slug: string;
  legalName: string;
  tradeName: string;
  cnpj: string;
  adminName: string;
  adminEmail: string;
}

/** `TenantProvisioning.ProvisionedTenant` */
export interface ProvisionedTenant {
  tenant: TenantView;
  adminUserId: string;
}

@Injectable({ providedIn: 'root' })
export class PlatformTenantsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_V1}/platform/tenants`;

  list(): Observable<TenantView[]> {
    return this.http.get<TenantView[]>(this.base);
  }

  provision(request: ProvisionTenantRequest): Observable<ProvisionedTenant> {
    return this.http.post<ProvisionedTenant>(this.base, request);
  }

  suspend(id: string, reason: string): Observable<TenantView> {
    return this.http.post<TenantView>(`${this.base}/${id}/suspend`, { reason });
  }

  reactivate(id: string, reason: string): Observable<TenantView> {
    return this.http.post<TenantView>(`${this.base}/${id}/reactivate`, { reason });
  }
}
