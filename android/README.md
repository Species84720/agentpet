# AgentPet Android companion

This native Android app is the Messenger-style floating companion for the
Cloudflare relay. It runs a visible foreground service, requests the system
overlay grant, and keeps a draggable pop-up above other apps while connected.

## Run

Open `android/` in Android Studio (JDK 17; Android SDK 35). The app opens on its
local companion game. Feed, groom, or let your pet nap; play a three-cup guessing
game, explore three places for keepsakes, and talk to it. Each pet has a
persistent Scout, Dreamer, or Rascal personality, a friendship meter, and a
short diary. Its needs change with time; after 90 minutes alone it can nap,
find a snack, tidy up, or follow its personality on a little adventure. The
floating pet shows these idle thoughts only when no AI task or approval needs
attention. Game actions are stored on the phone and do not grant AI-use XP or
change Cloudflare care totals. Actual agent token use continues to award level
XP.

Use **Requests** for pending approval decisions. **Settings** contains the pet,
animation, bubble, care, history, and relay controls. The floating pet opens
Settings on a double-tap; a single tap expands or collapses its message bubble.
To pair the companion, enter the deployed relay URL and a `companion` device
token in Settings → Relay, grant **Display over other apps**, and tap **Allow
overlay and start pet**.

The companion reads `snapshot` and `event` WebSocket frames and renders the
selected animated pet in its floating overlay and in the game screen.

The app deliberately shows a persistent notification: Android requires one for
the foreground service that keeps an overlay alive. Its **Clear cloud activity
history** control calls `DELETE /v1/logs` after confirmation; it never clears
the selected pet, settings, or device token.

## App updates

Settings → Relay shows the installed version/build and can check for updates.
AgentPet also checks GitHub Releases when opened (at most once every six hours)
and offers newer `android-N` builds. **Download & install** downloads the APK
and opens Android's package installer; Android requires the user to confirm the
installation, so updates cannot be applied silently. The first update may ask
you to allow AgentPet to install packages from this source. GitHub Actions uses
its run number as the Android `versionCode`, and the checked-in development
signing key allows these prerelease APKs to update one another without losing
the saved pairing token.
