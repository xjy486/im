# Jitong macOS Desktop client

The desktop client is a Compose Desktop JVM application. It keeps one independent H2 AES database per account and stores the generated database key through a Keychain-backed `Keychain` port. The default implementation uses the macOS `security` CLI and refuses to silently fall back to plaintext storage.

## Run

```sh
./gradlew run
```

The server URL defaults to `https://127.0.0.1:8443`. Set `JITONG_SERVER_URL` to a trusted development or production endpoint to override it.

## Verify

```sh
./gradlew test
./gradlew packageDistributionForCurrentOS
```

The desktop client also supports exact account and group discovery, approval-gated group join requests, group member/role management, group invites, contact removal/blocking, one-to-one and group conversation history, real-time text and image messages, SYSTEM/MODERATED tombstones, user-level read progress, and durable per-account sync cursors. Local history remains available while offline, while the composer is disabled and clearly reports that sending requires a connection.

Private AI summaries, editable reply drafts, extracted facts, and action items use the same server-owned consent, group policy, budget, context, and ownership rules as mobile. AI jobs and results are mirrored through the user's sync stream into the per-account encrypted database. Drafts are only copied into the normal composer and are never published automatically.
