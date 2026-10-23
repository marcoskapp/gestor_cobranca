import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { SessionView } from '../../../core/auth/auth.models';
import { API_V1 } from '../../../core/config/api';
import { toHttpParams } from '../../../core/http/http-params';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import { CreateUserRequest, UpdateUserRequest, UserFilter, UserView } from './admin.models';

@Injectable({ providedIn: 'root' })
export class UsersApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_V1}/users`;

  search(filter: UserFilter, page: PageRequest): Observable<PageResponse<UserView>> {
    return this.http.get<PageResponse<UserView>>(this.base, {
      params: toHttpParams({ ...filter, page: page.page, size: page.size }),
    });
  }

  get(id: string): Observable<UserView> {
    return this.http.get<UserView>(`${this.base}/${id}`);
  }

  create(request: CreateUserRequest): Observable<UserView> {
    return this.http.post<UserView>(this.base, request);
  }

  update(id: string, request: UpdateUserRequest): Observable<UserView> {
    return this.http.put<UserView>(`${this.base}/${id}`, request);
  }

  assignRoles(id: string, roleIds: string[]): Observable<UserView> {
    return this.http.put<UserView>(`${this.base}/${id}/roles`, { roleIds });
  }

  defineCreditorScope(id: string, allCreditors: boolean, creditorIds: string[]): Observable<UserView> {
    return this.http.put<UserView>(`${this.base}/${id}/creditor-scope`, { allCreditors, creditorIds });
  }

  disable(id: string, reason: string): Observable<UserView> {
    return this.http.post<UserView>(`${this.base}/${id}/disable`, { reason });
  }

  enable(id: string): Observable<UserView> {
    return this.http.post<UserView>(`${this.base}/${id}/enable`, null);
  }

  unlock(id: string): Observable<UserView> {
    return this.http.post<UserView>(`${this.base}/${id}/unlock`, null);
  }

  resetMfa(id: string, reason: string): Observable<UserView> {
    return this.http.post<UserView>(`${this.base}/${id}/mfa/reset`, { reason });
  }

  sendCredentialLink(id: string): Observable<void> {
    return this.http.post<void>(`${this.base}/${id}/credential-link`, null);
  }

  sessions(id: string): Observable<SessionView[]> {
    return this.http.get<SessionView[]>(`${this.base}/${id}/sessions`);
  }

  revokeSessions(id: string, reason: string): Observable<number> {
    return this.http.post<number>(`${this.base}/${id}/sessions/revoke`, { reason });
  }
}
