ATAK Plugin — TAKwerx Market

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

The catalog hosts no APKs. Each entry links to that plugin's own signed release,
so the plugin a user installs is the same file published by its own project, and
this plugin distributes nothing.

_________________________________________________________________
STATUS

Version 0.6. Sixth submission.

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
