# Forge – working notes for Claude

## Which part of Forge we work on

**Unless a task says otherwise, "the app", "the UI", "the theme", "the match screen" or "the window" means the
libGDX client, not the Swing one.** That is the client the owner plays and develops for.

| Module | What it is | Our focus |
| --- | --- | --- |
| `forge-gui-mobile` | libGDX GUI code: screens, match view, Arena theme, adventure mode. Shared by every libGDX backend. | **Yes, most work lands here.** |
| `forge-gui-mobile-dev` | libGDX desktop launcher (LWJGL3): `forge.app.Main`, `GameLauncher`, `DesktopWindow`. This is what runs on the owner's Windows PC. | **Yes** |
| `forge-gui-android`, `forge-gui-ios` | Thin Android / iOS backends on top of `forge-gui-mobile`. | Only when asked |
| `forge-gui` | Shared GUI logic, game modes, preferences, resources (`res/`). | When the libGDX client needs it |
| `forge-gui-desktop` | The **Swing** desktop client. | **No**, leave it alone. It is only the reference the libGDX Arena theme was ported from. |
| `forge-core`, `forge-game`, `forge-ai` | Rules engine and AI. | Only when asked |

When a name exists in both clients (`ArenaTheme`, `ArenaMotion`, `ArenaCurtain` ...), the libGDX one lives under
`forge-gui-mobile/src/forge/screens/match/arena/`, the Swing one under `forge-gui-desktop/.../toolbox/`.
Edit the libGDX one unless told otherwise. The package doc in `arena/package-info.java` explains the skin pattern
the Arena views use (a static `ArenaSkin` the view asks first, default look untouched underneath).

## Devices the UI must work on

Every UI change has to be good on **both** of the owner's setups:

1. **Windows PC with an ultrawide monitor** (mouse and keyboard). Big, very wide window, often maximized or
   borderless fullscreen. Do not assume 16:9: layouts must use the extra width instead of stretching or leaving
   dead space, and nothing may be pinned to fixed positions from a 16:9 design.
2. **Touch laptop, 14 inch screen** (touch, but also a real window with keyboard). Small physical screen, so
   text and controls must stay large enough to hit with a finger, and short windows must still show every
   menu entry. No hover-only interactions: anything reachable by hover or a keyboard shortcut needs a touch path.

Related existing pieces: `InterfaceSize` (interface size preference, `Utils.scale`), `DesktopWindow` (resizable
window that remembers itself, borderless fullscreen, F11 / Alt+Enter), and `ArenaTheme.px()` for sizes.
Use them rather than hard-coding pixel values.

## Working rules

- Do not run the application and do not write or run tests unless the user asks for it.
- Commit messages: `{feat,bug,refactor,chore,bump}(version): one line`, with the version from the root `pom.xml`
  (`versionCode`). Never sign commits as Claude and add no co-author line.
- Java 17, Maven multi-module build (`pom.xml` at the root). Follow the style of the surrounding code
  (`checkstyle.xml`).
- Docs live in `docs/` (see `docs/Development/UI-Guidelines.md` for UI conventions). Update them when behaviour
  they describe changes, e.g. the Arena layout and shortcut docs.
