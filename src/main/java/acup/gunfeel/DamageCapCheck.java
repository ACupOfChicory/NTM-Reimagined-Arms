package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.extprop.HbmPlayerProps;
import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.ModItems;
import com.hbm.items.armor.ModArmorItem;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.XFactory10ga;
import com.hbm.items.weapon.sedna.factory.XFactory12ga;
import com.hbm.items.weapon.sedna.factory.XFactory50;
import com.hbm.items.weapon.sedna.factory.XFactory762mm;
import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// The damage cap on a dedicated server (./gradlew runCheckCap -PnoAssets): a mannequin fires NTM's real guns (NTM's
// handleKeybind, as an AI bot does) at a player 4 blocks off, in HEV or bare, with the cap on and off:
//  - SPAS-12 slugs, the secondary's double shot, at HEV: one click does not kill; NTM's shield counts with health;
//  - the double barrel's DU, both barrels, at HEV: one click takes no more than 60 %;
//  - SPAS-12 explosive shells (the blast's path) at HEV;
//  - a minigun's DU every tick at a bare player: no death sooner than 14 ticks (0.7 s) after the first hit;
//  - AMAT .50 SM at a bare player: the head kills with one click, the body takes two;
//  - a player's bullet whose hit its client decided (ClientHits, the head) on a husk with damageCapMobs on: the
//    head's 80 %; with it off the husk is NTM's.
// A tsv of the cases and OK/FAIL lines go to the file; the server stops (exit 1 if a check failed).
public final class DamageCapCheck {
    public static final String PROPERTY = "ntmgunfeel.checkCap";
    private static final BlockPos O = new BlockPos(0, 120, 0);
    private static final double DIST = 4;

    private DamageCapCheck() {
    }

    private static final List<Runnable> STEPS = new ArrayList<>();
    private static final List<String> LINES = new ArrayList<>();
    private static boolean ok = true;
    private static ServerLevel level;
    private static Mannequin shooter;
    private static FakePlayer target, player, gunner;
    private static Mob husk;

    // the case under way
    private static String name = "";
    private static LivingEntity victim;
    private static int bullets, hits, heads, explosive, firstHit = -1, deathAt = -1, tick;
    private static float lost, shieldStart;
    private static long cutAt;
    private static String last = "";

    public static void registerIfAsked() {
        String file = System.getProperty(PROPERTY);
        if (file == null || file.isBlank()) return;
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, started -> {
            level = started.getServer().overworld();
            try {
                setup();
                plan();
            } catch (Throwable t) {
                LINES.add("FAIL\tsetup crashed: " + t);
                for (StackTraceElement s : t.getStackTrace()) LINES.add("    at " + s);
                BulletCheck.finish(file, LINES, 1);
            }
            int[] at = {0};
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> {
                if (at[0] < 0) return;
                tick++;
                try {
                    if (target != null) target.invulnerableTime = 0;
                    if (husk != null) husk.invulnerableTime = 0;
                    if (at[0] < STEPS.size()) {
                        STEPS.get(at[0]++).run();
                        return;
                    }
                    at[0] = -1;
                    keys();
                    LINES.add(ok ? "ALL OK" : "SOME FAILED");
                    BulletCheck.finish(file, LINES, ok ? 0 : 1);
                } catch (Throwable t) {
                    at[0] = -1;
                    LINES.add("FAIL\tcrashed: " + t);
                    for (StackTraceElement s : t.getStackTrace()) LINES.add("    at " + s);
                    keys();
                    BulletCheck.finish(file, LINES, 1);
                }
            });
        });
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, e -> {
            if (!(e.getEntity() instanceof EntityBulletBaseMK4 b) || e.getLevel().isClientSide()) return;
            if (b.getThrower() == shooter || b.getThrower() == gunner || (player != null && b.getThrower() == player)) bullets++;
            if (player != null && b.getThrower() == player && b instanceof ClientHits.Holder && husk != null) {
                // what a client's report would give: the hit on the husk's head, the path tested far ahead
                var s = ClientHits.state(b);
                s.client = true;
                s.shooter = player;
                s.start = b.position();
                s.reach = 64;
                s.tested = 1000;
                s.claims.add(new HitReportPayload.Claim(husk.getId(), 0F, new Vec3(husk.getX(), husk.getEyeY(), husk.getZ() - 0.2), HitReportPayload.HEAD));
            }
        });
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, false, LivingDamageEvent.Post.class, e -> {
            if (e.getEntity() != victim) return;
            hits++;
            if (e.getSource().is(DamageTypeTags.IS_EXPLOSION)) explosive++;
            lost += e.getInflictedDamage();
            if (firstHit < 0) firstHit = tick;
            if (deathAt < 0 && victim.getHealth() <= 0F) deathAt = tick;
            // the cap's note on this hit; none with the cap off
            last = DamageCap.last;
            DamageCap.last = "";
            if (last.contains("head=true")) heads++;
        });
    }

    // ---------------------------------------------------------------- the world

    private static void setup() {
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 10; z++) level.setBlockAndUpdate(O.offset(x, -1, z), Blocks.STONE.defaultBlockState());
        shooter = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
        shooter.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        shooter.setHealth(1000);
        shooter.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5, 0F, 0F);
        level.addFreshEntity(shooter);
        // a player another player may hurt (FakePlayer's own says no)
        target = new FakePlayer(level, new GameProfile(UUID.nameUUIDFromBytes("capTarget".getBytes()), "capTarget")) {
            @Override
            public boolean canHarmPlayer(net.minecraft.world.entity.player.Player other) {
                return true;
            }
        };
        target.setGameMode(GameType.SURVIVAL);
        target.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5 + DIST, 180F, 0F);
        level.addNewPlayer(target);
        // a player whose client has not loaded takes no damage (ServerPlayer.isInvulnerableTo)
        target.connection.markClientLoaded();
        target.setInvulnerable(false);
        gunner = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("capGunner".getBytes()), "capGunner"));
        gunner.setGameMode(GameType.SURVIVAL);
        gunner.snapTo(O.getX() + 0.5, O.getY(), O.getZ() - 1.5, 0F, 0F);
        level.addNewPlayer(gunner);
    }

    private static void wait(int ticks) {
        for (int i = 0; i < ticks; i++) STEPS.add(() -> {
        });
    }

    private static void plan() {
        // the server's file is read once a second
        STEPS.add(() -> keys("damageCap", "true"));
        wait(30);
        spas("SPAS-12 slug ПКМ, HEV", true, XFactory12ga.g12_slug, 0F);
        spas("SPAS-12 slug ПКМ, HEV, щит 10", true, XFactory12ga.g12_slug, 10F);
        spas("SPAS-12 explosive ПКМ, HEV", true, XFactory12ga.g12_explosive, 0F);
        oneClick("двустволка DU, 2 ствола, HEV", true, ModItems.GUN_DOUBLE_BARREL::get, XFactory10ga.g10_du, EnumKeybind.GUN_PRIMARY, 0F);
        minigun("миниган DU, без брони", false);
        minigun("миниган DU, HEV", true);
        amat("AMAT SM в голову, без брони", false, true);
        amat("AMAT SM в тело, без брони", false, false);
        amat("AMAT SM в голову, HEV", true, true);
        amat("AMAT SM в тело, HEV", true, false);
        clientHit("клиентское попадание в голову, хаск, damageCapMobs", true);
        // and without the cap, for the numbers before it
        STEPS.add(() -> keys("damageCap", "false"));
        wait(30);
        spas("без потолка: SPAS-12 slug ПКМ, HEV", true, XFactory12ga.g12_slug, 0F);
        spas("без потолка: SPAS-12 explosive ПКМ, HEV", true, XFactory12ga.g12_explosive, 0F);
        oneClick("без потолка: двустволка DU, 2 ствола, HEV", true, ModItems.GUN_DOUBLE_BARREL::get, XFactory10ga.g10_du, EnumKeybind.GUN_PRIMARY, 0F);
        minigun("без потолка: миниган DU, без брони", false);
        amat("без потолка: AMAT SM в тело, без брони", false, false);
        STEPS.add(() -> keys("damageCap", "true"));
        wait(30);
        clientHit("клиентское попадание в голову, хаск, мобы без потолка", false);
    }

    // ---------------------------------------------------------------- the cases

    private static void spas(String n, boolean hev, BulletConfig ammo, float shield) {
        oneClick(n, hev, ModItems.GUN_SPAS12::get, ammo, EnumKeybind.GUN_SECONDARY, shield);
    }

    private static void oneClick(String n, boolean hev, Supplier<Item> gun, BulletConfig ammo, EnumKeybind key, float shield) {
        STEPS.add(() -> begin(n, hev, shield));
        STEPS.add(() -> fire(gun.get(), ammo, key, false));
        wait(40);
        STEPS.add(() -> {
            float shieldLost = shieldStart - HbmPlayerProps.getData(target).shield;
            boolean capped = DamageCap.on();
            String why = row(shieldLost);
            if (capped) {
                check(bullets >= 1 && hits >= 1, n + ": попало (" + bullets + " пуль, " + hits + " ударов)");
                // a click with a pellet on the head has the head's cap
                float cap = (heads > 0 ? 0.8F : 0.6F) * 20F;
                check(lost + shieldLost <= cap + 0.01F && target.getHealth() > 0F, n + ": клик снял " + f(lost) + " HP + щит "
                        + f(shieldLost) + " ≤ " + f(cap) + " (в голову " + heads + "), жив" + why);
                if (ammo == XFactory12ga.g12_explosive) check(explosive >= 1, n + ": удар взрывом прошёл через потолок (" + explosive + ")");
            }
        });
    }

    private static void minigun(String n, boolean hev) {
        // held down: NTM's minigun spins up while its key is held and feeds its belt from the inventory, so a player
        // fires it (a fake one, its gun ticked here as a player's is)
        ItemStack[] held = new ItemStack[1];
        STEPS.add(() -> {
            begin(n, hev, 0F);
            for (int i = 1; i < 10; i++) {
                ItemStack ammo = XFactory762mm.r762_du.ammoStack().copy();
                ammo.setCount(ammo.getMaxStackSize());
                gunner.getInventory().setItem(i, ammo);
            }
            held[0] = loaded(ModItems.GUN_MINIGUN.get(), XFactory762mm.r762_du);
            gunner.getInventory().setSelectedSlot(0);
            gunner.setItemSlot(EquipmentSlot.MAINHAND, held[0]);
            aim(gunner, target, false);
            ((ItemGunBaseNT) held[0].getItem()).handleKeybind(gunner, held[0], EnumKeybind.GUN_PRIMARY, true);
        });
        for (int i = 0; i < 100; i++) STEPS.add(() -> {
            var gun = (ItemGunBaseNT) held[0].getItem();
            if (target.getHealth() <= 0F && ItemGunBaseNT.getPrimary(held[0], 0)) gun.handleKeybind(gunner, held[0], EnumKeybind.GUN_PRIMARY, false);
            aim(gunner, target, false);
            gun.inventoryTick(held[0], level, gunner, EquipmentSlot.MAINHAND);
        });
        STEPS.add(() -> ((ItemGunBaseNT) held[0].getItem()).handleKeybind(gunner, held[0], EnumKeybind.GUN_PRIMARY, false));
        wait(20);
        STEPS.add(() -> {
            String why = row(0F);
            check(hits >= 2 && (bullets >= 10 || deathAt >= 0), n + ": стреляет очередью (" + bullets + " пуль, " + hits + " ударов)");
            if (DamageCap.on()) check(deathAt >= 0 && deathAt - firstHit >= 14, n + ": смерть через " + (deathAt - firstHit)
                    + " тиков после первого попадания (≥ 14)" + why);
        });
    }

    private static void amat(String n, boolean hev, boolean head) {
        STEPS.add(() -> begin(n, hev, 0F));
        STEPS.add(() -> fire(ModItems.GUN_AMAT.get(), XFactory50.bmg50_sm, EnumKeybind.GUN_PRIMARY, head));
        wait(30);
        float[] afterOne = new float[1];
        STEPS.add(() -> afterOne[0] = target.getHealth());
        STEPS.add(() -> {
            if (target.getHealth() > 0F) fire(ModItems.GUN_AMAT.get(), XFactory50.bmg50_sm, EnumKeybind.GUN_PRIMARY, head);
        });
        wait(30);
        STEPS.add(() -> {
            String why = row(0F) + ", после 1-го клика HP " + f(afterOne[0]) + ", " + DamageCap.last;
            if (!DamageCap.on() || hev) {
                LINES.add("#\t" + n + why);
                return;
            }
            if (head) check(afterOne[0] <= 0F, n + ": один клик убивает" + why);
            else check(afterOne[0] > 0F && afterOne[0] >= 20F - 12F - 0.01F && target.getHealth() <= 0F, n + ": первый клик ≤ 60 %, второй убивает" + why);
        });
    }

    private static void clientHit(String n, boolean mobs) {
        STEPS.add(() -> {
            keys("damageCapMobs", mobs ? "true" : "false");
            if (player == null) {
                player = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("capShooter".getBytes()), "capShooter"));
                player.setGameMode(GameType.SURVIVAL);
                player.snapTo(O.getX() + 2.5, O.getY(), O.getZ() + 0.5, 0F, 0F);
                level.addNewPlayer(player);
            }
            if (husk == null) {
                husk = EntityTypes.HUSK.create(level, EntitySpawnReason.COMMAND);
                husk.setNoAi(true);
                husk.snapTo(O.getX() + 2.5, O.getY(), O.getZ() + 0.5 + DIST, 180F, 0F);
                level.addFreshEntity(husk);
            }
        });
        wait(30);
        long[] taken = new long[1];
        STEPS.add(() -> {
            begin(n, false, 0F);
            victim = husk;
            husk.setHealth(husk.getMaxHealth());
            taken[0] = ClientHits.taken;
            ItemStack stack = loaded(ModItems.GUN_SPAS12.get(), XFactory12ga.g12_slug);
            player.setItemSlot(EquipmentSlot.MAINHAND, stack);
            aim(player, husk, true);
            ((ItemGunBaseNT) stack.getItem()).handleKeybind(player, new SimpleContainer(1), stack, EnumKeybind.GUN_SECONDARY, true);
            ((ItemGunBaseNT) stack.getItem()).handleKeybind(player, new SimpleContainer(1), stack, EnumKeybind.GUN_SECONDARY, false);
        });
        wait(30);
        STEPS.add(() -> {
            long t = ClientHits.taken - taken[0];
            String why = "\tпуль " + bullets + ", ударов " + hits + ", клиентских " + t + ", снято " + f(lost) + " из " + f(husk.getMaxHealth()) + ", " + last;
            LINES.add("#\t" + n + why);
            check(t >= 1, n + ": удар решил клиент (" + t + ")");
            if (mobs) check(Math.abs(lost - 0.8F * husk.getMaxHealth()) < 0.01F && last.contains("head=true"), n + ": снято ровно 80 % (голова)" + why);
            else check(lost > 0.8F * husk.getMaxHealth() + 0.01F, n + ": снято больше 80 %, потолка нет" + why);
            husk.discard();
            husk = null;
        });
    }

    // ---------------------------------------------------------------- the parts

    private static void begin(String n, boolean hev, float shield) {
        name = n;
        victim = target;
        bullets = hits = heads = explosive = 0;
        firstHit = deathAt = -1;
        lost = 0;
        last = DamageCap.last = "";
        cutAt = DamageCap.cut;
        DamageCap.forget();
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
            target.setItemSlot(slot, hev ? hev(slot) : ItemStack.EMPTY);
        target.setHealth(target.getMaxHealth());
        target.setAbsorptionAmount(0);
        target.snapTo(O.getX() + 0.5, O.getY(), O.getZ() + 0.5 + DIST, 180F, 0F);
        target.setDeltaMovement(Vec3.ZERO);
        var props = HbmPlayerProps.getData(target);
        props.maxShield = Math.max(props.maxShield, shield);
        props.shield = shield;
        shieldStart = shield;
    }

    private static ItemStack hev(EquipmentSlot slot) {
        Item item = switch (slot) {
            case HEAD -> ModItems.HEV_HELMET.get();
            case CHEST -> ModItems.HEV_PLATE.get();
            case LEGS -> ModItems.HEV_LEGS.get();
            default -> ModItems.HEV_BOOTS.get();
        };
        ItemStack stack = new ItemStack(item);
        if (item instanceof ModArmorItem armor && armor.isPowered()) armor.setCharge(stack, armor.maxPower(stack));
        return stack;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ItemStack loaded(Item item, BulletConfig ammo) {
        ItemStack stack = new ItemStack(item);
        ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
        for (int i = 0; i < gun.getConfigCount(); i++) {
            for (var r : gun.getConfig(stack, i).getReceivers(stack)) {
                com.hbm.items.weapon.sedna.mags.IMagazine magazine = r.getMagazine(stack);
                magazine.setType(stack, ammo);
                magazine.setAmount(stack, magazine instanceof com.hbm.items.weapon.sedna.mags.MagazineSingleTypeBase m ? m.capacity : 30);
            }
            ItemGunBaseNT.setState(stack, i, ItemGunBaseNT.GunState.IDLE);
            ItemGunBaseNT.setTimer(stack, i, 0);
        }
        ItemGunBaseNT.setIsDrawn(stack, true);
        ItemGunBaseNT.setIsEquipped(stack, true);
        return stack;
    }

    /** the mannequin: a fresh loaded gun, aimed at the target's head or its chest, one click */
    private static void fire(Item item, BulletConfig ammo, EnumKeybind key, boolean head) {
        ItemStack stack = loaded(item, ammo);
        shooter.setItemSlot(EquipmentSlot.MAINHAND, stack);
        aim(shooter, target, head);
        var gun = (ItemGunBaseNT) stack.getItem();
        gun.handleKeybind(shooter, new SimpleContainer(1), stack, key, true);
        gun.handleKeybind(shooter, new SimpleContainer(1), stack, key, false);
    }

    private static void aim(LivingEntity from, LivingEntity at, boolean head) {
        Vec3 eye = from.getEyePosition();
        Vec3 to = new Vec3(at.getX(), head ? at.getEyeY() : at.getY() + 0.9, at.getZ());
        Vec3 d = to.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        from.snapTo(from.getX(), from.getY(), from.getZ(), yaw, pitch);
        from.setYHeadRot(yaw);
        from.setYBodyRot(yaw);
    }

    private static String row(float shieldLost) {
        String r = "\tпуль " + bullets + ", ударов " + hits + " (в голову " + heads + ", взрыв " + explosive + "), снято " + f(lost) + " HP, щит " + f(shieldLost)
                + ", HP " + f(Math.max(0F, target.getHealth())) + (deathAt >= 0 ? ", смерть через " + (deathAt - firstHit) + " тиков ("
                + f((deathAt - firstHit) / 20F) + " с)" : ", жив") + ", урезано " + (DamageCap.cut - cutAt) + ", " + last;
        LINES.add("#\t" + name + r);
        return r;
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(ReaimTimes.FILE);
    }

    private static final java.util.Map<String, String> KEYS = new java.util.TreeMap<>();

    /** the server's file with these keys; none: the file gone */
    private static void keys(String... kv) {
        for (int i = 0; i < kv.length; i += 2) KEYS.put(kv[i], kv[i + 1]);
        try {
            if (kv.length == 0) {
                Files.deleteIfExists(file());
                return;
            }
            StringBuilder s = new StringBuilder();
            KEYS.forEach((k, v) -> s.append(k).append(" = ").append(v).append('\n'));
            Files.writeString(file(), s);
            Files.setLastModifiedTime(file(), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String f(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static void check(boolean pass, String what) {
        if (!pass) ok = false;
        LINES.add((pass ? "OK" : "FAIL") + "\t" + what);
    }
}
