# Klasse Android App

WebView wrapper for [klasse.netlify.app](https://klasse.netlify.app). Full-screen, no browser chrome, domain whitelist enforced. Supports **Firebase push notifications**.

---

## Domain Whitelist

Edit `ALLOWED_HOSTS` in `app/src/main/java/com/klasse/app/MainActivity.kt`:

```kotlin
val ALLOWED_HOSTS = setOf(
    "klasse.netlify.app",
    "netlify.app",
    "supabase.co",
    "supabase.com",
    "accounts.google.com",
    "github.com"
)
```

External links not in this list open in the system browser.

---

## Push Notifications (Firebase)

### 1. Create a Firebase project

1. Go to [console.firebase.google.com](https://console.firebase.google.com) → **Add project**.
2. Add an Android app with package name **`com.klasse.app`**.
3. Download **`google-services.json`** and place it at `app/google-services.json`.
   - ⚠️ This file is gitignored — do NOT commit it. Upload it to Codemagic as a secret file (see below).

### 2. Send a test notification

In Firebase Console → **Cloud Messaging** → **Send your first message** → target your device or all users.

**Or via HTTP (server-to-device):**

```bash
curl -X POST https://fcm.googleapis.com/v1/projects/YOUR_PROJECT_ID/messages:send \
  -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  -H "Content-Type: application/json" \
  -d '{
    "message": {
      "topic": "all",
      "notification": { "title": "Hallo!", "body": "Neue Nachricht in Klasse." },
      "data": { "url": "https://klasse.netlify.app/chat" }
    }
  }'
```

The `data.url` field is optional — if set, tapping the notification opens that page directly.

### 3. Subscribe all users to a topic (optional)

Call this from your web app's JS once the user logs in:

```js
// This only works inside the Android WebView via a JS bridge — or handle it server-side via FCM API.
```

---

## Build with Codemagic

### 1. Push to GitHub

```bash
cd klasse-android
git init
git add .
git commit -m "Initial Klasse Android app"
git remote add origin https://github.com/YOUR_USERNAME/klasse-android.git
git push -u origin main
```

### 2. Connect Codemagic

1. Go to [codemagic.io](https://codemagic.io) → **Add application** → select your GitHub repo.
2. Choose **"codemagic.yaml"** as the workflow config.
3. Update `email.recipients` in `codemagic.yaml` with your email.

### 3. Add `google-services.json` as a Codemagic secret file

1. Codemagic → **Teams → Environment variables** → **Files** tab.
2. Upload your `google-services.json`, name the variable `GOOGLE_SERVICES_JSON`.
3. Add this step to `codemagic.yaml` **before** the build step:
   ```yaml
   - name: Install google-services.json
     script: |
       echo $GOOGLE_SERVICES_JSON > "$CM_BUILD_DIR/app/google-services.json"
   ```
   *(already included in the codemagic.yaml in this repo)*

### 4. Set up signing

1. Generate keystore (one-time):
   ```bash
   keytool -genkey -v -keystore klasse.jks -alias klasse -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Codemagic → **Teams → Code signing → Android** → upload `klasse.jks`, name it **`klasse_keystore`**.
3. Add env vars: `CM_KEYSTORE_PASSWORD`, `CM_KEY_ALIAS`, `CM_KEY_PASSWORD`.

Push to `main` → Codemagic builds and emails you the signed APK.

---

## Local Development

1. Place your `google-services.json` in `app/google-services.json`.
2. Open in **Android Studio** → wait for Gradle sync.
3. Run on emulator or device.

The FCM token is logged to Logcat (`KlasseFCM` tag) on first launch — useful for testing.

---

## Feature Summary

| Feature | Detail |
|---|---|
| Target URL | `https://klasse.netlify.app` |
| Min Android | 7.0 (API 24) |
| Push notifications | Firebase Cloud Messaging (FCM) |
| Notification tap | Opens app at `data.url` or home |
| JavaScript | Enabled |
| Pull-to-refresh | Enabled |
| Offline page | German-language fallback |
| Back navigation | In-WebView history first |
| Browser UI | Hidden (full-screen) |
| User-Agent | Spoofed to Chrome Mobile |
| External links | Open in system browser |
