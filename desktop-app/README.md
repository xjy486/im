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
./gradlew packageDmg
```

The desktop client also supports exact account search, contact requests, contact removal/blocking, one-to-one conversation history, real-time text messages, user-level read progress, and durable per-account sync cursors. Local history remains available while offline, while the composer is disabled and clearly reports that sending requires a connection.
