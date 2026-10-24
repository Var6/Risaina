# Cloudflare R2 setup

Development runs with `STORAGE_DRIVER=local` (files under `backend/.data/storage`, signed URLs
served by the API). Production uses a private R2 bucket.

1. Cloudflare dashboard → R2 → Create bucket, e.g. `risaina-media`. Keep it **private** (no
   public access, no custom domain required).
2. R2 → Manage R2 API tokens → Create token with **Object Read & Write** scoped to that bucket.
3. Backend environment:
   ```
   STORAGE_DRIVER=r2
   R2_ACCOUNT_ID=<account id>
   R2_ACCESS_KEY_ID=<token access key>
   R2_SECRET_ACCESS_KEY=<token secret>
   R2_BUCKET=risaina-media
   R2_ENDPOINT=https://<account id>.r2.cloudflarestorage.com
   ```
4. CORS on the bucket is **not** needed: uploads are direct PUTs from the Android app (not a
   browser).
5. Lifecycle: the backend deletes objects when messages/memories/couples are deleted and cleans
   abandoned `pending` uploads after 24 h. Optionally add an R2 lifecycle rule to abort incomplete
   multipart uploads after 1 day.
6. Verify: `GET /api/v1/health` reports `storage.driver = "r2"`; upload a photo from the app and
   confirm the object under `couples/<coupleId>/photo/` in the bucket.

Object keys are always generated server-side as `couples/{coupleId}/{category}/{mediaId}.{ext}`.
