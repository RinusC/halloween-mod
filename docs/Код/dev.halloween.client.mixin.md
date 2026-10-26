# dev.halloween.client.mixin

Файл — строк — о чём (первая фраза комментария класса). Обновляется tools/obsidian_export.pl.

- **AbstractClientPlayerMixin** (`src/client/java/dev/halloween/client/mixin/AbstractClientPlayerMixin.java`, 21) — In a match players wear their role's skin: Michael, a survivor of the game, the police uniform.
- **AtmosphericFogMixin** (`src/client/java/dev/halloween/client/mixin/AtmosphericFogMixin.java`, 21) — A foggy match night: the fog closes in (see ClientWeather).
- **BiomeRainMixin** (`src/client/java/dev/halloween/client/mixin/BiomeRainMixin.java`, 23) — Haddonfield is filled with the badlands biome (for its autumn grass and leaves), where it never rains: on a rainy match night it rains there too.
- **CameraMixin** (`src/client/java/dev/halloween/client/mixin/CameraMixin.java`, 229) — Over-the-shoulder camera for the Shape, and the zoom while focusing in Killer Sense.
- **ChannelAccessor** (`src/client/java/dev/halloween/client/mixin/ChannelAccessor.java`, 11) — 
- **EntityMixin** (`src/client/java/dev/halloween/client/mixin/EntityMixin.java`, 41) — Outline colour per victim as the Shape sees it (see below); and while Michael speeds up, his own plain step sounds give way to dev.halloween.client.DashSteps.
- **EntityRenderDispatcherMixin** (`src/client/java/dev/halloween/client/mixin/EntityRenderDispatcherMixin.java`, 21) — The Shape vanishes completely (body, held item, shadow) during Shape Jump.
- **EntityRenderStateMixin** (`src/client/java/dev/halloween/client/mixin/EntityRenderStateMixin.java`, 23) — Render states remember whose they are (for the grab animations, see ClientAnims).
- **EntityRendererMixin** (`src/client/java/dev/halloween/client/mixin/EntityRendererMixin.java`, 19) — Each render state learns its entity's id as it is filled in.
- **GameRendererAccessor** (`src/client/java/dev/halloween/client/mixin/GameRendererAccessor.java`, 12) — 
- **ItemDisplayRendererMixin** (`src/client/java/dev/halloween/client/mixin/ItemDisplayRendererMixin.java`, 35) — The Demogorgon's rift rides on an item display: drawn as its own 3D model, not as the item (see RiftRenderer).
- **ItemInHandLayerMixin** (`src/client/java/dev/halloween/client/mixin/ItemInHandLayerMixin.java`, 52) — Michael's knife, held point down: as his arm goes up the wrist turns so the point still comes down first (the blocky arm can't bend at the elbow).
- **ItemInHandRendererMixin** (`src/client/java/dev/halloween/client/mixin/ItemInHandRendererMixin.java`, 27) — In a match survivors and the Runt get our hands in first person (see ViewArms), not vanilla's.
- **KeyMappingAccessor** (`src/client/java/dev/halloween/client/mixin/KeyMappingAccessor.java`, 15) — Presses passed on from the VR buttons to the keys they stand for (see VrKeys).
- **LevelExtractorMixin** (`src/client/java/dev/halloween/client/mixin/LevelExtractorMixin.java`, 33) — Vecna looking through someone's Eyes still sees himself (the game draws its own player only from his own camera).
- **LightmapExtractorMixin** (`src/client/java/dev/halloween/client/mixin/LightmapExtractorMixin.java`, 18) — A match night's darkness (see ClientDarkness).
- **LivingEntityRendererMixin** (`src/client/java/dev/halloween/client/mixin/LivingEntityRendererMixin.java`, 97) — Knocked down: the whole body goes over backwards from the feet, and comes up again (see ClientAnims).
- **LocalPlayerSprintMixin** (`src/client/java/dev/halloween/client/mixin/LocalPlayerSprintMixin.java`, 19) — Out of breath: the local player can't start running, and a run stops (see game.Stamina).
- **MinecraftMixin** (`src/client/java/dev/halloween/client/mixin/MinecraftMixin.java`, 30) — 
- **PlayerModelMixin** (`src/client/java/dev/halloween/client/mixin/PlayerModelMixin.java`, 22) — Michael's own walk and knife hand, then the grab animations (ClientAnims), go over the pose of players and townsfolk last.
- **RecipeToastMixin** (`src/client/java/dev/halloween/client/mixin/RecipeToastMixin.java`, 18) — No "new recipe" pop-ups: the items are props, nothing gets crafted (and the Shape gets his knife before the client knows a match is on).
- **SoundBufferLibraryMixin** (`src/client/java/dev/halloween/client/mixin/SoundBufferLibraryMixin.java`, 59) — Any of our clips still stored as WAV under an .ogg name loads with WavSounds; real Ogg takes the vanilla decoder.
- **SoundEngineAccessor** (`src/client/java/dev/halloween/client/mixin/SoundEngineAccessor.java`, 17) — 
- **SoundManagerAccessor** (`src/client/java/dev/halloween/client/mixin/SoundManagerAccessor.java`, 12) — 
- **VrFogMixin** (`src/client/java/dev/halloween/client/mixin/VrFogMixin.java`, 46) — Black between the scenes' shots in the headset: the fog closes in to the eyes and goes black (see VrShots).
