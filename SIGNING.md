# Private build signing

GitHub Actions release builds use one fixed private signing key from repository secrets.

Keep these secrets unchanged to make every future APK upgrade-compatible:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Do not commit `keystore/release.keystore` to the repository. The `keystore/` folder is intentionally ignored by Git.

To create the base64 secret from a local keystore on Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("keystore\release.keystore"))
```

Use the output as `RELEASE_KEYSTORE_BASE64`.
