# Jitong macOS Desktop client

The desktop client is a Compose Desktop JVM application. It keeps one independent H2 AES database per account and stores the generated database key through a Keychain-backed `Keychain` port. The default implementation uses the macOS `security` CLI and refuses to silently fall back to plaintext storage.

## Run

```sh
./gradlew run
```

The server URL defaults to `http://127.0.0.1:8080`. Set `JITONG_SERVER_URL` to override it.

## Verify

```sh
./gradlew test
./gradlew packageDmg
```

The app is intentionally small at this milestone: it covers login, refresh-on-restart, same-class replacement confirmation, normal logout retention, and untrusted-device erasure. Message and synchronization screens are delivered by later tickets.
