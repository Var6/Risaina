# MongoDB setup

## Local (what this repository was built against)
`brew install mongodb-community` → `brew services start mongodb-community`. The backend's
`.env.example` points at `mongodb://127.0.0.1:27017` with database `risaina_dev`.

## Atlas (recommended for production)
1. Create a free (M0) cluster. Add a database user with readWrite on one database.
2. Network access: allow the backend host's IP (or 0.0.0.0/0 only if the host IP is dynamic and
   you accept the trade-off; the connection is still TLS + password).
3. `MONGODB_URI=mongodb+srv://<user>:<password>@<cluster>/?retryWrites=true&w=majority`,
   `MONGODB_DB=risaina`.

## Indexes and validators
Created automatically at boot by `ensureIndexes()`; the full list with the queries they serve is
in `backend/docs/DATA_MODEL.md`. JSON-schema validators are applied to the main collections.

## Backups
Atlas M0 has no continuous backup; take periodic `mongodump`s if the data matters to you.
