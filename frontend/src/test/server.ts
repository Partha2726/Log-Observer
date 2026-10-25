import { setupServer } from 'msw/node';

/** MSW server for page tests; each test adds its own handlers. */
export const server = setupServer();
