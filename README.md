# Findly — Digital Lost & Found

A responsive lost-and-found community website with password and one-time-code sign-in, registration, and an interactive dashboard. The Java backend uses only the JDK; no Maven or external libraries are required.

## Run locally

1. Install a JDK (Java 17 or newer).
2. From this folder, run `run.cmd` on Windows, or `powershell -ExecutionPolicy Bypass -File .\run.ps1` if script execution is blocked.
3. Open [http://localhost:8080](http://localhost:8080).

The first run creates a `data` folder for registered accounts. Passwords are stored as salted PBKDF2 hashes. Dashboard items posted during a run are held in memory; the example community items are seeded at startup.

## Sign-in options

- **Password:** Register with an email and a password of at least 8 characters, then sign in with that email and password.
- **One-time code:** Register an Indian mobile number (for example, `+919876543210`), select the one-time-code tab, and request a six-digit code for that phone.
- **MSG91 SMS:** Set `MSG91_AUTH_KEY` and `MSG91_TEMPLATE_ID` before starting the server. The flow template must be approved for India and contain a variable matching `MSG91_OTP_VARIABLE` (defaults to `VAR1`); configure the matching DLT sender ID and approved template in MSG91. The app sends the code through the [MSG91 Flow API](https://docs.msg91.com/sms/send-sms) and never returns it in the browser response. See MSG91's [template and DLT setup guide](https://msg91.com/help/template/how-to-create-flow-id-to-send-sms-via-api).
- **Local demo OTP:** When MSG91 credentials are not set, demo mode prints the OTP to the server console and includes it in the response. Disable demo mode with `-Dapp.devOtp=false`; OTP requests then fail clearly until SMS credentials are configured.

In PowerShell, set the variables in the same terminal before starting the app:

```powershell
$env:MSG91_AUTH_KEY = "your-msg91-auth-key"
$env:MSG91_TEMPLATE_ID = "your-approved-template-id"
$env:MSG91_OTP_VARIABLE = "VAR1"
powershell -ExecutionPolicy Bypass -File .\run.ps1
# or just: .\run.cmd
```

Never commit or share the MSG91 auth key. For local setup, enter it only in your own PowerShell terminal. To send Indian SMS, finish any MSG91/telecom DLT entity, sender ID, and template approvals required for your account.

Set the `PORT` environment variable to use a port other than `8080`.

## Production note

This starter is for local development and demos. It does not provide persistent item storage, HTTPS termination, CSRF defenses, or production account protections such as rate limiting. Add those before deploying it publicly.
