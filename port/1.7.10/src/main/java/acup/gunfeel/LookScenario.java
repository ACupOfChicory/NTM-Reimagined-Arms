package acup.gunfeel;

import java.io.File;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.World;

// A dev scenario for looking (gradle runClient -Pscenario=look): in ShootScenario's flat world a wall of stone, glass
// and iron bars (stone behind them) ahead and a zombie that stands still to the side (under a stone roof, so the sun does not burn it). The
// player fires gun_maresleg and a burst of gun_am180 at the wall, then turns to the zombie with F3+B on and fires at
// it. The game saves screenshots at the moments to look at (run/client/screenshots/look-*.png): the HUD, the tracers
// in flight (look-sky-3: a shot into the sky seen from aside), the shots at the wall and their holes, the zombie's
// model outline with its head zone. Nothing is checked: the pictures are for eyes.
final class LookScenario {
    private LookScenario() {
    }

    private static final int BUILD = 5, GIVE = 40, HUD = 60, SKY = 70, SHOT = 100, HOLES = 140, SWITCH = 160, BURST = 180,
            BURST_TICKS = 20, HOLES2 = 240, TURN = 260, ZOMBIE = 280, AT_ZOMBIE = 300, RADIAL = 330, MACHINE = 390, DOOR = 470, STANDS = 650, STAND_TICKS = 60, DONE = 1040;

    // the zombie's place relative to the player's feet: 3 to the left (+x, the player looks along +z), 7 ahead
    private static final int ZX = 3, ZZ = 7;
    // the press (an NTM machine drawn by its renderer, for the holes on it: MachineMesh): its core 6 to the right
    // (-x), 5 ahead
    private static final int MX = -6, MZ = 5;
    private static int[] feet;
    // the sliding blast door (its leaves slide aside when it opens: the holes on them go along), 10 to the left, 14 ahead
    private static final int DX = 10, DZ = 14;
    private static int[] door;
    // NTM blocks whose renderer draws them or a part without OBJ models (MachineMesh: ModelRenderer, the Tessellator: the
    // pumpjack's cables, the tank's fluid gauge, the crucible's molten metal), in a row
    // 14 behind the player, 10 apart: the block, how high on it to aim
    private static final String[] STAND = {"hbm:tile.pole_satellite_receiver", "hbm:tile.radiobox", "hbm:tile.steel_roof", "hbm:tile.machine_pumpjack",
            "hbm:tile.machine_bat9000", "hbm:tile.machine_crucible"};
    private static final double[] STAND_AIM = {0.6, 0.5, 0.1, 1.5, 2.4, 1.3};
    // where the player stands: how far in front, how high (on a stone pillar: the crucible's metal is seen from above)
    private static final int[] STAND_BACK = {8, 8, 8, 8, 8, 4}, STAND_UP = {0, 0, 0, 0, 0, 4};
    private static final int SZ = -14;

    // the held gun's rounds after the ammo menu's choice, as the server has them (a shell-by-shell gun reloads to full)
    private static void reloadState(Minecraft mc) {
        final int slot = mc.thePlayer.inventory.currentItem;
        ShootScenario.onServer(() -> {
            EntityPlayerMP player = (EntityPlayerMP) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
            net.minecraft.item.ItemStack stack = player.inventory.getStackInSlot(slot);
            com.hbm.items.weapon.sedna.mags.IMagazine<?> mag = AmmoLock.primary(stack);
            GunFeel.LOGGER.info("Scenario look: after the ammo choice {} of {} rounds, {}", mag == null ? -1 : mag.getAmount(stack, null),
                    mag == null ? -1 : mag.getCapacity(stack), com.hbm.items.weapon.sedna.ItemGunBaseNT.getState(stack, 0));
        });
    }

    static void step(Minecraft mc, int t) {
        if (t == BUILD) {
            // with -Dntmgunfeel.trace (runClient -Pscenario sets it) the run is written too: what the client got
            Trace.recording = true;
            final int px = (int) Math.floor(mc.thePlayer.posX), py = (int) Math.floor(mc.thePlayer.boundingBox.minY),
                    pz = (int) Math.floor(mc.thePlayer.posZ);
            feet = new int[] {px, py, pz};
            ShootScenario.onServer(() -> build(px, py, pz));
        } else if (t == GIVE) {
            ShootScenario.onServer(() -> {
                ShootScenario.give(0, "gun_maresleg");
                ShootScenario.give(1, "gun_am180");
                giveAmmo(2);
            });
            mc.thePlayer.inventory.currentItem = 0;
        } else if (t == HUD) {
            shot(mc, "hud");
        } else if (t == SKY - 1) {
            // over the wall, into the sky: the tracer flies for ticks instead of a part of one
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = -30F;
        } else if (t == SKY) {
            ShootScenario.trigger(true, mc);
        } else if (t > SKY && t <= SKY + 5) {
            if (t == SKY + 2) {
                ShootScenario.trigger(false, mc);
                // the bullets fly on along the shot; seen from 40 degrees aside, the tracers cross the picture
                // (straight ahead they point away from the eye and shrink to a dot at the crosshair)
                mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = 40F;
                mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = -20F;
            }
            shot(mc, "sky-" + (t - SKY));
        } else if (t == SHOT - 5) {
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = 0F;
            mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = 0F;
        } else if (t == SHOT) {
            ShootScenario.trigger(true, mc);
        } else if (t == SHOT + 1) {
            shot(mc, "wall-1");
        } else if (t == SHOT + 2) {
            ShootScenario.trigger(false, mc);
            shot(mc, "wall-2");
        } else if (t == HOLES) {
            shot(mc, "holes");
        } else if (t == SWITCH) {
            mc.thePlayer.inventory.currentItem = 1;
        } else if (t == BURST) {
            ShootScenario.trigger(true, mc);
        } else if (t == BURST + 5 || t == BURST + 12) {
            shot(mc, "burst-" + (t - BURST));
        } else if (t == BURST + BURST_TICKS) {
            ShootScenario.trigger(false, mc);
        } else if (t == HOLES2) {
            shot(mc, "holes-burst");
        } else if (t == TURN) {
            // F3+B, and the camera on the zombie's chest
            RenderManager.debugBoundingBox = true;
            double dx = ZX + .5 - (mc.thePlayer.posX - Math.floor(mc.thePlayer.posX)), dz = ZZ + .5 - (mc.thePlayer.posZ - Math.floor(mc.thePlayer.posZ));
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.toDegrees(Math.atan2(1.62 - 1.2, Math.sqrt(dx * dx + dz * dz)));
            mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = yaw;
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = pitch;
        } else if (t == ZOMBIE) {
            shot(mc, "zombie-f3b");
        } else if (t == AT_ZOMBIE) {
            mc.thePlayer.inventory.currentItem = 0;
        } else if (t == AT_ZOMBIE + 10) {
            ShootScenario.trigger(true, mc);
        } else if (t == AT_ZOMBIE + 11) {
            shot(mc, "zombie-shot");
        } else if (t == AT_ZOMBIE + 12) {
            ShootScenario.trigger(false, mc);
        } else if (t == AT_ZOMBIE + 20) {
            shot(mc, "zombie-after");
        } else if (t == RADIAL) {
            // the ammo menu: the key held (AmmoLockKey opens it after KeyHold.HOLD_MS), the pointer on a choice, a
            // click takes it
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = 0F;
            net.minecraft.client.settings.KeyBinding.setKeyBindState(AmmoLockKey.KEY.getKeyCode(), true);
        } else if (t == RADIAL + 10) {
            GunFeel.LOGGER.info("Scenario look: ammo menu open {}", RadialMenu.isOpen());
            shot(mc, "radial");
            RadialMenu.move(60, 0);
        } else if (t == RADIAL + 12) {
            com.hbm.items.weapon.sedna.BulletConfig h = RadialMenu.hovered();
            GunFeel.LOGGER.info("Scenario look: ammo menu points at {}", h == null ? "nothing" : h.id + " " + AmmoLock.ammoStack(h));
            shot(mc, "radial-hover");
        } else if (t == RADIAL + 14) {
            // the click as a player's: our handler takes it, then NTM asks its fire key on the same button (it has to
            // stay up: a shot during the reload the choice starts cancels it after one shell)
            AmmoLockKey.input(mc.gameSettings.keyBindAttack.getKeyCode(), true);
            ShootScenario.trigger(true, mc);
            net.minecraft.client.settings.KeyBinding.setKeyBindState(AmmoLockKey.KEY.getKeyCode(), false);
        } else if (t == RADIAL + 18) {
            ShootScenario.trigger(false, mc);
        } else if (t == RADIAL + 40) {
            GunFeel.LOGGER.info("Scenario look: ammo menu open {}, loaded now {}", RadialMenu.isOpen(),
                    ((com.hbm.items.weapon.sedna.BulletConfig) AmmoLock.primary(mc.thePlayer.getHeldItem()).getType(mc.thePlayer.getHeldItem(), null)).id);
            shot(mc, "radial-after");
            reloadState(mc);
        } else if (t == MACHINE + 9) {
            reloadState(mc);
        } else if (t == MACHINE) {
            final int[] f = feet;
            ShootScenario.onServer(() -> {
                press(f[0] + MX, f[1], f[2] + MZ);
                ShootScenario.give(3, "gun_am180"); // a loaded one: the ammo menu left the other to reload
            });
        } else if (t == MACHINE + 10) {
            // the camera on the press's body, the am180 in hand
            mc.thePlayer.inventory.currentItem = 3;
            double dx = feet[0] + MX + .5 - mc.thePlayer.posX, dz = feet[2] + MZ + .5 - mc.thePlayer.posZ;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.toDegrees(Math.atan2(1.62 - 1.0, Math.sqrt(dx * dx + dz * dz)));
            mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = yaw;
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = pitch;
        } else if (t == MACHINE + 35) {
            ShootScenario.trigger(true, mc);
        } else if (t == MACHINE + 45) {
            ShootScenario.trigger(false, mc);
        } else if (t == MACHINE + 60) {
            GunFeel.LOGGER.info("Scenario look: holes on machines {}", Tracers.machineHoles());
            shot(mc, "machine-holes");
        } else if (t == MACHINE + 62) {
            // close up: two blocks from the press, on the side the bullets came from
            final int[] f = feet;
            final double cx = f[0] + MX + .5, cz = f[2] + MZ + .5;
            final double ox = mc.thePlayer.posX - cx, oz = mc.thePlayer.posZ - cz, ol = Math.sqrt(ox * ox + oz * oz);
            final double x = cx + ox / ol * 2.6, z = cz + oz / ol * 2.6;
            final float yaw = (float) Math.toDegrees(Math.atan2(ox, -oz));
            ShootScenario.onServer(() -> {
                EntityPlayerMP player = (EntityPlayerMP) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
                player.playerNetServerHandler.setPlayerLocation(x, f[1], z, yaw, 8F);
            });
        } else if (t == MACHINE + 70) {
            shot(mc, "machine-close");
        } else if (t == DOOR) {
            final int[] f = feet;
            ShootScenario.onServer(() -> door = door(f[0] + DX, f[1], f[2] + DZ));
        } else if (t == DOOR + 10) {
            // in front of the door, facing it
            final int[] f = feet;
            ShootScenario.onServer(() -> {
                EntityPlayerMP player = (EntityPlayerMP) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
                player.playerNetServerHandler.setPlayerLocation(f[0] + DX + .5, f[1], f[2] + DZ - 7.5, 0F, -6F);
            });
        } else if (t == DOOR + 30) {
            ShootScenario.trigger(true, mc);
        } else if (t == DOOR + 38) {
            ShootScenario.trigger(false, mc);
        } else if (t == DOOR + 55) {
            GunFeel.LOGGER.info("Scenario look: door at {}, holes on machines {}", door == null ? "none" : door[0] + " " + door[1] + " " + door[2],
                    Tracers.machineHoles());
            shot(mc, "door-shut");
            MachineMesh.lagMax = 0;
            final int[] d = door;
            if (d != null) ShootScenario.onServer(() -> {
                World w = MinecraftServer.getServer().worldServerForDimension(0);
                ((com.hbm.tileentity.TileEntityDoorGeneric) w.getTileEntity(d[0], d[1], d[2])).open();
            });
        } else if (t == DOOR + 76) {
            shot(mc, "door-opening");
        } else if (t == DOOR + 150) {
            GunFeel.LOGGER.info("Scenario look: door open, holes on machines {}", Tracers.machineHoles());
            GunFeel.LOGGER.info("Scenario look: holes on the door lay at most {} blocks from their leaf while it opened",
                    String.format(java.util.Locale.ROOT, "%.3f", MachineMesh.lagMax));
            GunFeel.LOGGER.info("Scenario look: the parts of machines read in at most {} ms a frame (frames up to 0.5 ms {}, up to 1 ms {}, more {})",
                    String.format(java.util.Locale.ROOT, "%.2f", MachineMesh.readLongest / 1e6), MachineMesh.readFrames[0], MachineMesh.readFrames[1],
                    MachineMesh.readFrames[2]);
            shot(mc, "door-open");
        } else if (t >= STANDS && t < STANDS + STAND.length * STAND_TICKS) {
            stand(mc, (t - STANDS) / STAND_TICKS, (t - STANDS) % STAND_TICKS);
        } else if (t == DONE - 10) {
            // the systems' state in chat (the chat lines go to the log as [CHAT])
            for (String c : new String[] {"/gunfeel quality low", "/gunfeel quality", "/gunfeel quality high", "/gunfeel hitvolume", "/gunfeel settings",
                    "/gunfeel get cameraClimb", "/gunfeel set cameraClimb", "/gunfeel grip show", "/gunfeel clearholes", "/gunfeel status"})
                net.minecraftforge.client.ClientCommandHandler.instance.executeCommand(mc.thePlayer, c);
            mc.thePlayer.sendChatMessage("/gunaim"); // the server's (HOLD_AIM)
        } else if (t == DONE - 5) {
            shot(mc, "status");
        } else if (t == DONE) {
            RenderManager.debugBoundingBox = false;
            GunFeel.LOGGER.info("Scenario look: first-person arms drawn in {} frames", Arms.drawnFrames);
            GunFeel.LOGGER.info("Scenario look done: screenshots in {}", new File(mc.mcDataDir, "screenshots"));
            mc.shutdown();
        }
    }

    private static int sx(int i) {
        return feet[0] + (i - STAND.length / 2) * 10;
    }

    // one stand: placed (and the am180 given again) at 0, the player in front of it facing it at 10, a burst swept
    // across it from 20 to 32, the picture at 50
    private static void stand(Minecraft mc, final int i, int t) {
        final int[] f = feet;
        final int x = sx(i), z = f[2] + SZ;
        if (t == 0) {
            ShootScenario.onServer(() -> {
                place(STAND[i], x, f[1], z);
                ShootScenario.give(3, "gun_am180");
            });
        } else if (t == 10) {
            mc.thePlayer.inventory.currentItem = 3;
            ShootScenario.onServer(() -> {
                EntityPlayerMP player = (EntityPlayerMP) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
                int up = STAND_UP[i], back = STAND_BACK[i];
                for (int k = 0; k < up; k++) player.worldObj.setBlock(x, f[1] + k, z + back, Blocks.stone, 0, 3);
                player.playerNetServerHandler.setPlayerLocation(x + .5, f[1] + up, z + back + .5, 180F, 0F);
            });
        } else if (t >= 15 && t <= 32) {
            double dx = x + .5 - mc.thePlayer.posX, dz = z + .5 - mc.thePlayer.posZ;
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.toDegrees(Math.atan2(mc.thePlayer.posY - feet[1] - STAND_AIM[i], Math.sqrt(dx * dx + dz * dz)));
            // across the block while firing: -8 to +8 degrees
            if (t >= 20) yaw += -8F + 16F * (t - 20) / 12F;
            mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = yaw;
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = pitch;
            if (t == 20) ShootScenario.trigger(true, mc);
            if (t == 32) ShootScenario.trigger(false, mc);
        } else if (t == 50) {
            GunFeel.LOGGER.info("Scenario look: stand {} at {} {} {}, holes on machines {}", STAND[i], x, f[1], z, Tracers.machineHoles());
            shot(mc, "stand-" + STAND[i].substring(STAND[i].indexOf('.') + 1));
        }
    }

    // the integrated server: an NTM block placed at (x, y, z) as the player facing south would (BlockDummyable builds
    // its machine); a tank's fluid filled, for the gauge its renderer draws
    private static void place(String id, int x, int y, int z) {
        EntityPlayerMP player = (EntityPlayerMP) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
        World w = player.worldObj;
        net.minecraft.block.Block b = (net.minecraft.block.Block) net.minecraft.block.Block.blockRegistry.getObject(id);
        if (b == null || b == Blocks.air) {
            GunFeel.LOGGER.warn("Scenario look: no {}", id);
            return;
        }
        float yaw = player.rotationYaw;
        player.rotationYaw = 0F;
        try {
            w.setBlock(x, y, z, b, 0, 3);
            b.onBlockPlacedBy(w, x, y, z, player, new net.minecraft.item.ItemStack(b));
        } finally {
            player.rotationYaw = yaw;
        }
        for (int dx = -4; dx <= 4; dx++) for (int dy = -1; dy <= 6; dy++) for (int dz = -4; dz <= 4; dz++) {
            net.minecraft.tileentity.TileEntity te = w.getTileEntity(x + dx, y + dy, z + dz);
            if (te instanceof com.hbm.tileentity.machine.storage.TileEntityBarrel) {
                com.hbm.tileentity.machine.storage.TileEntityBarrel tank = (com.hbm.tileentity.machine.storage.TileEntityBarrel) te;
                tank.tank.setTankType(com.hbm.inventory.fluid.Fluids.WATER);
                tank.tank.setFill(tank.tank.getMaxFill() * 2 / 3);
                te.markDirty();
            }
            if (te instanceof com.hbm.tileentity.machine.TileEntityCrucible) {
                com.hbm.tileentity.machine.TileEntityCrucible c = (com.hbm.tileentity.machine.TileEntityCrucible) te;
                if (c.wasteStack.isEmpty()) c.wasteStack.add(new com.hbm.inventory.material.Mats.MaterialStack(com.hbm.inventory.material.Mats.MAT_IRON,
                        com.hbm.tileentity.machine.TileEntityCrucible.wasteZCapacity / 2));
            }
        }
        GunFeel.LOGGER.info("Scenario look: {} at {} {} {}: {}", id, x, y, z, w.getTileEntity(x, y, z));
    }

    // the integrated server: NTM's sliding blast door placed at (x, y, z), facing the player's way (south); its core
    private static int[] door(int x, int y, int z) {
        EntityPlayerMP player = (EntityPlayerMP) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
        World w = player.worldObj;
        net.minecraft.block.Block b = (net.minecraft.block.Block) net.minecraft.block.Block.blockRegistry.getObject("hbm:tile.sliding_blast_door");
        if (b == null) {
            GunFeel.LOGGER.warn("Scenario look: no hbm:tile.sliding_blast_door");
            return null;
        }
        float yaw = player.rotationYaw;
        player.rotationYaw = 0F;
        try {
            w.setBlock(x, y, z, b, 0, 3);
            b.onBlockPlacedBy(w, x, y, z, player, new net.minecraft.item.ItemStack(b));
        } finally {
            player.rotationYaw = yaw;
        }
        for (int dx = -8; dx <= 8; dx++) for (int dy = -8; dy <= 8; dy++) for (int dz = -8; dz <= 8; dz++)
            if (w.getTileEntity(x + dx, y + dy, z + dz) instanceof com.hbm.tileentity.TileEntityDoorGeneric) return new int[] {x + dx, y + dy, z + dz};
        GunFeel.LOGGER.warn("Scenario look: the door was not set up");
        return null;
    }

    // the integrated server: NTM's press with its core at (x, y, z), turned to the player (BlockDummyable)
    private static void press(int x, int y, int z) {
        EntityPlayerMP player = (EntityPlayerMP) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
        World w = player.worldObj;
        net.minecraft.block.Block b = (net.minecraft.block.Block) net.minecraft.block.Block.blockRegistry.getObject("hbm:tile.machine_press");
        if (b == null) {
            GunFeel.LOGGER.warn("Scenario look: no hbm:tile.machine_press");
            return;
        }
        w.setBlock(x, y, z, b, 0, 3);
        b.onBlockPlacedBy(w, x, y, z, player, new net.minecraft.item.ItemStack(b));
        GunFeel.LOGGER.info("Scenario look: press at {} {} {}: {}", x, y, z, w.getTileEntity(x, y, z));
    }

    // the integrated server: noon, the wall 10 ahead (stone, its middle column glass), the zombie and its roof
    private static void build(int px, int py, int pz) {
        EntityPlayerMP player = (EntityPlayerMP) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
        World w = player.worldObj;
        w.setWorldTime(6000);
        for (int dx = -3; dx <= 3; dx++)
            for (int dy = 0; dy < 4; dy++) {
                // the column right of the glass: iron bars before stone, holes go through the gaps (ModelCells)
                w.setBlock(px + dx, py + dy, pz + 10, dx == 0 ? Blocks.glass : dx == -1 ? Blocks.iron_bars : Blocks.stone, 0, 3);
                if (dx == -1) w.setBlock(px + dx, py + dy, pz + 11, Blocks.stone, 0, 3);
            }
        w.setBlock(px + ZX, py + 3, pz + ZZ, Blocks.stone, 0, 3);
        EntityZombie z = new EntityZombie(w);
        z.setLocationAndAngles(px + ZX + .5, py, pz + ZZ + .5, 180F, 0F);
        z.rotationYawHead = z.renderYawOffset = 180F;
        z.getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(0);
        z.func_110163_bv(); // never despawns
        w.spawnEntityInWorld(z);
        // a sheep and a small slime beside it: the render passes the hitboxes take (the wool, the slime's outer body)
        net.minecraft.entity.passive.EntitySheep sheep = new net.minecraft.entity.passive.EntitySheep(w);
        sheep.setLocationAndAngles(px + ZX + 2.5, py, pz + ZZ + .5, 180F, 0F);
        sheep.getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(0);
        w.spawnEntityInWorld(sheep);
        net.minecraft.entity.monster.EntitySlime slime = new net.minecraft.entity.monster.EntitySlime(w);
        net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound();
        slime.writeEntityToNBT(tag);
        tag.setInteger("Size", 0); // size 1: does not hurt the player
        slime.readEntityFromNBT(tag);
        slime.setLocationAndAngles(px + ZX - 1.5, py, pz + ZZ + .5, 180F, 0F);
        w.spawnEntityInWorld(slime);
        // NTM's mobs drawn by OBJ models: a glyphid (NTM's loader) and a tesla crab (Forge's), still, without their AI and
        // away from the rest (the crab zaps what is within 3 blocks)
        net.minecraft.entity.EntityLiving glyphid = new com.hbm.entity.mob.glyphid.EntityGlyphid(w);
        net.minecraft.entity.EntityLiving crab = new com.hbm.entity.mob.EntityTeslaCrab(w);
        double[][] at = {{ZX + 7.5, ZZ + 2.5}, {ZX - 7.5, ZZ + 3.5}};
        int i = 0;
        for (net.minecraft.entity.EntityLiving mob : new net.minecraft.entity.EntityLiving[] {glyphid, crab}) {
            mob.setLocationAndAngles(px + at[i][0], py, pz + at[i][1], 180F, 0F);
            mob.getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(0);
            mob.func_110163_bv();
            mob.tasks.taskEntries.clear();
            mob.targetTasks.taskEntries.clear();
            w.spawnEntityInWorld(mob);
            i++;
        }
        GunFeel.LOGGER.info("Scenario look: wall at z {}, zombie at {} {} {}", pz + 10, px + ZX, py, pz + ZZ);
    }

    // a stack of each ammo the held gun's (slot 0) first magazine takes, from `slot` on: the ammo menu's choices
    private static void giveAmmo(int slot) {
        EntityPlayerMP player = (EntityPlayerMP) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
        for (com.hbm.items.weapon.sedna.BulletConfig c : AmmoLock.accepted(AmmoLock.primary(player.inventory.getStackInSlot(0)))) {
            net.minecraft.item.ItemStack ammo = AmmoLock.ammoStack(c);
            if (ammo == null || slot >= 9) continue;
            ammo.stackSize = 12;
            player.inventory.setInventorySlotContents(slot++, ammo);
        }
        player.inventoryContainer.detectAndSendChanges();
    }

    private static void shot(Minecraft mc, String name) {
        ScreenShotHelper.saveScreenshot(mc.mcDataDir, "look-" + name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        GunFeel.LOGGER.info("Scenario look: screenshot look-{}.png", name);
    }
}
