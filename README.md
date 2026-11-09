# health-connect-sync-android

A small Android app that reads your **Health Connect** data and POSTs it to **your own server**. It's the phone-side companion
of [health-connect-mcp](https://github.com/weissmike/health-connect-mcp), but it speaks plain JSON over HTTPS, so any endpoint that accepts it will work.

- Read-only access to Health Connect. Nothing leaves the phone except to the URL you configure, with your bearer token.
- 23 record types: weight, body fat, lean body mass, height, BMR, steps, distance, floors, active and total calories,
  exercise sessions, sleep (with stages), heart rate (summarized per record), resting heart rate, HRV, SpO2, respiratory rate,
  blood pressure, blood glucose, body temperature, VO2 max, nutrition, hydration.
- Sync on demand, or every 6 hours in the background (WorkManager).
- Idempotent: every record carries Health Connect's own id, so re-syncing never duplicates and edits to recent records are picked up.
- Minimal: one screen, no analytics, no third-party services, about 550 lines of Kotlin.

> Unofficial and personal-use. It's a debug-signed app you build and install yourself. It is not on the Play Store.

## Payload

One POST per record type per batch (up to 300 records), `Authorization: Bearer <token>`:

```json
{
  "timestamp": "2026-11-09T12:00:00-05:00",
  "app_version": "hcsync/0.1.0",
  "timezone": "America/Detroit",
  "weight": [
    {
      "time": "2026-11-09T07:00:00-05:00",
      "kilograms": 88.2,
      "metadata": { "id": "…", "data_origin": "com.example.scale", "last_modified_time": "2026-11-09T12:01:02Z" }
    }
  ]
}
```

Interval types (steps, sleep, exercise, nutrition, ...) use `start_time` and `end_time`; sleep adds `session_end_time`,
`duration_seconds` and `stages`. See `app/src/main/java/dev/hcsync/Specs.kt` for every type and field.

## Build and install

You need JDK 17 and the Android SDK (platform 36, build tools 36). Point `local.properties` at the SDK
(`sdk.dir=C:/Users/you/AppData/Local/Android/Sdk`), then:

```sh
./gradlew assembleDebug                       # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Configure

Either type the URL and token into the app, or push them over USB so the token never touches the phone's keyboard:

```powershell
.\scripts\provision.ps1 -Url https://your-host/ingest -TokenFile C:\path\to\ingest_token.txt
```

The script writes a one-time config file into the app's private storage (this works because it's a debug build); the app imports
it on launch and deletes it. The token is shown in the app only as its last four characters.

Then, on the phone: **1. Grant Health Connect permissions** (the app requests read access only), **2. Sync now**, and optionally
**3. Enable background sync**. Background reads also need the "background" permission from step 1.

## How syncing works

The first sync reads the last N days (default 30; "history" access is requested for anything older). Later syncs re-read from 48 hours
before the last successful sync. The overlap is deliberate: the server upserts by record id. If any type fails, the full window is retried
next time.

## Limitations

- Deletions in Health Connect are not propagated (it re-reads windows rather than using the Changes API).
- Heart rate is summarized (count, min, avg, max) per Health Connect record, not sent sample by sample.
- Debug build only: the token lives in app-private storage, which is not encrypted at rest beyond Android's own file encryption.
- Health Connect's per-app permission limits apply: without "history", reads older than 30 days before the first grant are not returned.

## Built on

- [mcnaveen/health-connect-webhook](https://github.com/mcnaveen/health-connect-webhook) (AGPL-3.0): the existing app that does this job, and the model for the
  payload shape. No code from it is used here.
- [AndroidX Health Connect client](https://developer.android.com/jetpack/androidx/releases/health-connect), [WorkManager](https://developer.android.com/jetpack/androidx/releases/work),
  [activity-ktx](https://developer.android.com/jetpack/androidx/releases/activity) (Apache-2.0) and
  [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) (Apache-2.0).
- [Android Health Connect](https://developer.android.com/health-and-fitness/guides/health-connect), the platform this reads.

See [NOTICE.md](NOTICE.md).

## License

MIT, see [LICENSE](LICENSE).
