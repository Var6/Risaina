import { type Db, MongoClient } from 'mongodb';

export interface MongoConnection {
  client: MongoClient;
  db: Db;
}

export async function connectMongo(uri: string, dbName: string): Promise<MongoConnection> {
  const client = new MongoClient(uri, {
    serverSelectionTimeoutMS: 10_000,
    maxPoolSize: 20,
    ignoreUndefined: true,
  });
  await client.connect();
  const db = client.db(dbName);
  await db.command({ ping: 1 });
  return { client, db };
}

export async function pingMongo(db: Db): Promise<boolean> {
  try {
    await db.command({ ping: 1 }, { timeoutMS: 2000 });
    return true;
  } catch {
    return false;
  }
}
