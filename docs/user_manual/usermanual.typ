#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "TAKwerx Market",
   plugin-version: "1.0",
   platform: "ATAK",
   platform-version: "5.8.0",
)

#tak-slide[
= Overview

TAKwerx Market lists every takwerx ATAK plugin, shows which of them run on the
ATAK you are holding, and installs them without leaving ATAK. Sideload this one
plugin and you can get the rest from inside the app.

It also tells you when something you already have is out of date, and lets you
load, unload or remove a plugin from the same list.

#v(6pt)
#toolbox.side-by-side(columns: (4fr, 7fr))[
  #image("02-pane-updates.png", width: 92%)
][
  Open it from the ATAK toolbar. The header says how many plugins are available
  for your ATAK, and under it whether any of them have updates waiting.

  Each row is one plugin: its name, its version, and what the market can do
  about it. Amber means an update is available and shows what you have beside
  what is on offer. Green means you are current. A plugin you do not have shows
  its version and an *Install* button.

  The count on the toolbar icon is the same number as the header. It goes down
  as you apply updates and disappears when there are none.
]
]

#tak-slide[
= The toolbar and the startup notice

#image("01-toolbar-badge.jpg", width: 100%)

The market icon carries a red count whenever updates are waiting. It is drawn
the way ATAK badges its own tools, so it reads the same as the rest of the bar.

#v(6pt)
#toolbox.side-by-side(columns: (6fr, 5fr))[
  #image("13-startup-toast.jpg", width: 100%)
][
  When ATAK starts, the market checks the catalog quietly in the background. If
  anything you have is out of date it says so once, like this, and then leaves
  you alone. If everything is current it says nothing at all.

  Open the market whenever you want the full picture. *Refresh* re-reads the
  catalog if you want to check again immediately.
]
]

#tak-slide[
= What the buttons do

*Install* fetches a plugin you do not have. *Update* replaces one you do with a
newer release. A plugin that is already current has no button at all, because
there is nothing to do to it.

#v(6pt)
#toolbox.side-by-side(columns: (6fr, 5fr))[
  #image("09-row-actions.jpg", width: 100%)
][
  Tapping the *row* rather than the button opens the actions for a plugin you
  already have: load it into ATAK, unload it, or uninstall it entirely. That is
  the same set of things ATAK's own plugin manager offers, without leaving the
  market.

  == Why some plugins are not offered

  ATAK plugins are built against one ATAK release and only load on that
  release. The market reads the ATAK you are running and offers only what
  matches it. Anything built for a different ATAK is still listed, greyed out,
  saying which release it was built for. It is not hidden: a list that silently
  drops things reads as though nothing else exists.
]
]

#tak-slide[
= Installing and updating

Tap *Install* or *Update*. The market downloads the plugin, checks it, and hands
it to Android. The row shows the download as it happens.

#v(6pt)
#toolbox.side-by-side(columns: (4fr, 7fr))[
  #image("03-downloading.png", width: 92%)
][
  #image("04-android-update-dialog.jpg", width: 80%)
  #v(4pt)
  Android then asks you to confirm. That prompt is Android's own and cannot be
  skipped: no app on your phone can install another one silently. On some
  devices you are asked once to allow ATAK to install apps; allow it and you
  will not be asked again.

  Two things are checked before Android is ever asked. The download must match
  the fingerprint published in the catalog, so a truncated or altered file is
  discarded rather than installed. And the plugin must be signed by the TAK
  Product Center: everything in the market is built and signed by tak.gov from
  published source, and anything else is refused, with the reason shown.
]
]

#tak-slide[
= What you will see next

#toolbox.side-by-side(columns: (1fr, 1fr, 1fr))[
  #image("05-done-screen-and-uninstalled-toast.jpg", width: 100%)
][
  #image("06-load-plugin-prompt.jpg", width: 100%)
][
  #image("07-loaded-toast.jpg", width: 100%)
]

#v(6pt)
Three things happen in a row, and all three are normal.

- *Android's "App installed" screen.* Tap *Done*.
- *A toast saying the plugin was uninstalled.* During an update ATAK notices the
  old copy being replaced and says so. Nothing has gone wrong; the market row
  underneath says *Installing…* for exactly that reason.
- *ATAK asks "Load plugin?"* This is the same question it asks after any
  install, from any source. Say *OK* and the row turns green with *LOADED*.

The row updates on its own. You do not need to reopen the market or refresh it.
]

#tak-slide[
= Unloading and uninstalling

#toolbox.side-by-side(columns: (1fr, 1fr, 1fr))[
  #image("10-row-unloaded.png", width: 84%)
][
  #image("11-android-uninstall-dialog.jpg", width: 100%)
][
  #image("12-after-uninstall.png", width: 84%)
]

#v(6pt)
*Unload from ATAK* takes a plugin out of the running ATAK but leaves it on the
phone. The row says *UNLOADED* in amber, and the same row menu offers to load
it again.

*Uninstall* removes it from the phone. Android asks you to confirm, as it does
for any app. When it is gone, the row goes back to offering *Install*, the
plugin's icon leaves the toolbar, and the market's own toast says which one was
removed.

Uninstalling a plugin does not touch the data it downloaded into ATAK. Maps,
elevation and cameras are still there for the next install.
]

#tak-slide[
= All up to date

#toolbox.side-by-side(columns: (4fr, 7fr))[
  #image("14-all-up-to-date.png", width: 92%)
][
  When every plugin is installed and current the header says so in green, the
  count leaves the toolbar icon, and no row has a button.

  If everything you have is current but some plugins are not installed, the
  header says *No updates* and how many are not installed, so *all* is only
  ever said when it means all.

  == "signed with a different key"

  If the market says a plugin on your device was signed with a different key,
  you are running a build that did not come from tak.gov, usually a development
  build. Android will not replace an app with one signed by a different key, so
  the market offers to uninstall the one you have; then install from the
  market. This is Android's rule, not the market's, and the market stops you
  before Android shows its own less helpful message.
]
]

#tak-slide[
= After an ATAK upgrade

#toolbox.side-by-side(columns: (4fr, 7fr))[
  #image("18-after-atak-upgrade.png", width: 92%)
][
  Move from ATAK 5.7 to 5.8 and every plugin you had is still installed but
  was built for 5.7, so ATAK marks it incompatible and it will not load. It is
  not obvious what to do about that from ATAK's own package manager.

  Get the 5.8 market from the same place you got this one and open it. Each of
  those plugins shows up amber as *built for 5.7.0.CIV → 5.8.0.CIV* with an
  *Update* button, and the count on the toolbar icon includes them. One tap
  each, the same three screens as any update, and you are moved over.

  The market knows because it reads the ATAK release each installed plugin
  was built for, not just its version number. The number alone would say
  nothing had changed.

  If you update ATAK yourself, you sideload the matching market once. If the
  market updates ATAK (next page), you do not: it puts its own new build on
  first. This screen is a phone the market had just moved from 5.7 to 5.8.
]
]

#tak-slide[
= Updating ATAK itself

#toolbox.side-by-side(columns: (4fr, 7fr))[
  #image("19-atak-row.png", width: 92%)
][
  When a newer ATAK-CIV is out, the market shows it as a row of its own, above
  the plugins, with an *Update* button. Tap it and read the warning, because
  this one restarts ATAK.

  The market downloads ATAK (about 370 MB), then its own build for the new
  ATAK, and checks both. Then two Android prompts, back to back: first the
  market's new build, then ATAK. If ATAK asks to load TAKwerx Market between
  the two, tap *Cancel*; it loads after the restart. Do not cancel the ATAK
  install once it starts.

  ATAK comes back on the new version. Open the market: every plugin you had
  is listed as *built for 5.7.0.CIV → 5.8.0.CIV*, and *Update all* moves them
  over one after another, each with the same three screens as one update.

  The ATAK file is the TAK Product Center's own release, copied unchanged and
  carrying its signature; the market checks that signature, and that the file
  is the version it was told, before Android ever sees it. An ATAK that did
  not come from tak.gov cannot be updated from here, and the market says so.

  == Vector tile packages and ATAK 5.8

  ATAK 5.8.0.4 does not start with a vector tile package (Map Depot's
  public-lands maps) on the phone. If you have any, the market will not offer
  the 5.8 update, and says so. It comes back when tak.gov ships a fixed ATAK.
]
]

#tak-slide[
= After the ATAK update

#toolbox.side-by-side(columns: (4fr, 7fr))[
  #image("21-all-on-58.png", width: 92%)
][
  ATAK is back on the new version and the market loaded with it. Its list
  shows every plugin you had as *built for 5.7.0.CIV → 5.8.0.CIV*, the toolbar
  count says how many, and *Update all* runs them one after another: Android's
  confirm, Done, and "Load plugin?" for each. Four plugins took a quarter of a
  minute on the phone this was written on.

  When it is done the header says *All up to date*, ATAK's own row reads
  *Installed* with the new version, and the count leaves the toolbar icon.
  Nothing you had downloaded into ATAK was touched: maps, elevation and
  cameras are all still there.
]
]

#tak-slide[
= Settings and this guide

#image("16-tool-preferences-page.png", width: 100%)

Settings #sym.arrow.r Tool Preferences #sym.arrow.r TAKwerx Market. *Plugin
Documentation* opens this guide on the device.

#v(6pt)
#toolbox.side-by-side(columns: (1fr, 1fr))[
  #image("15-tool-preferences.png", width: 100%)
][
  There is deliberately nothing else there. The market reads one list, the
  TAKwerx one, and that cannot be changed from the device. ATAK can import
  preferences from a data package, so a setting for it would be a way to point
  the market somewhere else without you doing anything, and what the market
  offers you is exactly the thing worth being sure about.

  == Getting help

  Every takwerx plugin has its own repository, with its downloads, its own guide
  and its own issue tracker. If something in the market is wrong, that is the
  place to raise it.
]
]
