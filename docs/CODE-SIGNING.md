# Code signing the Windows exe

Unsigned exes trigger Windows SmartScreen ("Windows protected your PC") for
every downloader. This is reputation-based: an **untrusted signature** warns
just like **no signature**, so self-signing does not solve the problem.

## The two realistic routes

| Route | Cost | Who it fits | Effort |
|---|---|---|---|
| **Certum** Open Source Code Signing (OV) | ~€69/yr | Individual developers | Identity verification, then a cert file + password |
| **Azure Trusted Signing** | $9.99/mo | Anyone with an Entra ID org (individual accounts exist, availability varies by region) | Azure portal setup, then app registration |

Both are OV-grade: SmartScreen reputation builds from downloads over
days-to-weeks, after which the warning disappears. EV certs get instant
reputation but cost more and (for organizations) require hardware keys —
overkill here. Neither is purchasable for you by an agent; identity
verification is yours to do.

## Enabling it in this repo (Azure Trusted Signing)

1. Set up Trusted Signing in the Azure portal; create a certificate profile.
2. Register an app (client) with a client secret that has the
   **Trusted Signing Certificate Profile Signer** role.
3. Add these **repo secrets** (Settings → Secrets and actions):
   - `AZURE_SIGNING_TENANT_ID`
   - `AZURE_SIGNING_CLIENT_ID`
   - `AZURE_SIGNING_CLIENT_SECRET`
   - `AZURE_SIGNING_ENDPOINT` (e.g. `https://eus.codesigning.azure.net/`)
   - `AZURE_SIGNING_ACCOUNT` (your Trusted Signing account name)
   - `AZURE_SIGNING_PROFILE` (your certificate profile name)

That's it — `release.yml` already contains the signing step. It is a no-op
while the secrets are unset and signs the published exe on every tagged
release once they are. Repo secrets cannot be read back out, only overwritten.

## If you choose Certum instead

Replace the `Sign the exe` step with `signtool sign /fd sha256 /tr
http://timestamp.digicert.com /td sha256 /a <exe>` after importing the cert
with the secret-encoded PFX. Open an issue and the workflow can be switched.
