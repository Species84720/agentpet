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
main-screen preview and floating pet show sleepy animation when tired or
napping (while AI work and approvals still take priority). Naps last five
minutes and gradually restore energy; the pet cannot take game actions until
it wakes. Low energy or hunger blocks play and exploration. Feeding a full pet
causes an eight-minute tummy break that also blocks those activities. The
floating pet shows idle thoughts only when no AI task or approval needs
attention. Game actions are stored on the phone and do not grant AI-use XP or
change Cloudflare care totals. Actual agent token use continues to award level
XP.

Daily care requests earn stars and a friendship bonus. Friendship unlocks four
decorated rooms, three toys and three tricks. Use **Decorate**, **Toys**, and
**Tricks** on Play to choose them; toys and tricks use energy and respect sleep,
hunger and tummy breaks. Unlocks stay earned. Friendship gently drops after six
hours without rewarding care (at most 30 points), and each care action grants
friendship at most once every five minutes to discourage button spam.

**Ask AI** connects to the Power Automate pet brain through the paired relay.
Follow [`power-automate/README.md`](../power-automate/README.md) to generate and
configure the flow. AI chat shares only pet context and conversation memories.
Settings → Relay → **Clear pet AI memories** deletes those cloud memories.

Use **Requests** for pending approval decisions. **Settings** contains the pet,
animation, bubble, care, history, and relay controls. The floating pet opens
Settings on a double-tap; a single tap expands or collapses its message bubble.
Swipe inside the message bubble to scroll its full text. Drag the pet itself
to move the overlay. Pet size, animation clips and speed, bubble appearance,
and display settings apply to the running overlay automatically; relay URL
and token edits reconnect after a short pause in typing.
To pair the companion, enter the deployed relay URL and a `companion` device
token in Settings → Relay, grant **Display over other apps**, and tap **Allow
overlay and start pet**.
Once overlay permission is granted, opening the app starts the floating pet
automatically. The Play screen offers **Enable floating pet** on first setup;
returning from Android's permission screen starts it without another tap.

The companion reads `snapshot` and `event` WebSocket frames and renders the
selected animated pet in its floating overlay and in the game screen. When two
or more sessions are working or waiting at once, the overlay expands to show
their count and a separate logo, project, short session ID, and status for each
active agent (up to three visible, with an overflow count). The Play dashboard
also shows the live active-agent count.

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
