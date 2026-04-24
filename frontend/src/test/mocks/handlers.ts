import { HttpResponse, http } from 'msw';
export const handlers = [
  http.get('/api/v1/auth/me', () =>
    HttpResponse.json({ code: 0, data: { id: 1, username: 'admin', roles: ['ADMIN'] } })
  ),
];
