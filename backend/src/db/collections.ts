import type { Collection, Db } from 'mongodb';
import type {
  CoupleDoc,
  DeviceTokenDoc,
  EmailVerificationDoc,
  EventDoc,
  InvitationDoc,
  ListDoc,
  MediaDoc,
  MemoryDoc,
  MessageDoc,
  MilestoneDoc,
  PasswordResetDoc,
  PingDoc,
  SessionDoc,
  UserDoc,
} from './types.js';

export const COLLECTION_NAMES = {
  users: 'users',
  sessions: 'sessions',
  passwordResets: 'passwordResets',
  emailVerifications: 'emailVerifications',
  couples: 'couples',
  invitations: 'invitations',
  pings: 'pings',
  messages: 'messages',
  media: 'media',
  deviceTokens: 'deviceTokens',
  memories: 'memories',
  milestones: 'milestones',
  events: 'events',
  lists: 'lists',
} as const;

export interface Collections {
  users: Collection<UserDoc>;
  sessions: Collection<SessionDoc>;
  passwordResets: Collection<PasswordResetDoc>;
  emailVerifications: Collection<EmailVerificationDoc>;
  couples: Collection<CoupleDoc>;
  invitations: Collection<InvitationDoc>;
  pings: Collection<PingDoc>;
  messages: Collection<MessageDoc>;
  media: Collection<MediaDoc>;
  deviceTokens: Collection<DeviceTokenDoc>;
  memories: Collection<MemoryDoc>;
  milestones: Collection<MilestoneDoc>;
  events: Collection<EventDoc>;
  lists: Collection<ListDoc>;
}

export function getCollections(db: Db): Collections {
  return {
    users: db.collection<UserDoc>(COLLECTION_NAMES.users),
    sessions: db.collection<SessionDoc>(COLLECTION_NAMES.sessions),
    passwordResets: db.collection<PasswordResetDoc>(COLLECTION_NAMES.passwordResets),
    emailVerifications: db.collection<EmailVerificationDoc>(COLLECTION_NAMES.emailVerifications),
    couples: db.collection<CoupleDoc>(COLLECTION_NAMES.couples),
    invitations: db.collection<InvitationDoc>(COLLECTION_NAMES.invitations),
    pings: db.collection<PingDoc>(COLLECTION_NAMES.pings),
    messages: db.collection<MessageDoc>(COLLECTION_NAMES.messages),
    media: db.collection<MediaDoc>(COLLECTION_NAMES.media),
    deviceTokens: db.collection<DeviceTokenDoc>(COLLECTION_NAMES.deviceTokens),
    memories: db.collection<MemoryDoc>(COLLECTION_NAMES.memories),
    milestones: db.collection<MilestoneDoc>(COLLECTION_NAMES.milestones),
    events: db.collection<EventDoc>(COLLECTION_NAMES.events),
    lists: db.collection<ListDoc>(COLLECTION_NAMES.lists),
  };
}
