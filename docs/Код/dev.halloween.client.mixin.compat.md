# dev.halloween.client.mixin.compat

Файл — строк — о чём (первая фраза комментария класса). Обновляется tools/obsidian_export.pl.

- **HwCompatPlugin** (`src/client/java/dev/halloween/client/mixin/compat/HwCompatPlugin.java`, 45) — Mixins into other mods apply only when that mod is there: Sodium* for Sodium.
- **SodiumChunkRendererMixin** (`src/client/java/dev/halloween/client/mixin/compat/SodiumChunkRendererMixin.java`, 22) — Binds the game's Globals for Sodium's terrain pass, next to its own (see SodiumChunkShaderMixin).
- **SodiumChunkShaderMixin** (`src/client/java/dev/halloween/client/mixin/compat/SodiumChunkShaderMixin.java`, 20) — Sodium's terrain pipeline also takes the game's Globals (where the camera is, the time): our match fog needs them on the terrain too (halloween:sodium_fog.glsl).
