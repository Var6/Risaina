/**
 * Plain, warm, short transactional emails (HTML + text). Only values we generate (codes, links,
 * minutes) and an HTML-escaped display name are interpolated.
 */

export interface RenderedEmail {
  subject: string;
  html: string;
  text: string;
}

export function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function minutesUntil(expiresAt: Date, now: Date): number {
  return Math.max(1, Math.round((expiresAt.getTime() - now.getTime()) / 60_000));
}

function layout(bodyHtml: string): string {
  return `<!doctype html>
<html lang="en">
  <body style="margin:0;padding:24px;background:#faf7f5;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#2b2b2b;">
    <div style="max-width:480px;margin:0 auto;background:#ffffff;border-radius:12px;padding:28px;">
      ${bodyHtml}
      <p style="margin:24px 0 0;font-size:13px;color:#777;">With love,<br>Risaina</p>
    </div>
  </body>
</html>`;
}

export function verificationEmail(
  input: { code: string; displayName: string; expiresAt: Date },
  now: Date = new Date(),
): RenderedEmail {
  const minutes = minutesUntil(input.expiresAt, now);
  const name = input.displayName.trim() || 'there';
  const subject = `Your Risaina code: ${input.code}`;
  const text = [
    `Hi ${name},`,
    '',
    'Welcome to Risaina. Here is your code to confirm this email address:',
    '',
    `    ${input.code}`,
    '',
    `It works for ${minutes} minutes. If you did not create a Risaina account, you can ignore this email.`,
    '',
    'With love,',
    'Risaina',
  ].join('\n');
  const html = layout(`
      <p style="margin:0 0 12px;font-size:16px;">Hi ${escapeHtml(name)},</p>
      <p style="margin:0 0 16px;font-size:15px;line-height:1.5;">Welcome to Risaina. Here is your code to confirm this email address:</p>
      <p style="margin:0 0 16px;font-size:30px;letter-spacing:6px;font-weight:600;">${escapeHtml(input.code)}</p>
      <p style="margin:0;font-size:14px;line-height:1.5;color:#555;">It works for ${minutes} minutes. If you did not create a Risaina account, you can ignore this email.</p>`);
  return { subject, html, text };
}

export function passwordResetEmail(
  input: { resetUrl: string; expiresAt: Date },
  now: Date = new Date(),
): RenderedEmail {
  const minutes = minutesUntil(input.expiresAt, now);
  const subject = 'Reset your Risaina password';
  const text = [
    'Hi,',
    '',
    'Someone (hopefully you) asked to reset the password for your Risaina account.',
    'Open this link to choose a new one:',
    '',
    input.resetUrl,
    '',
    `The link works once, for ${minutes} minutes. If you did not ask for this, ignore this email; your password stays the same.`,
    '',
    'With love,',
    'Risaina',
  ].join('\n');
  const url = escapeHtml(input.resetUrl);
  const html = layout(`
      <p style="margin:0 0 12px;font-size:16px;">Hi,</p>
      <p style="margin:0 0 16px;font-size:15px;line-height:1.5;">Someone (hopefully you) asked to reset the password for your Risaina account.</p>
      <p style="margin:0 0 16px;"><a href="${url}" style="display:inline-block;background:#c2185b;color:#ffffff;text-decoration:none;padding:12px 20px;border-radius:8px;font-size:15px;">Choose a new password</a></p>
      <p style="margin:0 0 8px;font-size:13px;line-height:1.5;color:#555;">Or paste this link into your browser:<br><span style="word-break:break-all;">${url}</span></p>
      <p style="margin:0;font-size:14px;line-height:1.5;color:#555;">The link works once, for ${minutes} minutes. If you did not ask for this, ignore this email; your password stays the same.</p>`);
  return { subject, html, text };
}
