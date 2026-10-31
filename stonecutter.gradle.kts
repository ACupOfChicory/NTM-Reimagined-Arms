plugins {
    id("dev.kikugie.stonecutter")
    id("net.neoforged.moddev") version "2.0.148" apply false
}

stonecutter active "26.2"

// port/continuum: src/ is written for 26.2 (main's, vcsVersion), and 26.2 stays the active node: never switch the
// active node to 26.1, these renames are one-way. Each is closed by `(`, `.` or `;` so it touches nothing else.
stonecutter parameters {
    replacements {
        string(current.parsed < "26.2") {
            // Minecraft 26.1: the screen lives on Minecraft, not on Gui; camera and render target are getters
            replace(".gui.screen()", ".screen")
            replace(".gui.setScreen(", ".setScreen(")
            replace(".gameRenderer.mainCamera()", ".gameRenderer.getMainCamera()")
            replace(".gameRenderer.mainRenderTarget()", ".getMainRenderTarget()")
            // 26.1 keeps the entity type constants on EntityType
            replace("import net.minecraft.world.entity.EntityTypes;", "import net.minecraft.world.entity.EntityType;")
            replace("EntityTypes.", "EntityType.")
            // Minecraft 26.1's light, game render state and render pass forms
            replace("LightCoordsUtil.getLightCoords(", "net.minecraft.client.renderer.LevelRenderer.getLightCoords(")
            replace("Minecraft.getInstance().gameRenderer.gameRenderState().useShaderTransparency()", "Minecraft.useShaderTransparency()")
            replace("Optional.of(new org.joml.Vector4f())", "java.util.OptionalInt.of(0)")
            replace("pass.drawIndexed(count,1,0,0,0)", "pass.drawIndexed(0,0,count,1)")
            replace("pass.setVertexBuffer(0,maskBuffer.slice())", "pass.setVertexBuffer(0,maskBuffer)")
            replace(",stencil,GpuFormat.RGBA8_UNORM)", ",stencil)")
            replace("import com.mojang.blaze3d.GpuFormat;", "import com.mojang.blaze3d.pipeline.TextureTarget;")
            // 26.2's depth is reversed (near 1, far 0), 26.1's is not: depth tests, biases and clears turn round as
            // vanilla's own do (bullet holes; the F3+B visibility probe). The F3+B line shader is continuum/src's own
            replace("new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false, 1.0F, 10.0F)", "new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false, -1.0F, -10.0F)")
            replace("target.getDepthTextureView(),OptionalDouble.of(0))) {}", "target.getDepthTextureView(),OptionalDouble.of(1))) {}")
            replace("clearDepthTexture(terrain.getDepthTexture(),0)", "clearDepthTexture(terrain.getDepthTexture(),1)")
            // 26.1 names samplers on the pipeline, not bind group layouts
            replace("import net.minecraft.client.renderer.BindGroupLayouts;", "import net.minecraft.client.renderer.RenderPipelines;")
            replace(".withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1)", ".withSampler(\"Sampler0\").withSampler(\"Sampler1\")")
            replace(".withBindGroupLayout(BindGroupLayouts.SAMPLER1)", ".withSampler(\"Sampler1\")")
            replace(".withPrimitiveTopology(com.mojang.blaze3d.PrimitiveTopology.TRIANGLES)",
                ".withVertexFormat(DefaultVertexFormat.POSITION_COLOR,VertexFormat.Mode.TRIANGLES)")
            // 26.1 has VertexFormat.Mode where 26.2 has PrimitiveTopology
            replace("import com.mojang.blaze3d.PrimitiveTopology;", "import com.mojang.blaze3d.vertex.VertexFormat;")
            replace("com.mojang.blaze3d.PrimitiveTopology.", "com.mojang.blaze3d.vertex.VertexFormat.Mode.")
            replace(".primitiveTopology()", ".mode()")
            replace("PrimitiveTopology topology", "VertexFormat.Mode topology")
            replace("topology!=PrimitiveTopology.QUADS && topology!=PrimitiveTopology.TRIANGLES",
                "topology!=VertexFormat.Mode.QUADS && topology!=VertexFormat.Mode.TRIANGLES")
            replace("topology==PrimitiveTopology.QUADS", "topology==VertexFormat.Mode.QUADS")
            replace(",PrimitiveTopology.QUADS,", ",VertexFormat.Mode.QUADS,") // SubmittedModelGeometryTest
            // compat tests: this node's mods.toml, mixin lists and NTM API pin are in continuum/src/main/resources, and
            // Continuum's jar has no Implementation-Version (its mods.toml has the version)
            replace("Path.of(\"src/main/resources\", ApiProbe.FILE)", "Path.of(\"continuum/src/main/resources\", ApiProbe.FILE)")
            replace("static final Path RES = Path.of(\"src/main/resources\");\n    static final String LEGO", "static final Path RES = Path.of(\"continuum/src/main/resources\");\n    static final String LEGO")
            replace("return jar.getManifest().getMainAttributes().getValue(\"Implementation-Version\");",
                "return new String(jar.getInputStream(jar.getEntry(\"META-INF/neoforge.mods.toml\")).readAllBytes()).lines().filter(l -> l.startsWith(\"version\")).findFirst().orElseThrow().replaceAll(\"[^0-9.]\", \"\");")
            replace("Files.readString(RES.resolve(\"assets/ntmgunfeel/lang/\"", "Files.readString(Path.of(\"src/main/resources\").resolve(\"assets/ntmgunfeel/lang/\"")
            replace("void renamedMethodTurnsOffOnlyItsSystem()", "@org.junit.jupiter.api.Disabled(\"port/continuum: MixinLegoSpread is NTM NEXT's\") void renamedMethodTurnsOffOnlyItsSystem()")
            // the tracer mixin of this node (Compat turns TRACERS off when it does not fit)
            replace("List.of(\"MixinLegoClient\", \"MixinRenderBulletMK4\")", "List.of(\"MixinTracerRibbon\")")
            replace("Set.of(\"MixinLegoClient\", \"MixinRenderBulletMK4\")", "Set.of(\"MixinTracerRibbon\")")
            // the recoil and sway mixins of this node: GunRecoil and WeaponRenderer (WeaponPoseAccess measures the gun)
            replace("List.of(\"MixinItemGunBaseNT\", \"MixinHbmAnimations\")", "List.of(\"MixinGunRecoil\", \"MixinWeaponRenderer\", \"WeaponPoseAccess\")")
            replace("List.of(\"MixinItemRenderWeaponBase\", \"MixinWholeModelGuns\", \"MixinSexyBottle\", \"MixinHbmAnimations\", \"MixinG3Inspect\")",
                // MixinGunAnimationHandler (the arms' animation cue) is INSTANT_SWITCH's: a mixin of two systems would take the
                // other one down with it
                "List.of(\"MixinWeaponRenderer\", \"MixinHbmAnimations\", \"MixinWholeModelGuns\", \"MixinSexyBottle\", \"MixinG3Inspect\")")
            replace("Map.of(\"MixinHbmAnimations\", List.of(\"gone\"))", "Map.of(\"MixinWeaponRenderer\", List.of(\"gone\"))")
            replace("r.skipped().containsKey(\"MixinItemGunBaseNT\")", "r.skipped().containsKey(\"MixinGunRecoil\")")
            // Feature still names NTM NEXT's mixins for the systems not ported yet (absent classes are not checked)
            replace("void everyNtmMixinBelongsToASystem()", "@org.junit.jupiter.api.Disabled(\"port/continuum: Feature still names NTM NEXT's mixins\") void everyNtmMixinBelongsToASystem()")
            // RenderType.prepare() and PreparedRenderType are 26.2's: Prepared261 (continuum/src) does it on 26.1
            replace("net.minecraft.client.renderer.rendertype.PreparedRenderType", "acup.gunfeel.Prepared261")
            replace("new PreparedRenderType", "new Prepared261")
            replace("List<PreparedRenderType.Texture>", "List<Prepared261.Texture>")
            replace("TYPE.prepare()", "Prepared261.of(TYPE)")
            replace("type.prepare()", "acup.gunfeel.Prepared261.of(type)")
            // the F3+B outlines without instancing (ModelDebugMask, ModelDebugLines): main's legacy path
            replace("public static final boolean LEGACY=ENABLED && Boolean.getBoolean(\"ntmgunfeel.benchLegacy\");",
                "public static final boolean LEGACY=true;")
            replace(" e.registerPipeline(ModelDebugMask.PIPELINE); e.registerPipeline(ModelDebugLines.PIPELINE);", " /* 26.1: no instanced outline pipelines */")
            // RenderedMesh.Recorder is a SubmitNodeCollector: 26.1's has no shape outlines, gizmos or quad particle groups,
            // other name tag, moving and breaking block forms, and an abstract submitModelPart, captured as a model is
            replace("import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives.Group;", "import net.minecraft.client.model.geom.ModelPart;")
            replace("Component name, boolean seeThrough, int light, CameraRenderState camera) {",
                "Component name, boolean seeThrough, int light, double distanceSq, CameraRenderState camera) {")
            replace("public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState state, int outline) {",
                "public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState state) {")
            replace("public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress) {",
                "public void submitBreakingBlockModel(PoseStack poseStack, net.minecraft.client.renderer.block.dispatch.BlockStateModel model, long seed, int progress) {")
            replace("@Override\n        public void submitShapeOutline(", "@SuppressWarnings(\"unused\")\n        public void submitShapeOutline(")
            replace("public void submitQuadParticleGroup(QuadParticleRenderState particles) {",
                "public void submitParticleGroup(SubmitNodeCollector.ParticleGroupRenderer particles) {")
            replace("public void submitGizmoPrimitives(Group group, CameraRenderState camera, boolean onTop) {",
                "public void submitModelPart(ModelPart part, PoseStack poseStack, RenderType type, int light, int overlay, @Nullable TextureAtlasSprite sprite, " +
                "boolean sheeted, boolean foil, int tint, @Nullable CrumblingOverlay crumbling, int outline) { PoseStack.Pose pose = poseStack.last().copy(); " +
                "parts.add((out, o) -> { PoseStack ps = new PoseStack(); ps.last().pose().set(pose.pose()); ps.last().normal().set(pose.normal()); " +
                "Capture c = new Capture(type, sprite != null ? spriteTex(sprite) : texOf(type), o, out); part.render(ps, c, light, overlay, tint); c.finish(); });")
            // NTM Continuum: the gun animation enum and the bus animation moved (Golem Core holds the bus)
            replace("com.hbm.render.anim.AnimationEnums.GunAnimation", "com.hbm.items.weapon.sedna.GunAnimation")
            replace("com.hbm.render.anim.BusAnimationSequence", "dev.golem.core.render.anim.BusAnimationSequence")
            replace("com.hbm.render.anim.BusAnimationKeyframe", "dev.golem.core.render.anim.BusAnimationKeyframe")
            replace("com.hbm.render.anim.BusAnimation;", "dev.golem.core.render.anim.BusAnimation;")
            // Continuum folds its multiblock cores: one lookup by position serves client and server (Cells)
            replace("MultiblockSurface.clientCoreOf(level, pos) : MultiblockSurface.coreOfAny(level, pos, state)",
                "MultiblockSurface.coreOfAny(level, pos) : MultiblockSurface.coreOfAny(level, pos)")
            // the ammo item of a bullet config (ExplosiveAmmo)
            replace("config.ammoItem", "config.ammo")
            // Continuum numbers bullet configs in BulletConfigs (their id is an Identifier); the packets keep the index
            replace("BulletConfig.configs.size()", "com.hbm.items.weapon.sedna.BulletConfigs.all().size()")
            replace("BulletConfig.configs.get(", "com.hbm.items.weapon.sedna.BulletConfigs.byIndex(")
            // Continuum's bullet tracers are in TracerRegistry; MixinTracerRibbon reports the colours while probing
            replace("import com.hbm.client.render.RenderBulletMK4;", "import com.hbm.client.render.projectile.BulletProjectileRenderer;")
            replace("Field f = RenderBulletMK4.class.getDeclaredField(\"RENDERERS\");\n            f.setAccessible(true);\n            RenderBulletMK4.Tracer tracer = ((Map<BulletConfig, RenderBulletMK4.Tracer>) f.get(null)).get(config);",
                "var tracer = com.hbm.client.render.projectile.TracerRegistry.bullet(config.id);")
            replace("RenderBulletMK4.State state = new RenderBulletMK4.State();", "BulletProjectileRenderer.State state = new BulletProjectileRenderer.State();")
            // MixinBulletServer and BulletServer: Continuum's bullet is not an EntityThrowableNT. Its step per tick is step()
            // (NEXT's motionMult), its age tickCount, its shooter getThrower(); the packets carry the BulletConfigs index
            replace("@Shadow\n    protected abstract double motionMult();",
                "@Shadow\n    public abstract double step();\n\n    @Unique\n    private double motionMult() {\n        return step();\n    }")
            replace("@Inject(method = \"motionMult\", at = @At(\"RETURN\"), cancellable = true)", "@Inject(method = \"step\", at = @At(\"RETURN\"), cancellable = true)")
            replace("self.ticksInAir", "self.tickCount")
            replace("self.getOwner()", "self.getThrower()")
            replace("bullet.getOwner()", "bullet.getThrower()")
            replace("new ShotPayload(config.id,", "new ShotPayload(com.hbm.items.weapon.sedna.BulletConfigs.indexOf(config),")
            // TraceScenario: Continuum's animations are com.hbm.client.anim.HbmAnimations, and a gun animation plays as
            // GunAnimationHandler handles the server's GunAnimationPayload (NEXT: HbmAnimationPayload.handleSedna)
            replace("import com.hbm.render.anim.HbmAnimations;", "import com.hbm.client.anim.HbmAnimations;")
            replace("com.hbm.packet.toclient.HbmAnimationPayload.handleSedna(player, player.getMainHandItem(), player.getInventory().getSelectedSlot(), type, 0, 0);",
                "com.hbm.client.anim.GunAnimationHandler.handle(new com.hbm.packet.GunAnimationPayload(type.ordinal(), 0, 0, " +
                "net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString()));")
            // 26.1 hands the model renderer a SubmitNodeStorage.ModelSubmit
            replace("ModelFeatureRenderer.Submit<?>", "net.minecraft.client.renderer.SubmitNodeStorage.ModelSubmit<?>")
            // NTM Continuum draws first-person guns with Golem Core's Gem, which has no static interp
            replace("float partial=ItemRenderWeaponBase.interp;", "float partial=Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);")
            replace("import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;", "import net.minecraft.client.Minecraft;")
            // port/continuum-hud: the corner HUD replaces Continuum's GunHudOverlay.render (NTM NEXT:
            // ClientRegistry.renderGunHudComponents); MixinClientRegistry keeps its name, Feature.HUD is the same
            replace("import com.hbm.client.ClientRegistry;", "import com.hbm.client.GunHudOverlay;")
            replace("@Mixin(value = ClientRegistry.class, remap = false)", "@Mixin(value = GunHudOverlay.class, remap = false)")
            replace("@Inject(method = \"renderGunHudComponents\", at = @At(\"HEAD\"), cancellable = true)",
                "@Inject(method = \"render\", at = @At(\"HEAD\"), cancellable = true)")
            // port/continuum-hud: Continuum's BulletConfig has toStack() for NEXT's ammoStack(); its ammo bags are
            // ItemAmmoBag.InventoryAmmoBag (slots, not a Container); belts keep their type as an id string
            replace(".ammoStack()", ".toStack()")
            replace("bag.getContainerSize()", "bag.getSlots()")
            replace("bag.getItem(j)", "bag.getStackInSlot(j)")
            replace("bag.removeItem(j, n)", "bag.extractItem(j, n, false)")
            replace("bag.setItem(j, ", "bag.setStackInSlot(j, ")
            replace("bag.getMaxStackSize(in)", "in.getMaxStackSize()")
            replace("bag.getMaxStackSize(left)", "left.getMaxStackSize()")
            replace("List<com.hbm.inventory.ItemStackContainer>", "List<com.hbm.items.tool.ItemAmmoBag.InventoryAmmoBag>")
            replace("int id = MagazineBelt.getMagType(stack);",
                "int id = com.hbm.items.weapon.sedna.BulletConfigs.indexOf(com.hbm.items.weapon.sedna.BulletConfigs.get(MagazineBelt.getMagType(stack)));")
            replace("MagazineBelt.setMagType(stack, id);", "MagazineBelt.setMagType(stack, target.id.toString());")
            // port/continuum-hud: the ammo chosen in the radial menu is kept on the gun by its id (Continuum's
            // BulletConfigs index is for one session only); the menu sends the index, as the server and client share it
            replace("int id = ItemGunBaseNT.getValueInt(stack, NEXT) - 1;",
                "int id = com.hbm.items.weapon.sedna.BulletConfigs.indexOf(com.hbm.items.weapon.sedna.BulletConfigs.get(ItemGunBaseNT.getValueString(stack, NEXT)));")
            replace("ItemGunBaseNT.setValueInt(gun, NEXT, 0)", "ItemGunBaseNT.setValueString(gun, NEXT, \"\")")
            replace("ItemGunBaseNT.setValueInt(stack, NEXT, 0)", "ItemGunBaseNT.setValueString(stack, NEXT, \"\")")
            replace("ItemGunBaseNT.setValueInt(stack, NEXT, id + 1)", "ItemGunBaseNT.setValueString(stack, NEXT, target.id.toString())")
            replace("ItemGunBaseNT.setValueInt(stack, AmmoLock.NEXT, 0)", "ItemGunBaseNT.setValueString(stack, AmmoLock.NEXT, \"\")")
            replace("send(new AmmoSelectPayload(c.id));", "send(new AmmoSelectPayload(com.hbm.items.weapon.sedna.BulletConfigs.indexOf(c)));")
            // port/continuum-hud: Continuum's gun keys are HbmKeybindsClient's, read from the physical keys;
            // MixinHbmKeysRadial keeps them up while the menu is open (NTM NEXT: MixinGunKeysRadial)
            replace("((acup.gunfeel.mixin.mc.KeyMappingAccess) (Object) com.hbm.client.Keybinds.GUN_PRIMARY).gunfeel\$setIsDown(false);",
                "// Continuum: MixinHbmKeysRadial keeps GUN_PRIMARY up")
            replace("List.of(\"MixinAmmoLock\", \"MixinAmmoLockBelt\", \"MixinGunKeysRadial\")",
                "List.of(\"MixinAmmoLock\", \"MixinAmmoLockBelt\", \"MixinHbmKeysRadial\")")
            // port/continuum-hud: Continuum's getFirstConfig picks the ammo through acceptedConfig, which reads acceptedBullets
            replace("@Redirect(method = {\"standardReload\", \"getFirstConfig\"}, at = @At(value = \"FIELD\", opcode = 180 /* GETFIELD */,",
                "@Redirect(method = {\"standardReload\", \"acceptedConfig\"}, at = @At(value = \"FIELD\", opcode = 180 /* GETFIELD */,")
            // port/continuum-hud: instantSwitch. Continuum has no akimbo ghost item; the client starts the draw by
            // handing GunAnimationHandler the payload the server would send (MixinGunAnimationHandler, NEXT's
            // MixinHbmAnimationPayload, tells the two apart by serverPacket); 26.1's ItemInHandRenderer method name
            replace("import com.hbm.items.weapon.sedna.AkimboGhost;", "import com.hbm.client.anim.GunAnimationHandler;")
            replace("stack.getItem() instanceof ItemGunBaseNT && !AkimboGhost.isGhost(stack);", "stack.getItem() instanceof ItemGunBaseNT;")
            replace("import com.hbm.packet.toclient.HbmAnimationPayload;", "import com.hbm.packet.GunAnimationPayload;")
            replace("public static boolean serverPacket;", "public static boolean serverPacket = true;")
            replace("for (int i = 0; i < n; i++) HbmAnimationPayload.handleSedna(player, stack, slot, GunAnimation.EQUIP, 0, i);",
                "String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString(); serverPacket = false; " +
                "try { for (int i = 0; i < n; i++) GunAnimationHandler.handle(new GunAnimationPayload(GunAnimation.EQUIP.ordinal(), 0, i, " +
                "net.minecraft.world.InteractionHand.MAIN_HAND, itemId)); } finally { serverPacket = true; }")
            replace("List.of(\"MixinHbmAnimationPayload\")", "List.of(\"MixinGunAnimationHandler\")")
            replace("@Inject(method = \"submitHandsWithItems\", at = @At(\"HEAD\"))", "@Inject(method = \"renderHandsWithItems\", at = @At(\"HEAD\"))")
            // port/continuum-mobs: NTM Continuum's OBJ mobs draw WavefrontObj groups, not NTM NEXT's GroupObject: NtmMeshHits
            // and ModelMeshCapture take continuum/src's ObjGroup (the group's name, quads and bounds, as GroupObject's)
            replace("import java.util.UUID;\nimport com.hbm.render.loader.GroupObject;", "import java.util.UUID;")
            replace("public static void group(GroupObject group,", "public static void group(ObjGroup group,")
            replace("import com.hbm.render.loader.GroupObject;\nimport java.util.ArrayList;", "import java.util.ArrayList;")
            replace("Map<GroupObject, ModelMesh>", "Map<ObjGroup, ModelMesh>")
            replace("Map<GroupObject,Map<Key,ModelSurfaceGeometry>>", "Map<ObjGroup,Map<Key,ModelSurfaceGeometry>>")
            replace("geometry(GroupObject group,net.minecraft.resources.Identifier texture,", "geometry(ObjGroup group,net.minecraft.resources.Identifier texture,")
            replace("ModelMesh mesh(GroupObject group)", "ModelMesh mesh(ObjGroup group)")
            // port/continuum-mobs: 26.1's ModelSubmit has no render type, renderModel gets it apart (MixinRenderedModelHit
            // hands it to ModelSubmit261 while NtmMeshHits reads the submit)
            replace("(net.minecraft.client.renderer.feature.ModelFeatureRenderer.Submit<?> submit)", "(net.minecraft.client.renderer.SubmitNodeStorage.ModelSubmit<?> submit)")
            replace("submit.renderType()", "ModelSubmit261.renderType()")
            // port/continuum-mobs: the model hitbox mixins of this node (NTM NEXT's Flywheel mob visuals have none here)
            replace("List.of(\"MixinNtmMeshHits\", \"GlyphidGeometryAccess\", \"MixinGlyphidModelHits\", \"MixinMobObjModelHits\", \"MixinMobModelHits\", \"MixinGpuEntityGeometry\", \"MixinHeadDamage\")",
                "List.of(\"MixinNtmMeshHits\", \"ObjGroupAccess\", \"MixinBeamPronterHits\", \"MixinHeadDamage\")")
            // port/continuum-mobs: 26.1's SubmitNodeCollection has no afterTerrain list: LateHoles261 keeps the late holes (on
            // glass) and draws them after the translucent terrain (RenderLevelStageEvent.AfterTranslucentBlocks)
            replace("((SubmitNodeCollection) (Object) this).afterTerrain.submit(new CustomFeatureRenderer.Submit(poseStack.last().copy(), renderType, renderer));",
                "acup.gunfeel.LateHoles261.submit(poseStack.last().copy(), renderType, renderer);")
            // port/continuum-bullets: ExplosiveDamage on Continuum: the hole packet carries the BulletConfigs index, and a
            // bullet's damage source is ModDamageSources.bullet (NEXT: BulletConfig.getDamage)
            replace("new ImpactHolePayload(bullet.config.id,", "new ImpactHolePayload(com.hbm.items.weapon.sedna.BulletConfigs.indexOf(bullet.config),")
            replace("BulletConfig.getDamage(bullet,bullet.getThrower(),bullet.config.dmgClass)",
                "com.hbm.lib.ModDamageSources.bullet(bullet.level(),bullet.config.dmgClass.type(),bullet,bullet.getThrower())")
            // port/continuum-bullets: MixinHoldAim: Continuum's gun ticks in Golem Core's compatInventoryTick (with held)
            replace("@Inject(method = \"inventoryTick\", at = @At(\"TAIL\"))\n    private void gunfeel\$reaim(ItemStack stack, ServerLevel level, Entity entity, @Nullable EquipmentSlot slot, CallbackInfo ci) {",
                "@Inject(method = \"compatInventoryTick\", at = @At(\"TAIL\"))\n    private void gunfeel\$reaim(ItemStack stack, ServerLevel level, Entity entity, @Nullable EquipmentSlot slot, boolean held, CallbackInfo ci) {")
            // port/continuum-bullets: BulletCheck (main's runCheckBullets; here on the 26.1 client's server with
            // -Pntmgunfeel.checkBullets): Continuum's bullet heading is a unit vector set by shoot (no velocity argument),
            // its beam hit is XFactoryEnergy's, its entities ModNtmEntities, its bullet configs BulletConfigs.all(); a
            // block state Continuum names otherwise (its doors, barriers, machines) is taken as the block's default
            replace("bullet.setThrowableHeading(0,0,1,1F,0F);", "bullet.shoot(0,0,1,0F);")
            replace("bullet.setThrowableHeading(0, 0, 1, du.velocity, 0F);", "bullet.shoot(0, 0, 1, 0F);")
            replace("b.setThrowableHeading(0, 0, 1, round.velocity, 0F);", "b.shoot(0, 0, 1, 0F);")
            replace("b.setThrowableHeading(0, 0, 1, du.velocity, 0F);", "b.shoot(0.0, 0.0, 1.0, 0F);")
            replace("b.setThrowableHeading(0, -1, 1, du.velocity, 0F);", "b.shoot(0, -1, 1, 0F);")
            replace("setOnBeamImpact(BulletConfig.LAMBDA_STANDARD_BEAM_HIT)", "setOnBeamImpact(com.hbm.items.weapon.sedna.factory.XFactoryEnergy.LAMBDA_STANDARD_BEAM_HIT)")
            replace("com.hbm.entity.ModEntities.RAD_BEAST.", "com.hbm.entity.ModNtmEntities.RAD_BEAST.")
            replace("com.hbm.entity.ModEntities.MASK_MAN.", "com.hbm.entity.ModNtmEntities.MASK_MAN.")
            replace("for (BulletConfig c : BulletConfig.configs) {", "for (BulletConfig c : com.hbm.items.weapon.sedna.BulletConfigs.all()) {")
            replace("throw new IllegalArgumentException(text, e);",
                "lines.add(\"     port: no state \" + text + \" in NTM Continuum, the block's default\");\n" +
                "            try {\n                return BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(Registries.BLOCK), text.replaceAll(\"\\\\[.*\", \"\"), false).blockState();\n" +
                "            } catch (Exception again) {\n                throw new IllegalArgumentException(text, again);\n            }")
            // port/continuum-bullets: WoodBoxes: Continuum's wooden barrier is BlockBarrier, its wooden structures name their
            // kind type; both draw their boards by their block state's sides (north/east/south/west/up), refreshed as the
            // neighbours change, the same boards as NTM NEXT's model for the same sides
            replace("import com.hbm.blocks.generic.BlockWoodBarrier;", "import com.hbm.blocks.generic.BlockBarrier;")
            replace("state.getBlock() instanceof BlockWoodBarrier)", "state.getBlock() instanceof BlockBarrier)")
            replace("BlockWoodBarrier.panel(state, Direction.WEST)", "state.getValue(BlockBarrier.WEST)")
            replace("BlockWoodBarrier.panel(state, Direction.EAST)", "state.getValue(BlockBarrier.EAST)")
            replace("BlockWoodBarrier.panel(state, Direction.NORTH)", "state.getValue(BlockBarrier.NORTH)")
            replace("BlockWoodBarrier.panel(state, Direction.SOUTH)", "state.getValue(BlockBarrier.SOUTH)")
            replace("(level.getBlockState(pos.above()).isSolidRender() ? 16 : 0)", "(state.getValue(BlockBarrier.UP) ? 16 : 0)")
            replace("switch (w.kind) {", "switch (w.type) {")
            replace("same(level, pos, state, Direction.WEST)", "state.getValue(BlockWoodStructure.WEST)")
            replace("same(level, pos, state, Direction.EAST)", "state.getValue(BlockWoodStructure.EAST)")
            replace("same(level, pos, state, Direction.NORTH)", "state.getValue(BlockWoodStructure.NORTH)")
            replace("same(level, pos, state, Direction.SOUTH)", "state.getValue(BlockWoodStructure.SOUTH)")
            replace("same(level, pos, state, Direction.UP)", "state.getValue(BlockWoodStructure.UP)")
            // port/continuum-bullets: BulletCheck: Continuum's barrier draws its panel by its side flags, which placing it
            // sets (the side it faces) and setBlock does not. NEXT's barrier facing north has its panel on the south side,
            // Continuum's on the side it faces: the check's barrier is the one with the same panel
            replace("single(\"hbm:wood_barrier[facing=north]\")", "single(\"hbm:wood_barrier[facing=south,south=true]\")")
            // port/continuum-bullets: BulletCheck on the client's own server: it runs before the server's first tick, so the
            // chunks it forces are not loaded yet and the creatures it adds there stay hidden from entity lookups (which
            // ones, depends on how far the loading got: two runs differed). Its chunks, and those its creatures are
            // moved to, are loaded at once
            replace("for (int cx = 0; cx < 4; cx++) for (int cz = 0; cz < 4; cz++) level.setChunkForced(cx, cz, true);",
                "for (int cx = 0; cx < 8; cx++) for (int cz = 0; cz < 8; cz++) {\n            level.setChunkForced(cx, cz, true);\n            level.getChunk(cx, cz);\n        }")
            // port/continuum-arms: Arms, GunGeometry and ArmAnimationPreview on NTM Continuum. The gun's OBJ groups are
            // read from Continuum's WavefrontObj through continuum/src acup.gunfeel.obj (NEXT's class names, the same API)
            replace("import com.hbm.render.loader.HFRWavefrontObject;\nimport com.mojang.blaze3d.vertex.PoseStack;\n\nimport net.minecraft.client.Minecraft;",
                "import acup.gunfeel.obj.HFRWavefrontObject;\nimport com.mojang.blaze3d.vertex.PoseStack;\n\nimport net.minecraft.client.Minecraft;")
            replace("import com.hbm.render.loader.GroupObject;\nimport com.hbm.render.loader.HFRWavefrontObject;\n\n// Geometry",
                "import acup.gunfeel.obj.GroupObject;\nimport acup.gunfeel.obj.HFRWavefrontObject;\n\n// Geometry")
            // Arms draws into Golem Core's SubmitSink (WeaponRenderer's collector): the player's arm as custom geometry
            replace("public static void end(ItemStack stack, SubmitNodeCollector collector, int light, boolean drawArms) {",
                "public static void end(ItemStack stack, dev.golem.core.render.SubmitSink collector, int light, boolean drawArms) {")
            replace("private static void draw(SubmitNodeCollector collector, int light) {", "private static void draw(dev.golem.core.render.SubmitSink collector, int light) {")
            replace("private static void arm(SubmitNodeCollector collector, int light, ModelPart arm,", "private static void arm(dev.golem.core.render.SubmitSink collector, int light, ModelPart arm,")
            replace("private static void submit(SubmitNodeCollector collector, int light, ModelPart arm,", "private static void submit(dev.golem.core.render.SubmitSink collector, int light, ModelPart arm,")
            replace("net.minecraft.client.renderer.SubmitNodeCollector NOWHERE = (net.minecraft.client.renderer.SubmitNodeCollector)",
                "dev.golem.core.render.SubmitSink NOWHERE = (dev.golem.core.render.SubmitSink)")
            replace("{net.minecraft.client.renderer.SubmitNodeCollector.class}", "{dev.golem.core.render.SubmitSink.class}")
            replace("collector.submitModelPart(arm, pose, RenderTypes.entityTranslucent(skin), light, OverlayTexture.NO_OVERLAY, null);",
                "final ModelPart drawn = arm;\n        collector.submitCustomGeometry(pose, RenderTypes.entityTranslucent(skin), (at, buffer) -> {\n" +
                "            PoseStack ps = new PoseStack();\n            ps.last().set(at);\n            drawn.render(ps, buffer, light, OverlayTexture.NO_OVERLAY);\n        });")
            // Continuum's animations do not keep their cue (AnimCue), and the hand's projection is vanilla's 70 degrees
            replace("HbmAnimations.getGunAnimation(", "acup.gunfeel.AnimCue.of(")
            replace("com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase.adjustHudFov(70F)", "70F")
            // ArmAnimationPreview: an animation is keyed by its Item; a gun's own table answers when its config has none
            replace("stack.getItem().getDescriptionId(), com.hbm.util.GameTime.now()", "stack.getItem(), com.hbm.util.GameTime.now()")
            replace("var factory = gun.getConfig(stack,0).getAnims(stack);\n        if (factory == null) return;\n        var buses = factory.apply(stack,cue);",
                "var factory = gun.getConfig(stack,0).getAnimation(stack);\n        var buses = factory == null ? null : factory.apply(stack,cue);\n" +
                "        if (buses == null) buses = com.hbm.client.anim.GunAnimations.get(stack, cue, 0);")
            replace("HbmAnimations.identity()", "new double[] {0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 1, 2}")
            // the arms' own classes on this node belong to ARMS (NtmApiTest, Compat): the cue, the Gem walk, the dev check, the OBJ view
            replace("List.of(\"Arms\", \"Grips\", \"GunGeometry\", \"GripCommand\", \"ArmAnimationPreview\", \"ArmAnimationEditor\", \"WeaponSway\")",
                "List.of(\"Arms\", \"Grips\", \"GunGeometry\", \"GripCommand\", \"ArmAnimationPreview\", \"ArmAnimationEditor\", \"WeaponSway\", " +
                "\"AnimCue\", \"ArmsGem\", \"ArmsSmoke\", \"HFRWavefrontObject\", \"GroupObject\")")
            // port/continuum-hud: the HUD's gun silhouette rasterises GunGeometry's groups, Continuum's WavefrontObj through acup.gunfeel.obj
            replace("import com.hbm.render.loader.GroupObject;\nimport com.mojang.blaze3d.platform.NativeImage;",
                "import acup.gunfeel.obj.GroupObject;\nimport com.mojang.blaze3d.platform.NativeImage;")
        }
    }
}
