import type { FastifyPluginAsync } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import type { AppContext } from '../../app-context.js';
import { ErrorEnvelope, NoContent } from '../shared/schemas.js';
import { EmailVerificationService } from './email-verification.js';
import {
  AcceptedResponse,
  AuthResponse,
  ForgotBody,
  GoogleAuthResponse,
  GoogleBody,
  LoginBody,
  LogoutBody,
  RefreshBody,
  RegisterBody,
  ResetBody,
  TokensResponse,
  UserOnlyResponse,
  VerifyEmailBody,
} from './schemas.js';
import { AuthService } from './service.js';

const TAG = 'Auth';

export const authRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new AuthService(ctx);
  const verification = new EmailVerificationService(ctx);
  const security = [{ bearerAuth: [] }];
  const tight = { rateLimit: { max: ctx.env.rateLimit.authMax, timeWindow: ctx.env.rateLimit.authWindowMs } };

  r.post(
    '/auth/register',
    {
      config: tight,
      schema: {
        tags: [TAG],
        summary: 'Create an account',
        body: RegisterBody,
        response: { 201: AuthResponse, 400: ErrorEnvelope, 409: ErrorEnvelope, 429: ErrorEnvelope },
      },
    },
    async (req, reply) => {
      const result = await service.register(req.body, { userAgent: req.headers['user-agent'] ?? null });
      return reply.code(201).send(result);
    },
  );

  r.post(
    '/auth/login',
    {
      config: tight,
      schema: {
        tags: [TAG],
        summary: 'Sign in with email and password',
        body: LoginBody,
        response: { 200: AuthResponse, 401: ErrorEnvelope, 429: ErrorEnvelope },
      },
    },
    async (req) => service.login(req.body, { userAgent: req.headers['user-agent'] ?? null }),
  );

  r.post(
    '/auth/google',
    {
      config: tight,
      schema: {
        tags: [TAG],
        summary: 'Sign in (or sign up) with a Google ID token',
        description:
          'Verifies the ID token against Google keys (issuer accounts.google.com, audience in GOOGLE_CLIENT_IDS, ' +
          'email_verified must be true). Resolves by Google sub, then by email (linking; an unverified ' +
          'password account loses its password and sessions), else creates an account. Always 200; ' +
          '`isNewUser` tells whether the account was just created.',
        body: GoogleBody,
        response: {
          200: GoogleAuthResponse,
          400: ErrorEnvelope,
          401: ErrorEnvelope,
          409: ErrorEnvelope,
          429: ErrorEnvelope,
          503: ErrorEnvelope,
        },
      },
    },
    async (req) => service.googleSignIn(req.body.idToken, { userAgent: req.headers['user-agent'] ?? null }),
  );

  r.post(
    '/auth/email/verify',
    {
      preValidation: [app.authenticate],
      config: tight,
      schema: {
        tags: [TAG],
        summary: 'Confirm the account email with the 6-digit code',
        description:
          'Idempotent once verified. Wrong code: 400 verification_code_invalid (details.attemptsRemaining); ' +
          'the 5th wrong code kills it: 429 verification_attempts_exceeded; expired or none: 410 verification_code_expired.',
        security,
        body: VerifyEmailBody,
        response: {
          200: UserOnlyResponse,
          400: ErrorEnvelope,
          401: ErrorEnvelope,
          410: ErrorEnvelope,
          429: ErrorEnvelope,
        },
      },
    },
    async (req) => verification.verify(req.user!.id, req.body.code),
  );

  r.post(
    '/auth/email/resend',
    {
      preValidation: [app.authenticate],
      config: tight,
      schema: {
        tags: [TAG],
        summary: 'Email a new verification code (1 per 60 s, 5 per hour)',
        security,
        response: { 202: AcceptedResponse, 401: ErrorEnvelope, 409: ErrorEnvelope, 429: ErrorEnvelope },
      },
    },
    async (req, reply) => {
      await verification.resend(req.user!.id);
      return reply.code(202).send({ accepted: true });
    },
  );

  r.post(
    '/auth/refresh',
    {
      config: {
        rateLimit: { max: ctx.env.rateLimit.authMax * 3, timeWindow: ctx.env.rateLimit.authWindowMs },
      },
      schema: {
        tags: [TAG],
        summary: 'Rotate a refresh token',
        description:
          'Every refresh token is single use. Presenting a token that was already rotated revokes the whole session family, ' +
          'except within 60 s from the same deviceId while the replacement session is still unused (a response lost to a ' +
          'killed app): then a fresh pair is issued and the unused replacement is revoked.',
        body: RefreshBody,
        response: { 200: TokensResponse, 401: ErrorEnvelope, 429: ErrorEnvelope },
      },
    },
    async (req) =>
      service.refresh(req.body.refreshToken, {
        userAgent: req.headers['user-agent'] ?? null,
        deviceId: req.body.deviceId ?? null,
      }),
  );

  r.post(
    '/auth/logout',
    {
      config: {
        rateLimit: { max: ctx.env.rateLimit.authMax * 3, timeWindow: ctx.env.rateLimit.authWindowMs },
      },
      schema: {
        tags: [TAG],
        summary: 'Revoke the session (and that device push token)',
        body: LogoutBody,
        response: { 204: NoContent },
      },
    },
    async (req, reply) => {
      await service.logout(req.body.refreshToken, req.body.deviceId);
      return reply.code(204).send(null);
    },
  );

  r.post(
    '/auth/password/forgot',
    {
      config: tight,
      schema: {
        tags: [TAG],
        summary: 'Request a password reset (always 202)',
        body: ForgotBody,
        response: { 202: AcceptedResponse, 429: ErrorEnvelope },
      },
    },
    async (req, reply) => {
      await service.forgotPassword(req.body.email);
      return reply.code(202).send({ accepted: true });
    },
  );

  r.post(
    '/auth/password/reset',
    {
      config: tight,
      schema: {
        tags: [TAG],
        summary: 'Reset the password with a token from the reset email',
        body: ResetBody,
        response: { 204: NoContent, 400: ErrorEnvelope, 429: ErrorEnvelope },
      },
    },
    async (req, reply) => {
      await service.resetPassword(req.body.token, req.body.newPassword);
      return reply.code(204).send(null);
    },
  );
};
