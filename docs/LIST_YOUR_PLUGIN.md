# List your plugin in the TAKwerx Market

The TAKwerx Market lists plugins from other developers as well as takwerx's
own. The Market does not host or rebuild your plugin. Each row links to the
APK on your own GitHub Release, so users install the exact file you
published. Your repository has to be set up the way the Market reads it,
described below. When it is, [open a listing request](#how-to-submit).

## What your repository needs

**1. A public GitHub repository for the plugin.** One plugin per repository.
APK files committed into the repository tree can't be listed, and neither can
a shared repository holding several plugins, because the Market reads GitHub
Releases, not files.

**2. A published GitHub Release with the APKs attached as release assets.**
Drafts are ignored. Every published release counts, pre-releases included,
so don't attach test builds to a pre-release. The Market reads your 10 newest
releases and takes the newest APK for each ATAK version, so you can ship one
ATAK version per release if you need to.

**3. Every APK signed by the TAK Product Center.** Submit your source through
the tak.gov third-party plugin pipeline and attach the APKs it returns,
unchanged. The Market accepts the Trusted and Untrusted Plugin Release
certificates. An APK signed with your own key or the SDK's debug key is
skipped, and so is anything else in the release, such as a companion app
from Google Play.

**4. One APK per ATAK-CIV version the Market serves: 5.6, 5.7 and 5.8 today.**
Each APK must declare the matching `plugin-api` (the SDK template sets it
from `ATAK_VERSION`). The Market reads the version from inside the APK, not
from the file name, but please keep the SDK's naming so users can tell the
files apart:

```
ATAK-Plugin-<Name>-<version>--5.6.0-civ-release.apk
ATAK-Plugin-<Name>-<version>--5.7.0-civ-release.apk
ATAK-Plugin-<Name>-<version>--5.8.0-civ-release.apk
```

**5. A version that goes up with every release.** The Market compares
`versionName` number by number (`1.10` is newer than `1.9`), so start it with
a dotted number. Keep the same package name and give every release a higher
`versionCode` than the last, unique per ATAK version, so phones managed by an
MDM can update too. The SDK's default `versionCode` comes from git and
becomes 1 in a tak.gov build, so set it yourself.

**6. A new version is a new release.** Never replace an APK under an existing
tag and file name. The Market records each file's SHA-256 and phones refuse a
download that doesn't match, so a swapped file breaks installs until we
notice.

A layout that works ([cfd2474/TAK_Beacon](https://github.com/cfd2474/TAK_Beacon/releases)
is a live example):

```
github.com/<you>/<plugin-repo>
├── README.md, LICENSE, source ...
└── Releases
    ├── v1.1.0   ← newest: the Market offers this one
    │   ├── ATAK-Plugin-<Name>-1.1.0--5.6.0-civ-release.apk   (tak.gov signed)
    │   ├── ATAK-Plugin-<Name>-1.1.0--5.7.0-civ-release.apk   (tak.gov signed)
    │   └── ATAK-Plugin-<Name>-1.1.0--5.8.0-civ-release.apk   (tak.gov signed)
    └── v1.0.0
        └── ...
```

## What we need from you

- **An icon:** the plugin's toolbar glyph as a PNG, white on a transparent
  background, square, ideally 256 × 256 with the glyph filling the square.
  A release build renames its resources, so we can't reliably find it inside
  the APK.
- **One sentence** saying what the plugin does, written for a list row on a
  phone: under about 100 characters, starting with a capital and ending with
  a full stop.

## How to submit

1. Set your repository up as above and publish a release.
2. [Open a listing request](https://github.com/takwerx/takwerx-market/issues/new?template=list-my-plugin.yml)
   and fill it in. Attach the icon to the issue.
3. We check every APK's signature and `plugin-api`, then add your plugin to
   [`community/plugins.json`](../community/plugins.json). The Market's catalog
   is rebuilt every hour, so it appears in the Market within an hour of that.

## After you're listed

New releases are picked up automatically, usually within the hour. There's
nothing to tell us unless your repository moves or is renamed, or you'd like
the icon or description changed: open an issue for that. If a release doesn't
meet the rules above, its APKs are skipped and your previous release stays on
offer.

A listing says your plugin came through tak.gov's pipeline. It isn't a review
of your code by takwerx. Bug reports and support questions go to your own
repository.
