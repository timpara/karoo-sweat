# Signing and the release keystore

The app is signed with an RSA-4096 key created 2026-08-15.

- **Key alias:** `karoo-sweat`
- **SHA-256:** `0B:B4:F6:81:F2:9C:8C:99:73:9F:AF:B0:BE:E1:C1:48:97:A3:3E:49:3F:C3:95:A3:2B:C4:42:AA:7F:EE:A5:15`

## Why the key must never be lost

Android installs an update only if it is signed with the identical key. Lose it and
every existing user must uninstall and reinstall to move to a new version. There is no
recovery for this on the platform side.

## Where the key lives

1. **Password manager** — the authoritative backup, held by the maintainer, as a
   base64 export of the `.jks` plus its password. This is the copy of record.
2. **GitHub repository secrets** — `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
   `KEY_PASSWORD`, `KEY_ALIAS`. Used by the release workflow to sign tagged builds.
3. **`~/.config/karoo-sweat/`** on the maintainer's machine — `karoo-sweat.jks` and
   `keystore-password.txt`, for producing signed builds locally.

The keystore is **never** committed; `.gitignore` blocks `*.jks`, `*.keystore` and the
password files.

## Building a signed release locally

If `~/.config/karoo-sweat/karoo-sweat.jks` and `keystore-password.txt` are present,
`./gradlew :app:assembleRelease` picks them up automatically and produces a signed
`app/build/outputs/apk/release/app-release.apk`. With no keystore available the release
build is left unsigned, so contributors and forks can still build.

Verify a build:

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
# SHA-256 digest must be 0bb4f681f29c8c99739fafb0bee1c14897a33e493fc395a32bc442aa7feea515
```

## Rotating GitHub secrets from the local keystore

```bash
base64 -w0 ~/.config/karoo-sweat/karoo-sweat.jks | gh secret set KEYSTORE_BASE64 -R timpara/karoo-sweat
PW=$(cat ~/.config/karoo-sweat/keystore-password.txt)
printf '%s' "$PW" | gh secret set KEYSTORE_PASSWORD -R timpara/karoo-sweat
printf '%s' "$PW" | gh secret set KEY_PASSWORD -R timpara/karoo-sweat
printf 'karoo-sweat' | gh secret set KEY_ALIAS -R timpara/karoo-sweat
```
