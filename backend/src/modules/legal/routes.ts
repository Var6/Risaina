import { readFileSync } from 'node:fs';
import type { FastifyPluginAsync } from 'fastify';
import { escapeHtml } from '../../services/mailer/templates.js';

/**
 * Who runs Risaina, exactly as on the Google Play listing. Play requires the privacy policy and the
 * account-deletion page to name the developer and give a contact.
 */
export const LEGAL_CONTACT = {
  developerName: '[DEVELOPER NAME]',
  email: '[CONTACT EMAIL]',
} as const;

/** Public pages Google Play links to (store listing, Data safety form, and the app's Settings). */
const PAGES = { '/privacy': 'privacy.html', '/delete-account': 'delete-account.html' } as const;

export function renderLegalPage(file: string): string {
  return readFileSync(new URL(`../../../public/legal/${file}`, import.meta.url), 'utf8')
    .replaceAll('{{DEVELOPER_NAME}}', escapeHtml(LEGAL_CONTACT.developerName))
    .replaceAll('{{CONTACT_EMAIL}}', escapeHtml(LEGAL_CONTACT.email));
}

export const legalRoutes: FastifyPluginAsync = async (app) => {
  for (const [path, file] of Object.entries(PAGES)) {
    const html = renderLegalPage(file); // read once at boot
    app.get(path, { schema: { hide: true } }, async (_req, reply) =>
      reply.type('text/html; charset=utf-8').header('cache-control', 'public, max-age=3600').send(html),
    );
  }
};
