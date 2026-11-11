"""Generates the app's colour themes as Android resources.

    python tools/make_themes.py

Writes res/values/theme_colors.xml, res/values-night/theme_colors.xml and
res/values/theme_overlays.xml. Every theme has a light and a dark set of colours;
the dark set lives in values-night, so one overlay style per theme serves both.

Seven of the themes use the colour and corner-radius tokens of Astryx, Meta's
open-source design system (MIT licence, https://github.com/facebook/astryx), read
from the published @astryxdesign/theme-* packages, version 0.7.12. "Teal" is this
app's own. Astryx's typefaces are not bundled: headings use the system serif where
the Astryx theme has a serif heading face, and the system sans otherwise.
"""
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"

# Astryx tokens, (light, dark). Colours are CSS hex: #RRGGBB or #RRGGBBAA.
# body/surface/card/popover/muted are its background roles; radii are in dp
# (inner, element, container, page, full).
ASTRYX = {
    "neutral": dict(
        body=("#f1f1f1", "#1b1b1b"), surface=("#ffffff", "#262626"), card=("#ffffff", "#1b1b1b"),
        popover=("#ffffff", "#1b1b1b"), muted=("#f1f1f1", "#1b1b1b"),
        accent=("#1b1b1b", "#f1f1f1"), accent_muted=("#d4d4d4", "#474747"), on_accent=("#ffffff", "#111111"),
        text=("#000000", "#ffffff"), text2=("#474747", "#9e9e9e"), text3=("#919191", "#525252"),
        border=("#00000014", "#ffffff1A"), border_strong=("#d4d4d4", "#525252"),
        text_error=("#c0262c", "#ffaea7"),
        radii=(6, 10, 12, 28, 999), serif=False),
    "butter": dict(
        body=("#fdfbe4", "#261a13"), surface=("#ffffff", "#2e2117"), card=("#ffffff", "#3a2a1f"),
        popover=("#ffffff", "#3a2a1f"), muted=("#f3f2e2", "#3a2a1f"),
        accent=("#225bff", "#fdee8c"), accent_muted=("#225bff33", "#fdee8c40"), on_accent=("#ffffff", "#1d1c11"),
        text=("#1d1c11", "#f3f2e2"), text2=("#605f52", "#adac9e"), text3=("#adac9e", "#605f52"),
        border=("#e5e3d4", "#f3f2e21a"), border_strong=("#c7c4b2", "#939184"),
        text_error=("#6f1400", "#ffb09b"),
        radii=(6, 8, 12, 24, 999), serif=False),
    "chocolate": dict(
        body=("#FFFCF7", "#141010"), surface=("#FFFCF7", "#1c1610"), card=("#EDE4D4", "#2a2018"),
        popover=("#FFFCF7", "#2a2018"), muted=("#EDE4D4", "#2a2018"),
        accent=("#8C5927", "#d4a06a"), accent_muted=("#8C592714", "#d4a06a20"), on_accent=("#FFFFFF", "#4a3520"),
        text=("#4a3520", "#EDE4D4"), text2=("#B88859", "#c4a882"), text3=("#C4AC95", "#6b5540"),
        border=("#C4AC95", "#EDE4D41A"), border_strong=("#B88859", "#6b5540"),
        text_error=("#cc0000", "#ff7a7a"),
        radii=(6, 10, 12, 24, 999), serif=True),
    "matcha": dict(
        body=("#f0f0e0", "#12140e"), surface=("#ffffff", "#1a1c14"), card=("#ffffff", "#1e2016"),
        popover=("#ffffff", "#3e481d"), muted=("#f0f0e0", "#3e481d"),
        accent=("#3e481d", "#c0cba9"), accent_muted=("#3e481d14", "#c0cba920"), on_accent=("#ffffff", "#3e481d"),
        text=("#3e481d", "#c0cba9"), text2=("#5a6b2a", "#94a468"), text3=("#c0cba9", "#5a6440"),
        border=("#dce3ce", "#c0cba91a"), border_strong=("#b7c29e", "#5a6440"),
        text_error=("#cc0000", "#ff7a7a"),
        radii=(6, 12, 18, 42, 999), serif=True),
    "stone": dict(
        body=("#f0f0f7", "#101018"), surface=("#ffffff", "#1b1b21"), card=("#ffffff", "#25252a"),
        popover=("#ffffff", "#302f36"), muted=("#e2e2e8", "#3a3a42"),
        accent=("#25252a", "#f0f0f9"), accent_muted=("#25252a14", "#f0f0f920"), on_accent=("#ffffff", "#25252a"),
        text=("#25252a", "#f0f0f9"), text2=("#5f5f64", "#ababb3"), text3=("#d4d3dd", "#5e5e64"),
        border=("#e2e2e8", "#f0f0f91a"), border_strong=("#848489", "#5e5e64"),
        text_error=("#603c37", "#f7dbd7"),
        radii=(4, 8, 12, 24, 999), serif=False),
    # Gothic is dark in both modes.
    "gothic": dict(
        body=("#101314", "#101314"), surface=("#101314", "#101314"), card=("#191d20", "#191d20"),
        popover=("#24292d", "#24292d"), muted=("#24292d", "#24292d"),
        accent=("#e8f1f6", "#e8f1f6"), accent_muted=("#e8f1f620", "#e8f1f620"), on_accent=("#101314", "#101314"),
        text=("#e8f1f6", "#e8f1f6"), text2=("#96a0ab", "#96a0ab"), text3=("#495056", "#495056"),
        border=("#e8f1f61a", "#e8f1f61a"), border_strong=("#495056", "#495056"),
        text_error=("#c6a6a2", "#c6a6a2"),
        radii=(4, 8, 12, 24, 999), serif=False),
    "y2k": dict(
        body=("#cccffa", "#0e0f1a"), surface=("#ffffff", "#16182b"), card=("#ffffff", "#16182b"),
        popover=("#ffffff", "#1f2238"), muted=("#f1e6db", "#1f2238"),
        accent=("#2d241b", "#edeffc"), accent_muted=("#2d241b14", "#edeffc20"), on_accent=("#ffffff", "#16182b"),
        text=("#2d241b", "#edeffc"), text2=("#5a5146", "#a6acd6"), text3=("#d1c5b8", "#4a4f6b"),
        border=("#382e24", "#edeffc1A"), border_strong=("#382e24", "#636889"),
        text_error=("#880023", "#ff969a"),
        radii=(0, 0, 0, 0, 0), serif=False),
}

# This app's own theme, already in Android role names, (light, dark).
TEAL = dict(
    bg=("#F2F7F7", "#080B0C"), s_lowest=("#FFFFFF", "#040606"), s_low=("#FFFFFF", "#0E1314"),
    s=("#FFFFFF", "#12191A"), s_high=("#F7FAFA", "#182122"), s_highest=("#E3ECEC", "#202B2C"),
    divider=("#D5E0E1", "#1A2425"), outline=("#A9B8BA", "#33474A"),
    text=("#0B1516", "#F2F7F7"), text2=("#4A5D5F", "#9BADAF"), text3=("#8A9A9C", "#55676A"),
    accent=("#0F766E", "#2DD4BF"), accent_bright=("#14B8A6", "#5EEAD4"), on_accent=("#FFFFFF", "#00201C"),
    accent_c=("#CCEFEA", "#0C3A35"), on_accent_c=("#0B4F49", "#8FF3E4"),
    error=("#C9303A", "#FF7A70"), on_error=("#FFFFFF", "#2B0705"),
)
TEAL_RADII = (8, 12, 12, 28, 999)

ROLES = ["bg", "s_lowest", "s_low", "s", "s_high", "s_highest", "divider", "outline",
         "text", "text2", "text3", "accent", "accent_bright", "on_accent",
         "accent_c", "on_accent_c", "error", "on_error"]


def rgba(css: str):
    h = css.lstrip("#")
    r, g, b = (int(h[i:i + 2], 16) for i in (0, 2, 4))
    a = int(h[6:8], 16) if len(h) == 8 else 255
    return r, g, b, a


def android(css: str) -> str:
    r, g, b, a = rgba(css)
    return f"#{a:02X}{r:02X}{g:02X}{b:02X}"


def over(top: str, base: str) -> str:
    """`top` (may be translucent) painted over opaque `base`, as an opaque colour."""
    r, g, b, a = rgba(top)
    br, bg_, bb, _ = rgba(base)
    k = a / 255
    return "#%02x%02x%02x" % (round(r * k + br * (1 - k)), round(g * k + bg_ * (1 - k)),
                              round(b * k + bb * (1 - k)))


def mix(a: str, b: str, t: float) -> str:
    ar, ag, ab, _ = rgba(a)
    br, bg_, bb, _ = rgba(b)
    return "#%02x%02x%02x" % (round(ar + (br - ar) * t), round(ag + (bg_ - ag) * t),
                              round(ab + (bb - ab) * t))


def roles_from_astryx(t: dict, mode: int) -> dict:
    g = lambda k: t[k][mode]
    body = g("body")
    # The top of the surface ramp carries snackbars and chips: it must not vanish
    # into the page, which Astryx's "muted" does in a few themes.
    highest = g("muted") if g("muted").lower() != body.lower() else mix(body, g("text"), 0.12)
    return dict(
        bg=body, s_lowest=body, s_low=g("surface"), s=g("card"), s_high=g("popover"),
        s_highest=highest, divider=over(g("border"), body), outline=g("border_strong"),
        text=g("text"), text2=g("text2"), text3=g("text3"),
        accent=g("accent"), accent_bright=g("accent"), on_accent=g("on_accent"),
        accent_c=over(g("accent_muted"), g("card")), on_accent_c=g("text"),
        # Used as a foreground (destructive actions, an invalid outline), so this is
        # Astryx text-error, not its error fill.
        error=g("text_error"), on_error=body,
    )


def themes():
    out = {"teal": ([{r: TEAL[r][m] for r in ROLES} for m in (0, 1)], TEAL_RADII, False)}
    for name, t in ASTRYX.items():
        out[name] = ([roles_from_astryx(t, m) for m in (0, 1)], t["radii"], t["serif"])
    return out


HEADER = '<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by tools/make_themes.py; edit that, not this. -->\n<resources>\n'


def write_colors(mode: int, path: Path) -> None:
    lines = [HEADER]
    for name, (modes, _, _) in themes().items():
        lines.append(f"    <!-- {name} -->\n")
        for role in ROLES:
            lines.append(f'    <color name="{name}_{role}">{android(modes[mode][role])}</color>\n')
    lines.append("</resources>\n")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(lines), encoding="utf-8", newline="\n")


ATTRS = [
    ("android:colorBackground", "bg"), ("colorSurface", "bg"), ("colorOnBackground", "text"),
    ("colorOnSurface", "text"), ("colorOnSurfaceVariant", "text2"), ("colorTextMuted", "text3"),
    ("colorSurfaceVariant", "s_highest"),
    ("colorSurfaceContainerLowest", "s_lowest"), ("colorSurfaceContainerLow", "s_low"),
    ("colorSurfaceContainer", "s"), ("colorSurfaceContainerHigh", "s_high"),
    ("colorSurfaceContainerHighest", "s_highest"),
    ("colorSurfaceInverse", "text"), ("colorOnSurfaceInverse", "bg"),
    ("colorOutline", "outline"), ("colorOutlineVariant", "divider"),
    ("colorPrimary", "accent"), ("colorOnPrimary", "on_accent"),
    ("colorPrimaryContainer", "accent_c"), ("colorOnPrimaryContainer", "on_accent_c"),
    ("colorPrimaryInverse", "accent_c"),
    ("colorSecondary", "accent"), ("colorOnSecondary", "on_accent"),
    ("colorSecondaryContainer", "accent_c"), ("colorOnSecondaryContainer", "on_accent_c"),
    ("colorTertiary", "accent_bright"), ("colorOnTertiary", "on_accent"),
    ("colorError", "error"), ("colorOnError", "on_error"),
    ("android:textColorHighlight", "accent_c"),
    # Plain TextViews take the framework's text colours, which Material derives from its
    # own defaults rather than from the roles above; name them outright.
    ("android:textColorPrimary", "text"), ("android:textColorSecondary", "text2"),
    ("android:textColorTertiary", "text3"), ("android:textColorHint", "text3"),
]


def write_overlays(path: Path) -> None:
    lines = [HEADER,
             "    <!-- One overlay per theme, applied on top of Theme.DocScanner at run time\n"
             "         (AppTheme.kt). Colours come from theme_colors.xml, which has a night copy. -->\n"]
    for name, (_, radii, serif) in themes().items():
        style = "".join(p.capitalize() for p in name.split("_"))
        lines.append(f'    <style name="ThemeOverlay.DocScanner.{style}" parent="">\n')
        for attr, role in ATTRS:
            lines.append(f'        <item name="{attr}">@color/{name}_{role}</item>\n')
        for attr, value in zip(["radiusInner", "radiusElement", "radiusContainer", "radiusPage", "radiusFull"], radii):
            lines.append(f'        <item name="{attr}">{value}dp</item>\n')
        lines.append(f'        <item name="headingFont">{"serif" if serif else "sans-serif"}</item>\n')
        lines.append("    </style>\n\n")
    lines.append("</resources>\n")
    path.write_text("".join(lines), encoding="utf-8", newline="\n")


if __name__ == "__main__":
    write_colors(0, RES / "values" / "theme_colors.xml")
    write_colors(1, RES / "values-night" / "theme_colors.xml")
    write_overlays(RES / "values" / "theme_overlays.xml")
    print("themes:", ", ".join(themes()))
