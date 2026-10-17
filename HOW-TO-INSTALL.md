# «Світло» — how to get the app on your phone

The app is finished as code. GitHub (a free website) turns it into an installable file called **svitlo.apk**.
You do Part 1 once, which takes about 10 minutes. After that, getting a new version takes 2 minutes.

---

## Part 1 — Put the project on GitHub (on your PC)

1. Go to **https://github.com/signup** and create a free account. Confirm your email.
2. At the top right, click **+** → **New repository**.
   - Repository name: `svitlo-widget`
   - Choose **Public**. This lets your phone download the app without logging in.
     The project doesn't contain your address. Your address is saved only on your phone.
   - Click **Create repository**.
3. On the page that opens, click the small link **"uploading an existing file"**.
4. Open the folder `D:\AI Apps\Electricity Widget` in File Explorer.
   Press **Ctrl+A** to select everything, including the `.github`, `app` and `keystore` folders.
   **Drag** it all into the browser window.
5. Wait until the file list appears, then click the green **Commit changes** button.
6. Click the **Actions** tab at the top.
   - If GitHub asks you to enable workflows, click the green button.
   - You'll see **Build APK** running (yellow circle). Wait about 3–5 minutes.
   - A **green ✓** means it worked. A **red ✗** means it failed: send me a screenshot and I'll fix it.
     I couldn't test-build on this PC, so a fix on the first try is possible.

## Part 2 — Install on your Android phone

1. On your phone, open this address in your browser (replace `YOUR-NAME` with your GitHub username):
   `https://github.com/YOUR-NAME/svitlo-widget/releases/latest`
2. Tap **svitlo.apk** to download it, then open the downloaded file.
3. Android will warn that it can't install apps from this source. Tap **Settings** → allow
   **Install unknown apps** for your browser → go back → **Install**.
   If Google Play Protect warns that the app is unknown, tap **More details → Install anyway**.
   Play Protect warns about every app that doesn't come from the Play Store.
4. Open the **Світло** app:
   - Allow notifications.
   - Tap **+ Додати адресу** → Новобузька дільниця → Новий Буг → вул. Українська → 1 → **Зберегти**.
   - Add more addresses the same way if you want.
   - Tap **Дозволити роботу у фоні** → **Allow**. Without this, notifications can arrive late.
5. Add the widget: press and hold an empty spot on your home screen → **Widgets** → find **Світло** → drag it onto the screen.
   - **⇄** on the widget switches to your next saved address.
   - **⟳** refreshes the data.
   - Tapping anywhere else on the widget opens the app.

### Phone-specific tips (for reliable notifications)
- **Xiaomi / Redmi / POCO:** Settings → Apps → Світло → turn on **Autostart**, and set Battery saver to **No restrictions**.
- **Samsung:** Settings → Apps → Світло → Battery → **Unrestricted**.
- **Huawei / Honor:** Settings → Battery → App launch → Світло → **Manage manually**, and turn everything on.

## Getting a new version later
1. On GitHub, open your `svitlo-widget` project → **Add file → Upload files** → drag in the changed files → **Commit changes**.
2. Wait for the green ✓ in **Actions**.
3. On your phone, open the same `releases/latest` link, download **svitlo.apk** and install it.
   It installs over the old version and keeps your addresses and settings.

---

## What the app does
- **Widget:** shows the address and queue, then a big status with a coloured dot:
  - 🟢 Світло є
  - 🟡 Можливе відключення
  - 🟠 Ймовірно, світла немає
  - 🔴 Світла немає

  Below that: until when, or when the next outage is, plus a coloured strip of today's 24 hours with a "now" marker.
- **App:** today's and tomorrow's schedule, your saved addresses (each with its own 🔔 on/off), and the notification settings.
- **Notifications:**
  - Before an outage (10/15/30/60 min, you choose).
  - When power should return.
  - Emergency outages (ГАВ/СГАВ) starting or ending.
  - Today's schedule changed, or tomorrow's schedule published.
- **Data** comes from off.energy.mk.ua about every 15 minutes, which is the minimum Android allows.
  It's the official schedule. The app can't check whether your home really has power.
