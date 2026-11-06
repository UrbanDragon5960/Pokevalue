# Supabase setup for PokeValue

This project uses the Supabase project named **PokeValue** in the Europe region.

## 1. Create the collection table

Open the Supabase project, choose **SQL Editor**, create a new query, paste the contents of `supabase/migrations/001_create_collections.sql`, and run it.

The migration creates `public.collections`, links every row to `auth.users`, enables row-level security, and restricts each signed-in user to their own rows.

## 2. App configuration (next implementation step)

PokeValue needs the Supabase Project URL and the **publishable** key. The publishable key is safe to include in a mobile client when row-level security is correctly configured. Never put a secret/service-role key in the Android app or GitHub.

The Android integration will read these values from local build configuration rather than committing credentials to the repository.

## 3. Expected behaviour

- Signed-out: no account collection is displayed.
- Signed-in: load only rows belonging to the authenticated user.
- Add/update card: save it under the authenticated user's ID.
- Sign-out: clear the in-memory collection immediately.
- Sign back in: reload that account's collection from Supabase.

## Important

The SQL migration does not create user accounts and does not add test cards. Authentication and Android synchronisation are separate steps. Do not insert a made-up user ID; rows must use a real Supabase Auth user ID.
