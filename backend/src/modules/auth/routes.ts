import type { FastifyPluginAsync } from 'fastify';
import type { ZodTypeProvider } from 'fastify-type-provider-zod';
import { z } from 'zod';
import type { AppContext } from '../../app-context.js';
import { ErrorEnvelope, NoContent } from '../shared/schemas.js';
import {
  AuthResponse,
  ForgotBody,
  LoginBody,
  LogoutBody,
  RefreshBody,
  RegisterBody,
  ResetBody,
  TokensResponse,
} from './schemas.js';
import { AuthService } from './service.js';

const TAG = 'Auth';

export const authRoutes: FastifyPluginAsync<AppContext> = async (app, ctx) => {
  const r = app.withTypeProvider<ZodTypeProvider>();
  const service = new AuthService(ctx);
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
    '/auth/refresh',
    {
      config: {
        rateLimit: { max: ctx.env.rateLimit.authMax * 3, timeWindow: ctx.env.rateLimit.authWindowMs },
      },
      schema: {
        tags: [TAG],
        summary: 'Rotate a refresh token',
        description:
          'Every refresh token is single use. Presenting a token that was already rotated revokes the whole session family.',
        body: RefreshBody,
        response: { 200: TokensResponse, 401: ErrorEnvelope, 429: ErrorEnvelope },
      },
    },
    async (req) => service.refresh(req.body.refreshToken, { userAgent: req.headers['user-agent'] ?? null }),
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
        response: { 202: z.object({ accepted: z.literal(true) }), 429: ErrorEnvelope },
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
