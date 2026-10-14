# Launcher icon

The icon, "Portal": an ivory knight bursting out of a gold-ringed portal onto a swirling board, on
a night sky with a teal glow.

`build.py` draws it (with the helpers in `shapes.py` and `knight.py`) and writes the three vector drawables in
`app/src/main/res/drawable/` (`ic_launcher_foreground.xml`, `ic_launcher_background.xml` and
`ic_launcher_monochrome.xml`), plus an `icon.svg` preview.

The knight's outline comes from the chess symbols in
[Noto Sans Symbols 2](https://fonts.google.com/noto/specimen/Noto+Sans+Symbols+2), under the SIL Open
Font License (`app/src/main/assets/licenses/NotoSansSymbols2-OFL.txt`). The font isn't in the
repository; to regenerate the icon, download `NotoSansSymbols2-Regular.ttf` and run, with
[fontTools](https://pypi.org/project/fonttools/) installed:

```
python docs/icon/build.py docs/icon NotoSansSymbols2-Regular.ttf app/src/main/res/drawable
```
