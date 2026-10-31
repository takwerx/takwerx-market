# Community plugins

The plugins from other developers that the TAKwerx Market lists. The hourly
catalog refresh reads `plugins.json` and `glyphs/` from `main` of this
repository, so a merged change reaches the Market within the hour. To get a
plugin listed, see [docs/LIST_YOUR_PLUGIN.md](../docs/LIST_YOUR_PLUGIN.md).

Each entry in `plugins.json`:

| Field | Required | What it is |
|---|---|---|
| `repo` | yes | `owner/repo` on GitHub, whose Releases carry the tak.gov-signed APKs |
| `description` | yes | One sentence for the list row: 160 characters at most, a capital to start, a full stop to end |
| `glyph` | yes | File name under `glyphs/`: lowercase letters, digits, `.`, `-` and `_`, ending `.png` |
| `listed` | no | Date the plugin was listed |
| `request` | no | The issue that asked for it |

The glyph is the plugin's white toolbar glyph on a transparent background,
square, 256 × 256 with the glyph filling the square, under 256 KB.

Before adding an entry, check every APK in the repository's newest release:
signed by a TAK Product Center plugin-release certificate (`apksigner verify
--print-certs`), and a `plugin-api` that matches its ATAK version. The refresh
checks the same things again on every run and refuses anything else.

Removing an entry removes the plugin from every phone's Market. The unattended
refresh refuses to remove a listed plugin, so after a removal is merged the
catalog has to be republished by hand.
