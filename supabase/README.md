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


## 4. Configure the Android app without committing keys

The app reads these Gradle project properties:

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_your_key
```

On a Windows development machine, put those lines in:

`%USERPROFILE%\\.gradle\\gradle.properties`

On macOS/Linux:

`~/.gradle/gradle.properties`

Do not commit this file or put a Supabase secret/service-role key in the Android app. The publishable key is intended for client applications; Row Level Security is what protects each user's data.

For the GitHub Actions APK build, add two repository Actions secrets:
- `SUPABASE_URL`
- `SUPABASE_PUBLISHABLE_KEY`

In GitHub, open the repository **Settings → Secrets and variables → Actions → New repository secret**. Add each value under its matching name. The Android workflow passes them to Gradle during the build. Never add the Supabase secret/service-role key.

## 5. Authentication and sync implemented in the Android branch

The Android branch now includes:
- Email/password account creation and sign-in through Supabase Auth.
- Session persistence and refresh-token renewal.
- Account-scoped collection reads and upserts.
- Immediate clearing of the visible collection on sign-out.
- One-time migration of an existing locally saved collection into the first account that signs in.

Email confirmation may be required depending on the Supabase Auth settings. If enabled, the user must confirm the email before signing in.
