ATAK Plugin — TAKwerx Market

**Download TAKwerx Market 1.0** (pick the one matching your ATAK-CIV version, sideload, then load it in ATAK's Plugins manager):

- **ATAK-CIV 5.6:** https://github.com/takwerx/takwerx-market/releases/download/v1.0/ATAK-Plugin-TakwerxMarket-1.0--5.6.0-civ-release.apk
- **ATAK-CIV 5.7:** https://github.com/takwerx/takwerx-market/releases/download/v1.0/ATAK-Plugin-TakwerxMarket-1.0--5.7.0-civ-release.apk
- **ATAK-CIV 5.8:** https://github.com/takwerx/takwerx-market/releases/download/v1.0/ATAK-Plugin-TakwerxMarket-1.0--5.8.0-civ-release.apk

All releases: https://github.com/takwerx/takwerx-market/releases

**User guide with screenshots: [docs/USER_GUIDE.md](docs/USER_GUIDE.md)**
(https://github.com/takwerx/takwerx-market/blob/main/docs/USER_GUIDE.md)

_________________________________________________________________
PURPOSE AND CAPABILITIES

A plugin catalog inside ATAK. It lists the TAKwerx ATAK plugins, shows which of
them run on the ATAK build the device is actually running, and installs and
updates them without leaving the app. An operator sideloads one plugin and gets
the rest from inside ATAK, instead of being handed several APKs to sideload by
hand and being told which one matches their ATAK version.

Capabilities:

  - Reads a catalog over HTTPS in ATAK's own product.inf format, so the same
    hosted tree is also readable by ATAK's built-in Update Server setting.
  - Offers only what matches the running ATAK. A plugin built for a different
    ATAK release is listed and greyed with the release it was built for, rather
    than hidden, so the list never silently omits things.
  - Per plugin: install, update, load, unload and uninstall, the same set of
    actions ATAK's own package manager offers.
  - Shows whether an installed plugin is currently loaded into ATAK, which is
    otherwise not visible from the map.
  - One quiet notice at ATAK start when something installed is out of date, and
    nothing at all when everything is current.
  - Verifies every download against a SHA-256 published in the catalog, and
    refuses to install anything not signed by a TAK Product Center
    plugin-release certificate.

  - Updates ATAK itself. The catalog carries the newest ATAK-CIV release; a
    phone on an older one is offered it, and the market installs its own build
    for the new ATAK first, then hands ATAK to Android, so that when ATAK comes
    back the market is there to move every plugin over.

The catalog hosts no plugin APKs. Each plugin entry links to that plugin's own
signed release, so the plugin a user installs is the same file published by its
own project. ATAK-CIV is the exception: the tak.gov release is copied to the
catalog host byte for byte, its TAK Product Center signature intact, and the
market pins that signature for ATAK's package before Android is asked.

_________________________________________________________________
STATUS

Version 1.0. Tenth submission.

Verified on hardware against a live catalog: Samsung Galaxy XCover Pro,
Android 13, ATAK-CIV 5.8.0.3. Catalog fetch, per-ATAK filtering, update
detection, install to completion, load, unload, uninstall, signer pinning and
the user manual opening from Tool Preferences have all been exercised on the
device, in both debug and release (proguard) builds.

0.1 was verified on official ATAK: Samsung Galaxy S22 Ultra, Android 14,
ATAK-CIV 5.7.0.5. The signature verified, the plugin loaded, and a plugin was
updated from the catalog end to end -- downloaded, hash checked, signer checked,
installed over the existing copy and loaded by ATAK without a restart.

0.2 fixed what that session exposed: a loaded/unloaded indicator that went stale
after an update, and download progress that lived in a header where it could not
say which row was busy. Both confirmed working on ATAK-CIV 5.7.0.5, along with a
clean install and a replace-in-place update.

0.3 changed how the APK is handed to Android. Previously the plugin never
learned the outcome of an install, so the list depended on package broadcasts to
notice -- and on Android 14 those did not reliably arrive, leaving a row
claiming a plugin was installed after it had been removed. Installing through a
PackageInstaller session reports the result back, so the row is correct without
a refresh. Android's confirmation prompt is unchanged; its separate completion
screen is not shown, so an install is one dialog rather than two. Confirmed on
ATAK-CIV 5.7.0.5: uninstalling with the list open corrects it immediately, where
before the row went on claiming the plugin was installed.

0.4 finishes the same problem at the other end. Loading a plugin raises no
broadcast at all, so after an update the loaded indicator stayed wrong until
something happened to redraw it. ATAK does record it -- it writes a preference
per plugin -- and watching that gives the missing event. The number of available
updates now also appears on the toolbar icon, the way ATAK badges its own tools.

0.5 undoes half of 0.3. Installing through a session makes ATAK the installer
of record, and Android delivers the package-added event to the installer twice:
once as everyone gets it and once addressed to the installer. ATAK asks "Load
plugin?" for each, so every install prompted twice. Measured on ATAK-CIV 5.7.0.5
by replacing the same signed plugin over itself with the installer of record
changed and nothing else: one event and one prompt through the system installer,
two and two with ATAK named. So the APK goes to Android's installer again, as
ATAK's own package manager does, and the row is kept honest by the package
broadcast instead of a session result. The toolbar count also now follows the
list -- before, it was set once at start and stayed there after every update.

0.5 was confirmed on ATAK-CIV 5.7.0.5: three updates and one install from the
pane, each through Android's own installer, one "Load plugin?" each, and the
toolbar count going 3, 2, 1, gone as they were applied. 0.6 is wording from
that session: the header is two lines, the count of plugins for this ATAK and
under it "N updates available" or "All up to date"; and the startup notice
spells the plugin's name the way the plugin does.

0.6 was confirmed the same way on ATAK-CIV 5.7.0.5, and its screenshots are the
ones in the manual. 0.7 is the manual itself -- every screen the operator will
meet, taken on that device -- plus two words: the header only says "All up to
date" when every listed plugin is installed, and otherwise says how many are
not; and the Tool Preferences summary spells the name the same way as the rest.

0.8 closes the case the plugin exists for and 0.7 got wrong. After an ATAK
upgrade, say 5.7 to 5.8, every plugin on the device is still installed but was
built for the old release; ATAK marks them incompatible and they never load.
0.7 compared version numbers only, so PLSS 0.5 built for 5.7 matched the
catalog's 0.5 built for 5.8 and the row went green over a plugin that would
never load again. The market now also reads the ATAK target the installed APK
declares, and a build for another ATAK is offered the build for this one:
"1.1 · built for 5.7.0.CIV → 5.8.0.CIV", counted on the badge, replaced in
place. Verified on ATAK-CIV 5.8.0.3 with a tak.gov 5.7 build of Cam Depot
installed: offered, replaced through Android's installer, one load prompt.

0.9 updates ATAK. The catalog carries the newest ATAK-CIV, and a phone on an
older one gets a row for it. Tapping Update downloads ATAK, then the market's
own build for the new ATAK, verifies both, and hands them to Android in that
order: the market's new build first (Android replaces the file; the running
copy carries on), then ATAK. ATAK restarts on the new version, the new market
loads, and every plugin built for the old ATAK shows as an update, with an
"Update all" button to run them one after another. ATAK's own signing
certificate is pinned for its package alone; an ATAK that did not come from
tak.gov is told so and left alone. Verified on ATAK-CIV 5.8.0.3 (dev build):
the row, the warning, and the refusal for a non-tak.gov ATAK. The full
5.7 to 5.8 run needs a tak.gov-signed build on an official ATAK.

1.0 is that run, done. Signed 0.9 on a Samsung Galaxy S22 Ultra, official
ATAK-CIV 5.7.0.5, Android 14: Update on the ATAK row downloaded 5.8.0.4 and
the market's 5.8 build, Android replaced the market, ATAK unloaded it and 33
ms later the market's stop hook handed ATAK to the installer, ATAK came back
as 5.8.0.4 with its data, the new market's "Update all (4)" replaced the four
5.7 plugins in fourteen seconds, all loaded, all up to date. Two things that
run exposed are fixed here: ATAK had marked the market not-to-load when it
unloaded it for the replace, so the new build had to be loaded by hand once
(the handoff now marks it to load first); and the Update all button sat inset
from Refresh. The run also found that ATAK 5.8.0.4 itself will not start with
an Esri vector tile package in atak/imagery (a Map Depot download); that is
ATAK's and is recorded for Map Depot, not the market's.

Because the market is now what hands a phone 5.8, it will not hand it to a
phone it would break: if there are vector tile packages in atak/imagery, the
5.8 update is not offered, and the message says why and that a fix is being
waited on. The market moves nobody's files. The check is by target release
rather than build number until a fixed 5.8 is confirmed.

_________________________________________________________________
POINT OF CONTACTS

Andreas Johansson, takwerx
https://github.com/takwerx/takwerx-market/issues

_________________________________________________________________
PORTS REQUIRED

(This is important for ATO, networking, and other security concerns)

  Outbound TCP 443 (HTTPS) only.

  The plugin makes three kinds of request, all HTTPS and all to the catalog host
  or to the plugin release it links to:

    - the catalog itself, on ATAK start and when the operator opens or refreshes
      the pane
    - a plugin icon, once per ATAK session per listed plugin
    - a plugin APK, only when the operator taps Install or Update

  Plain HTTP is refused at the connection, not merely avoided: a non-HTTPS URL
  is rejected before any request is made, and redirects cannot downgrade the
  transport.

  No inbound ports. No listening sockets. No traffic to or from the TAK server.
  No CoT is generated or consumed. Nothing is sent anywhere -- the plugin only
  fetches, and transmits no device, user or position data of any kind.

  With no network the plugin lists nothing and says so; it does not block ATAK
  start or any other function.

_________________________________________________________________
EQUIPMENT REQUIRED

  Android device supported by ATAK-CIV 5.6, 5.7 or 5.8.
  A network connection when browsing or installing. Downloads are the size of
  the plugin being installed, typically 2 to 7 MB.

_________________________________________________________________
EQUIPMENT SUPPORTED

  Any Android device supported by ATAK. No additional or external hardware, no
  sensors, no peripherals.

_________________________________________________________________
COMPILATION

  Standard ATAK plugin build. Set sdk.path in local.properties to an unpacked
  ATAK CIV SDK, then:

      ./gradlew assembleCivDebug
      ./gradlew assembleCivRelease

  ext.ATAK_VERSION in app/build.gradle selects the ATAK release to target.

  The user manual is compiled from docs/user_manual/ by gradle/typst.gradle when
  ATAK_CI=1, or locally with -PbuildManual. It lands at
  app/src/main/assets/usermanual.pdf, which is build output and is not committed
  or submitted.

_________________________________________________________________
DEVELOPER NOTES

  Almost nothing here depends on ATAK internals, deliberately. Official ATAK is
  obfuscated and the SDK ships an empty mapping.txt, so what survives a release
  cannot be checked before submitting. The catalog fetch, the parse, the
  installed-version lookup and the install itself use only Android APIs. The
  three places that do reach into ATAK -- loading and unloading a plugin, the
  Tool Preferences entry, and the PDF helper -- are each guarded so that losing
  one costs that feature and not the plugin.

  Update detection cannot use versionCode. tak.gov builds from a source zip with
  no .git, so getVersionCode() falls through to 1 on every signed release;
  measured against four published plugins, all report revision 1. Comparing
  revisions would report zero updates forever while appearing to work. Versions
  are therefore compared by name, numerically per dotted component.

  For the same reason the plugin manages its own manual. PdfHelper.extractAndShow
  decides whether to re-extract by comparing versionCode, so with every release
  reporting 1 the first manual a user opens is the one they keep. The plugin
  compares the version name itself and removes the stale file first.

  The APK is handed to Android's installer with ACTION_VIEW rather than through
  a PackageInstaller session, and the reason is measured, not stylistic. A
  session names the calling app as installer of record, and Android sends that
  app the package-added broadcast a second time, addressed to it. ATAK raises
  its "Load plugin?" prompt on each copy. What a session buys -- the outcome,
  including cancel -- is traded for one prompt instead of two; the row waits
  on the package broadcast and gives up on its own if nothing arrives.

  A downloaded APK is staged in ATAK's internal storage, never external. Under
  Android/data any app holding WRITE_EXTERNAL_STORAGE can write on API 29 and
  below, and PackageInstaller re-reads the file when the operator confirms, so
  external staging would mean the bytes installed need not be the bytes that
  were verified. Internal storage is ATAK's uid only, so nothing outside the
  process can touch the file between the check and the installer's copy. It is written to a subdirectory of the path ATAK's own
  FileProvider declares, because the startup sweep empties what it is pointed at
  and ATAK keeps its own plugin APKs in that directory.

  Updating ATAK from inside ATAK is a two-file, two-prompt handoff, and the
  order is the design. The market's own build for the NEW ATAK is installed
  first: Android replaces the APK on disk while the running code carries on
  from the old one (measured on official 5.7: the old classes kept running
  until the process restarted). Then ATAK. ATAK is killed by its own replace
  and comes back on the new version, where the market's new build loads and
  offers every plugin its matching build. Done the other way round, the new
  ATAK comes up with no market to finish the job. Both files are downloaded
  and verified before either is handed over, so nothing depends on the
  network between the two prompts. If the catalog has no market build for the
  target ATAK, ATAK is not updated either.

  The SHA-256 check only proves the bytes match the catalog, which is worth
  nothing if the catalog is not trustworthy. Signer pinning is what establishes
  that a binary came from tak.gov's pipeline, and it is deliberately not
  conditioned on the catalog's own "type" column -- a check a document can
  switch off is not a check.

  Icon URLs carry a hash of the icon's own bytes. The catalog sits behind a CDN,
  and an icon changed at an unchanged URL was served stale by some edges and not
  others, so devices that looked during that window cached the wrong image
  indefinitely. A URL that changes with its content cannot go stale at any layer.

  ATAK already ships a mechanism for this in com.atakmap.android.update, and it
  is worth knowing why this plugin exists alongside it. That mechanism is driven
  by a single Update Server URL, so an organisation already pointing ATAK at
  their own repository would have to give it up. This plugin adds a catalog
  rather than replacing one, and needs no configuration.
