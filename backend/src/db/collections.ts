import type { Collection, Db } from 'mongodb';
import type {
  CoupleDoc,
  DeviceTokenDoc,
  EventDoc,
  InvitationDoc,
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
  couples: 'couples',
  invitations: 'invitations',
  pings: 'pings',
  messages: 'messages',
  media: 'media',
  deviceTokens: 'deviceTokens',
  memories: 'memories',
  milestones: 'milestones',
  events: 'events',
} as const;

export interface Collections {
  users: Collection<UserDoc>;
  sessions: Collection<SessionDoc>;
  passwordResets: Collection<PasswordResetDoc>;
  couples: Collection<CoupleDoc>;
  invitations: Collection<InvitationDoc>;
  pings: Collection<PingDoc>;
  messages: Collection<MessageDoc>;
  media: Collection<MediaDoc>;
  deviceTokens: Collection<DeviceTokenDoc>;
  memories: Collection<MemoryDoc>;
  milestones: Collection<MilestoneDoc>;
  events: Collection<EventDoc>;
}

export function getCollections(db: Db): Collections {
  return {
    users: db.collection<UserDoc>(COLLECTION_NAMES.users),
    sessions: db.collection<SessionDoc>(COLLECTION_NAMES.sessions),
    passwordResets: db.collection<PasswordResetDoc>(COLLECTION_NAMES.passwordResets),
    couples: db.collection<CoupleDoc>(COLLECTION_NAMES.couples),
    invitations: db.collection<InvitationDoc>(COLLECTION_NAMES.invitations),
    pings: db.collection<PingDoc>(COLLECTION_NAMES.pings),
    messages: db.collection<MessageDoc>(COLLECTION_NAMES.messages),
    media: db.collection<MediaDoc>(COLLECTION_NAMES.media),
    deviceTokens: db.collection<DeviceTokenDoc>(COLLECTION_NAMES.deviceTokens),
    memories: db.collection<MemoryDoc>(COLLECTION_NAMES.memories),
    milestones: db.collection<MilestoneDoc>(COLLECTION_NAMES.milestones),
    events: db.collection<EventDoc>(COLLECTION_NAMES.events),
  };
}
