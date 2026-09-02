# Icon source art

Drop the icon here. PNG with a transparent background, 512x512 or larger, glyph
in white or a single solid light colour. SVG is fine too.

Two files get generated from whatever lands here:

- `app/src/main/res/drawable/ic_toolbar.png` — the bare glyph, for ATAK's dark
  toolbar and its Tool Preferences list
- `app/src/main/res/drawable/ic_launcher.png` — the same glyph on a dark tile,
  because `android:icon` is drawn on LIGHT backgrounds (the app list, Settings,
  the file browser a user reaches the manual through) where a white glyph is
  invisible

Keep both. One icon for both places is wrong in one of them, and it looks fine
right up until a user sees it.

## How the current pair was generated

    java tools/MakeIcon.java <source.png> ic_toolbar.png ic_launcher.png check40.png 7

The trailing number dilates the strokes before downscaling. The supplied art has
strokes about 1% of its width; at a 40px toolbar slot that is under half a pixel,
and it renders as a dashed line rather than a cart. A dilation of 7px at 1254px
source makes it legible.

Better than dilating: re-export the source with strokes roughly 3x heavier and
run with 0. Dilation rounds corners slightly and fills small interior gaps — the
socket's prongs are already lost at 40px either way.
