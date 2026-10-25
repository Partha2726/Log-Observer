import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '@/test/server';
import { renderPage } from '@/test/render';
import { RequestPage } from '@/features/requests/RequestPage';
import request from '@/test/contract/request.json';

function open() {
  return renderPage(<RequestPage />, { path: '/requests/:requestId', url: '/requests/0c12301b-e4a1-41e6-8933-897567896a4f' });
}

describe('RequestPage (UI-002)', () => {
  it('links a request in a flow to its trace', async () => {
    server.use(http.get('/api/requests/:id', () => HttpResponse.json(request)));
    open();
    const ref = (request as { flowRef: string }).flowRef;
    expect(await screen.findByRole('link', { name: `Open flow ${ref}` })).toBeInTheDocument();
  });

  it('says when a request belongs to no flow, and shows errors', async () => {
    server.use(http.get('/api/requests/:id', () => HttpResponse.json({ ...request, flowRef: null, flowRefs: [] })));
    const view = open();
    expect(await screen.findByText('This request is not part of a flow')).toBeInTheDocument();
    view.unmount();

    server.use(http.get('/api/requests/:id', () => HttpResponse.json({ code: 'NOT_FOUND', status: 404, message: 'No lines carry request ID' }, { status: 404 })));
    open();
    expect(await screen.findByRole('alert')).toHaveTextContent('No lines carry request ID');
  });
});
