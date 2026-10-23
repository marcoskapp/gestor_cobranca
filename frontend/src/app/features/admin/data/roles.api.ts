import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { PermissionView, RoleRequest, RoleView } from './admin.models';

@Injectable({ providedIn: 'root' })
export class RolesApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_V1}/roles`;

  list(): Observable<RoleView[]> {
    return this.http.get<RoleView[]>(this.base);
  }

  permissions(): Observable<PermissionView[]> {
    return this.http.get<PermissionView[]>(`${this.base}/permissions`);
  }

  get(id: string): Observable<RoleView> {
    return this.http.get<RoleView>(`${this.base}/${id}`);
  }

  create(request: RoleRequest): Observable<RoleView> {
    return this.http.post<RoleView>(this.base, request);
  }

  update(id: string, request: RoleRequest): Observable<RoleView> {
    return this.http.put<RoleView>(`${this.base}/${id}`, request);
  }

  setActive(id: string, active: boolean): Observable<RoleView> {
    return this.http.post<RoleView>(`${this.base}/${id}/${active ? 'activate' : 'deactivate'}`, null);
  }
}
