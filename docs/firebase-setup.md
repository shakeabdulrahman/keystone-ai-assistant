# Firebase setup (free Spark plan, about 10 minutes)

Firebase gives us sign-in (and later push notifications, crash reports and tester builds) without running our own auth server. Everything here is free and needs no credit card.

## 1. Create the project

1. Go to https://console.firebase.google.com and sign in with your Google account.
2. Click **Create a project** (or **Add project**). Name it `keystone-hr` (any name works).
3. Google Analytics: you can switch it **off** for now; we'll revisit analytics in Phase 8.
4. Wait for the project to be created, then click **Continue**.

## 2. Turn on email sign-in

1. In the left menu: **Build → Authentication → Get started**.
2. **Sign-in method** tab → **Email/Password** → switch on the first toggle (Email/Password) → **Save**.
3. **Users** tab → **Add user** → create a test account, e.g. `test@keystone.dev` with a password you'll remember. You'll sign in with it in the app.

## 3. Register the Android app (twice: debug and release)

Debug builds use the application ID `com.keystone.assistant.debug` and release builds `com.keystone.assistant`, so register both:

1. Go to **Project overview** (home icon) → **Add app** → the **Android** icon.
2. Package name: `com.keystone.assistant.debug`, nickname "Keystone debug". Leave SHA-1 empty (email sign-in doesn't need it). Click **Register app**.
3. Skip the download for now: click **Next** through the remaining steps and **Continue to console**.
4. Repeat with package name `com.keystone.assistant`, nickname "Keystone release".
5. Now download the config: **Project settings** (gear icon) → **General** → scroll to **Your apps** → pick either Android app → **google-services.json**. One file covers both apps.
6. Move it to `keystone-ai-assistant/mobile/androidApp/google-services.json`.

`google-services.json` contains identifiers, not passwords, but it is still kept out of git (`.gitignore` already lists it). Access is protected by Firebase's own rules and, from Phase 8, by App Check.

## 4. Note your project ID

**Project settings → General → Project ID** (for example `keystone-hr-1a2b3`). The backend needs it to check sign-in tokens. In Phase 2B we'll put it in `backend/.env`:

```
AUTH_MODE=firebase
FIREBASE_PROJECT_ID=keystone-hr-1a2b3
```

The project ID is not a secret: it's printed inside every token. Only Google holds the private keys that sign tokens; our backend checks signatures with Google's public keys.
