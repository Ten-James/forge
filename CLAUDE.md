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

- **For UI and visual work, run the libGDX desktop client** (`forge-gui-mobile-dev`) and look at the result. Take
  screenshots of the screens you changed, before and after when that helps, at both sizes from "Devices the UI must
  work on" (an ultrawide and a small 14 inch laptop window). Iterate on what you see. Don't hand in visual work you
  haven't seen running.
- Build and run whatever a change needs to be verified. At minimum, compile the modules you touched.
- Tests: write or run them when they help verify the change. Keep runs to the touched modules; the full build is slow.
- Commit messages: `{feat,bug,refactor,chore,bump}(version): one line`, with the version from the root `pom.xml`
  (`versionCode`). Never sign commits as Claude and add no co-author line.
- Java 17, Maven multi-module build (`pom.xml` at the root). Follow the style of the surrounding code
  (`checkstyle.xml`).
- Docs live in `docs/` (see `docs/Development/UI-Guidelines.md` for UI conventions). Update them when behaviour
  they describe changes, e.g. the Arena layout and shortcut docs.

## Running the client and taking screenshots

Build (from the repo root; `-am` also builds the upstream modules the launcher needs):

```
mvn -pl forge-gui-mobile-dev -am -DskipTests package
```

`package` leaves `forge-gui-mobile-dev/target/forge-gui-mobile-dev-<version>-jar-with-dependencies.jar`. Use
`compile` instead of `package` when you only need to check that the code compiles.

Run (from `forge-gui-mobile-dev/`, because the game looks for `./res` and otherwise uses `../forge-gui/`):

```
cd forge-gui-mobile-dev
java -Xmx4096m -Dfile.encoding=UTF-8 -jar target/forge-gui-mobile-dev-*-jar-with-dependencies.jar width=3440 height=1440
```

- `width=` and `height=` (give both) open a window of that size and leave the saved window size alone
  (`GameLauncher`, `DesktopWindow`). Without them the window reopens at its last size. Use `width=3440 height=1440`
  for the ultrawide and `width=1366 height=768` for the 14 inch laptop.
- The jar's manifest already sets the `--add-opens` flags, so none need adding.
- The first start reads all the cards and is slow. Wait for the title screen before taking a screenshot.

No display (Linux, headless): run the client inside a virtual X server and capture the root window. Keep the
screenshots outside the repo (e.g. `/tmp/forge-shots/`) and never commit them.

```
mkdir -p /tmp/forge-shots
cd forge-gui-mobile-dev
xvfb-run -a -s "-screen 0 3440x1440x24" bash -c '
  java -Xmx4096m -Dfile.encoding=UTF-8 -jar target/forge-gui-mobile-dev-*-jar-with-dependencies.jar width=3440 height=1440 &
  pid=$!
  sleep 90
  import -window root /tmp/forge-shots/ultrawide.png
  kill $pid
'
```

Stop the client when you are done: `kill <pid>` (or `pkill -f forge-gui-mobile-dev-`), then check with
`pgrep -a java` that nothing is left running.

Hints (*try*, not checked in this repo):
- Missing tools: `apt install xvfb imagemagick` on Debian/Ubuntu. `xwd -root` can replace `import`.
- If OpenGL fails under Xvfb, try `LIBGL_ALWAYS_SOFTWARE=1` before `java`.
- On a Wayland desktop (`WAYLAND_DISPLAY` set, `DISPLAY` unset) the client opens its window without `xvfb-run`.
  `import` cannot capture Wayland windows; try `grim` if it is installed.
- Tests for one module: `mvn -pl forge-gui-mobile -am test -Dtest=<ClassName> -Dsurefire.failIfNoSpecifiedTests=false`.

Screenshots and before/after comparisons can be handed to a subagent when a flow asks for it.
