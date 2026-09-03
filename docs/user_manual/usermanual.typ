#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "TAKwerx Market",
   plugin-version: "0.4",
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
#toolbox.side-by-side(columns: (5fr, 7fr))[
  #image("pane.png", width: 100%)
][
  Open it from the ATAK toolbar. The header says how many plugins are available
  for your ATAK and how many of them have updates waiting.

  Each row is one plugin: its name, its version, and what the market can do
  about it. Amber means an update is available and shows what you have beside
  what is on offer. Green means you are current.
]
]

#tak-slide[
= What the buttons do

*Install* fetches a plugin you do not have. *Update* replaces one you do with a
newer release. A plugin that is already current has no button at all — there is
nothing to do to it.

Tapping the *row* rather than the button opens the actions for a plugin you
already have: load it into ATAK, unload it, or uninstall it entirely. That is
the same set of things ATAK's own plugin manager offers, without leaving the
market.

== Why some plugins are not offered

ATAK plugins are built against one ATAK release and will only load on that
release. The market reads the ATAK you are running and offers you only what
matches it.

Anything built for a different ATAK is still listed, greyed out, saying which
release it was built for. It is not hidden: a list that silently drops things
reads as though nothing else exists.
]

#tak-slide[
= Installing

Tap *Install* or *Update* and the market downloads the plugin, checks it, and
hands it to Android.

Android then asks you to confirm the install. That prompt is Android's own and
cannot be skipped — no app on your phone can install another one silently. On
some devices you are asked once to allow ATAK to install apps; allow it and you
will not be asked again.

After Android finishes, the row updates on its own. You do not need to reopen
the market or refresh it.

== What gets checked before anything is installed

Two things, both before Android is ever asked:

- The download must match the fingerprint published in the catalog, so a
  truncated or altered file is discarded rather than installed.
- The plugin must be signed by the TAK Product Center. Everything in the market
  is built and signed by tak.gov from published source; anything else is
  refused, and the market tells you why.
]

#tak-slide[
= Updates

When ATAK starts, the market checks quietly in the background. If anything you
have is out of date it says so once, and then leaves you alone. If everything is
current it says nothing at all.

Open the market whenever you want the full picture. *Refresh* re-reads the
catalog if you want to check again immediately.

== "signed with a different key"

If the market says a plugin on your device was signed with a different key, you
are running a build that did not come from tak.gov — usually a development
build. Android will not replace an app with one signed by a different key, so
uninstall the one you have first and then install from the market.

This is Android's rule, not the market's, and it is the reason the market stops
you before Android shows its own less helpful message.
]

#tak-slide[
= Settings

Settings #sym.arrow.r Tool Preferences #sym.arrow.r TAKwerx Market.

*Plugin Documentation* opens this guide.

There is deliberately nothing else there. The market reads one list, the TAKwerx
one, and that cannot be changed from the device. ATAK can import preferences
from a data package, so a setting for it would be a way to point the market
somewhere else without you doing anything — and what the market offers you is
exactly the thing worth being sure about.

== Getting help

Every takwerx plugin has its own repository, with its downloads, its own guide
and its own issue tracker. If something in the market is wrong, that is the
place to raise it.
]
