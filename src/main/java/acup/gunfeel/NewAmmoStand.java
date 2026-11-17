package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.factory.Lego;
import com.hbm.items.weapon.sedna.factory.XFactory556mm;

import acup.gunfeel.ammo.Duplex;
import acup.gunfeel.ammo.DuplexRule;
import acup.gunfeel.ammo.NuclearBmg;
import acup.gunfeel.ammo.RubberRule;
import acup.gunfeel.ammo.RubberServer;
import acup.gunfeel.ammo.RubberSlug;
import acup.gunfeel.ammo.client.RubberClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// runCheckClient -PnewAmmoStand: the new rounds (owner 2026-11-17) in the game. A flat world, the player in survival at
// (EX, EZ) looking along +Z.
//  - magazines: the rubber slug where NTM's 12 gauge buckshot goes (not the Shredder), Duplex where the SP of its calibre
//    goes (the Minigun's belt too), the nuclear .50 in the three AMAT rifles only (not the M2);
//  - Duplex: the player's G3 fired by NTM's own standard fire makes two bullets a shot, the rear one about a quarter of a
//    block behind the front one and within DuplexRule.CUT of its heading; one bullet does about 0.7 of an SP's damage
//    on the same spot of a zombie;
//  - rubber: a hit on a zombie takes at most its 6 (a zombie's own armour), the zombie drops its target and runs from
//    the shooter, frightened no longer after FEAR_TICKS, and not again within its immunity; one at 6 HP is left at 4 and
//    one at 4 untouched (slowed, so the walk below is short); a cow is pushed 1.5 to 5 blocks back; the player hit by a mannequin's slug is slowed, its
//    client confused, and holding the forward key it goes backwards then, forwards again after;
//  - nuclear: three of the AMAT's shots at a stone wall 40 blocks off wear it out (wear = durability, not after two),
//    the wall stands whole, a zombie by it is hurt.
// Lines "[new-ammo-stand] ..." in the log, PASS/FAIL in the report.
final class NewAmmoStand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.newAmmoStand");
    private static final BlockPos O = new BlockPos(8, 100, 8);
    private static final double EX = 8.5, EZ = 8.5;
    /** the wall the nuclear rounds hit: z, half its width, its height */
    private static final int WALL_Z = 48, WALL_W = 6, WALL_H = 6;

    private static int step, until;
    private static Mob zombie, cow, guard;
    private static Mannequin shooter;
    private static float lossSp, lossDx;
    private static double zFrom;
    private static Vec3 cowAt;
    private static int wallBefore;
    private static double backZ, fwdZ;

    private NewAmmoStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            for (int x = -24; x <= 24; x++) for (int z = -30; z <= WALL_Z + 12; z++) {
                level.setBlock(O.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 0; y <= 10; y++) level.setBlock(O.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            ServerPlayer p = player(server);
            p.setGameMode(GameType.SURVIVAL);
            p.teleportTo(level, EX, O.getY(), EZ, Set.of(), 0F, 0F, false);
        }).join();
        step = 0;
        until = now() + 60;
    }

    static void frame() {
        try {
            if (now() < until) return;
            switch (step) {
                case 0 -> magazines();
                case 10 -> duplexPair();
                case 20 -> duplexDamage();
                case 30 -> rubberZombie();
                case 31 -> rubberFled();
                case 32 -> rubberFearOver();
                case 33 -> rubberFloor();
                case 34 -> rubberCow();
                case 35 -> rubberCowMoved();
                case 40 -> rubberPlayer();
                case 41 -> rubberPlayerHit();
                case 42 -> rubberWalkBack();
                case 43 -> rubberWalkStop();
                case 44 -> rubberWalkFwd();
                case 45 -> rubberWalkEnd();
                case 50 -> nuclearFire(0);
                case 51 -> nuclearFire(1);
                case 52 -> nuclearFire(2);
                case 53 -> nuclearAfter();
                case 60 -> {
                    onServer(p -> {
                        RubberServer.clear();
                        return null;
                    });
                    ClientSmoke.finish();
                    step = 999;
                }
                default -> {
                }
            }
        } catch (Exception e) {
            GunFeel.LOGGER.error("[new-ammo-stand]", e);
            fail("исключение: " + e);
        }
    }

    // ---- magazines

    private static void magazines() {
        check(RubberSlug.config() != null && Duplex.r556() != null && Duplex.r762() != null && NuclearBmg.config() != null, "конфиги патронов есть");
        takes("gun_maresleg", RubberSlug.config(), true);
        takes("gun_spas12", RubberSlug.config(), true);
        takes("gun_autoshotgun_shredder", RubberSlug.config(), false);
        takes("gun_g3", Duplex.r556(), true);
        takes("gun_carbine", Duplex.r762(), true);
        takes("gun_minigun", Duplex.r762(), true);
        takes("gun_g3", Duplex.r762(), false);
        takes("gun_amat", NuclearBmg.config(), true);
        takes("gun_amat_subtlety", NuclearBmg.config(), true);
        takes("gun_amat_penance", NuclearBmg.config(), true);
        takes("gun_m2", NuclearBmg.config(), false);
        wait(5, 10);
    }

    private static void takes(String gun, BulletConfig c, boolean want) {
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", gun));
        boolean has = false;
        if (item instanceof ItemGunBaseNT g) {
            ItemStack s = new ItemStack(g);
            for (int k = 0; k < g.getConfigCount(); k++)
                for (Receiver r : g.getConfig(s, k).getReceivers(s)) has |= AmmoLock.accepted(r.getMagazine(s)).contains(c);
        } else {
            check(false, gun + ": нет такой пушки");
            return;
        }
        check(has == want, gun + ": " + BuiltInRegistries.ITEM.getKey(c.ammoItem.get()).getPath() + (want ? " берёт" : " не берёт"));
    }

    // ---- Duplex

    // twenty shots of the G3 through NTM's standard fire: two bullets each, the rear one behind and off a little
    private static void duplexPair() {
        String out = onServer(p -> {
            ServerLevel level = (ServerLevel) p.level();
            ItemStack stack = gun(p, "gun_g3", Duplex.r556(), 30);
            int pairs = 0, bad = 0;
            double sum = 0, most = 0, gapMin = 9, gapMax = 0;
            for (int i = 0; i < 20; i++) {
                List<EntityBulletBaseMK4> before = bullets(level, p);
                fire(p, stack);
                List<EntityBulletBaseMK4> made = bullets(level, p);
                made.removeAll(before);
                if (made.size() != 2) {
                    bad++;
                } else {
                    EntityBulletBaseMK4 a = made.get(0), b = made.get(1);
                    double ang = DuplexRule.angle(v(a.getDeltaMovement()), v(b.getDeltaMovement()));
                    double gap = a.position().distanceTo(b.position());
                    sum += ang;
                    most = Math.max(most, ang);
                    gapMin = Math.min(gapMin, gap);
                    gapMax = Math.max(gapMax, gap);
                    pairs++;
                }
                for (EntityBulletBaseMK4 m : made) m.discard();
            }
            return pairs + " " + bad + " " + sum / Math.max(1, pairs) + " " + most + " " + gapMin + " " + gapMax;
        });
        String[] f = out.split(" ");
        log("Duplex: пар {}, не по две {}, средний угол {} рад, наибольший {}, зазор {}..{}", f[0], f[1], f[2], f[3], f[4], f[5]);
        check(f[0].equals("20") && f[1].equals("0"), "Duplex: каждый выстрел — две пули (" + f[0] + " из 20)");
        double mean = Double.parseDouble(f[2]), most = Double.parseDouble(f[3]);
        check(mean > 0.0015 && mean < 0.0055 && most <= DuplexRule.CUT + 1e-6, "Duplex: задняя отклонена на мрад, не больше отсечки (" + f(mean * 1000) + " мрад)");
        double gapMin = Double.parseDouble(f[4]), gapMax = Double.parseDouble(f[5]);
        check(gapMin > DuplexRule.BEHIND - 0.02 && gapMax < DuplexRule.BEHIND + 0.02, "Duplex: задняя на четверть блока позади (" + f(gapMin) + ".." + f(gapMax) + ")");
        wait(5, 20);
    }

    // one bullet of each on the same spot of a still zombie: the Duplex one about 0.7 of the SP
    private static void duplexDamage() throws InterruptedException {
        onServer(p -> {
            gun(p, "gun_g3", Duplex.r556(), 30);
            zombie = mob(p, EntityTypes.ZOMBIE, EX, EZ + 6, true, 500);
            return null;
        });
        lossSp = shootAt(XFactory556mm.r556_sp, zombie);
        lossDx = shootAt(Duplex.r556(), zombie);
        log("Duplex: урон SP {}, пули Duplex {}, доля {}", f(lossSp), f(lossDx), f(lossDx / lossSp));
        check(lossSp > 0 && Math.abs(lossDx / lossSp - DuplexRule.SHARE) < 0.03, "Duplex: пуля даёт 0,7 урона SP (" + f(lossDx / lossSp) + ")");
        onServer(p -> {
            zombie.discard();
            return null;
        });
        wait(5, 30);
    }

    // a bullet of `c` from the player's eye level at the mob's chest, flown; the health it took
    private static float shootAt(BulletConfig c, LivingEntity target) throws InterruptedException {
        float before = onServer(p -> {
            target.setHealth(target.getMaxHealth());
            target.invulnerableTime = 0;
            ServerLevel level = (ServerLevel) p.level();
            double y = O.getY() + 1.62, ty = target.getY() + target.getBbHeight() * 0.6;
            EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, p, c, 10F * c.damageMult, -c.spread, EX, y, EZ, target.getX() - EX, ty - y, target.getZ() - EZ);
            level.addFreshEntity(b);
            return target.getHealth();
        });
        waitTicks(10);
        return before - onServer(p -> target.getHealth());
    }

    // ---- rubber

    private static void rubberZombie() throws InterruptedException {
        onServer(p -> {
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", "gun_maresleg"))));
            zombie = mob(p, EntityTypes.ZOMBIE, EX, EZ + 8, false, 0);
            zombie.setTarget(p);
            return null;
        });
        float loss = shootAt(RubberSlug.config(), zombie);
        boolean afraid = onServer(p -> RubberServer.afraid(zombie));
        log("резина: зомби потерял {}, напуган {}", f(loss), afraid);
        check(loss > 0F && loss <= RubberRule.DAMAGE + 0.01F, "резина: урон зомби не больше 6 (" + f(loss) + ")");
        check(afraid, "резина: зомби напуган");
        zFrom = onServer(p -> zombie.position().distanceTo(p.position()));
        wait(40, 31);
    }

    private static void rubberFled() {
        double d = onServer(p -> zombie.position().distanceTo(p.position()));
        boolean noTarget = onServer(p -> zombie.getTarget() == null);
        log("резина: зомби был в {}, стал в {}, без цели {}", f(zFrom), f(d), noTarget);
        check(d > zFrom + 2, "резина: зомби убегает от стрелка (" + f(zFrom) + " -> " + f(d) + ")");
        check(noTarget, "резина: у напуганного зомби нет цели");
        wait(RubberRule.FEAR_TICKS - 40 + 2, 32);
    }

    private static void rubberFearOver() throws InterruptedException {
        check(!onServer(p -> RubberServer.afraid(zombie)), "резина: страх прошёл через 4 с");
        // within the immunity: hit again, not frightened
        onServer(p -> {
            zombie.teleportTo(EX, O.getY(), EZ + 8);
            zombie.setDeltaMovement(Vec3.ZERO);
            return null;
        });
        waitTicks(2);
        shootAt(RubberSlug.config(), zombie);
        check(!onServer(p -> RubberServer.afraid(zombie)), "резина: в иммунитете не пугается");
        wait(2, 33);
    }

    private static void rubberFloor() throws InterruptedException {
        float[] hp = new float[2];
        onServer(p -> {
            zombie.discard();
            guard = mob(p, EntityTypes.ZOMBIE, EX + 2, EZ + 6, true, 0);
            return null;
        });
        onServer(p -> {
            guard.setHealth(6F);
            return null;
        });
        hp[0] = after(RubberSlug.config(), guard);
        hp[1] = after(RubberSlug.config(), guard);
        log("резина: с 6 HP осталось {}, потом {}", f(hp[0]), f(hp[1]));
        check(Math.abs(hp[0] - RubberRule.FLOOR) < 0.01F && Math.abs(hp[1] - RubberRule.FLOOR) < 0.01F, "резина: ниже 4 HP не опускает");
        onServer(p -> {
            guard.discard();
            return null;
        });
        wait(2, 34);
    }

    // a bullet at the mob's chest without filling its health first; its health after
    private static float after(BulletConfig c, LivingEntity target) throws InterruptedException {
        onServer(p -> {
            target.invulnerableTime = 0;
            ServerLevel level = (ServerLevel) p.level();
            double y = O.getY() + 1.62, ty = target.getY() + target.getBbHeight() * 0.6;
            level.addFreshEntity(new EntityBulletBaseMK4(level, p, c, 10F * c.damageMult, -c.spread, EX, y, EZ, target.getX() - EX, ty - y, target.getZ() - EZ));
            return null;
        });
        waitTicks(10);
        return onServer(p -> target.getHealth());
    }

    private static void rubberCow() throws InterruptedException {
        onServer(p -> {
            cow = mob(p, EntityTypes.COW, EX, EZ + 6, false, 0);
            return null;
        });
        waitTicks(10);
        cowAt = onServer(p -> cow.position());
        onServer(p -> {
            ServerLevel level = (ServerLevel) p.level();
            double y = O.getY() + 1.62, ty = cow.getY() + cow.getBbHeight() * 0.6;
            level.addFreshEntity(new EntityBulletBaseMK4(level, p, RubberSlug.config(), 2F, 0F, EX, y, EZ, cow.getX() - EX, ty - y, cow.getZ() - EZ));
            return null;
        });
        wait(30, 35);
    }

    private static void rubberCowMoved() {
        double dz = onServer(p -> cow.getZ()) - cowAt.z;
        log("резина: корову отбросило на {} бл", f(dz));
        check(dz > 1.5 && dz < 5, "резина: отброс 1,5–5 блоков вдоль выстрела (" + f(dz) + ")");
        onServer(p -> {
            cow.discard();
            return null;
        });
        wait(2, 40);
    }

    // a mannequin 10 blocks behind the player fires a slug into its back
    private static void rubberPlayer() {
        onServer(p -> {
            ServerLevel level = (ServerLevel) p.level();
            p.setHealth(p.getMaxHealth());
            shooter = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
            shooter.snapTo(EX, O.getY(), EZ - 10, 0F, 0F);
            shooter.setNoGravity(true);
            level.addFreshEntity(shooter);
            level.addFreshEntity(new EntityBulletBaseMK4(level, shooter, RubberSlug.config(), 2F, 0F, EX, O.getY() + 1.0, EZ - 9, 0, 0, 1));
            return null;
        });
        wait(6, 41);
    }

    private static void rubberPlayerHit() {
        boolean slowed = onServer(p -> p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED).getModifier(RubberServer.SLOW_ID) != null);
        check(slowed, "резина: игрок замедлен");
        check(RubberClient.confused(), "резина: клиент игрока запутан");
        // back where it stood, looking along +Z, the forward key held
        onServer(p -> {
            p.teleportTo((ServerLevel) p.level(), EX, O.getY(), EZ, Set.of(), 0F, 0F, false);
            p.setDeltaMovement(Vec3.ZERO);
            return null;
        });
        Minecraft.getInstance().player.setYRot(0F);
        zFrom = Minecraft.getInstance().player.getZ();
        Minecraft.getInstance().options.keyUp.setDown(true);
        wait(15, 42);
    }

    private static void rubberWalkBack() {
        backZ = Minecraft.getInstance().player.getZ() - zFrom;
        Minecraft.getInstance().options.keyUp.setDown(false);
        log("резина: «вперёд» в запутанности сдвинул на {} по Z", f(backZ));
        check(backZ < -0.2, "резина: «вперёд» ведёт назад (" + f(backZ) + ")");
        wait(RubberRule.CONFUSE_TICKS, 43);
    }

    private static void rubberWalkStop() {
        check(!RubberClient.confused(), "резина: запутанность прошла");
        zFrom = Minecraft.getInstance().player.getZ();
        Minecraft.getInstance().options.keyUp.setDown(true);
        wait(15, 44);
    }

    private static void rubberWalkFwd() {
        fwdZ = Minecraft.getInstance().player.getZ() - zFrom;
        Minecraft.getInstance().options.keyUp.setDown(false);
        log("резина: «вперёд» после сдвинул на {} по Z", f(fwdZ));
        check(fwdZ > 0.2, "резина: «вперёд» снова вперёд (" + f(fwdZ) + ")");
        wait(5, 45);
    }

    private static void rubberWalkEnd() {
        onServer(p -> {
            shooter.discard();
            RubberServer.clear();
            return null;
        });
        wait(5, 50);
    }

    // ---- nuclear

    private static void nuclearFire(int n) throws InterruptedException {
        if (n == 0) {
            onServer(p -> {
                ServerLevel level = (ServerLevel) p.level();
                for (int x = -WALL_W; x <= WALL_W; x++) for (int y = 0; y < WALL_H; y++)
                    level.setBlock(O.offset(x, y, WALL_Z - 8), Blocks.STONE.defaultBlockState(), 3);
                p.teleportTo(level, EX, O.getY(), EZ, Set.of(), 0F, -2F, false);
                gun(p, "gun_amat", NuclearBmg.config(), 7);
                guard = mob(p, EntityTypes.ZOMBIE, EX + 3, EZ + WALL_Z - 10, true, 0);
                return null;
            });
            wallBefore = wall();
        }
        // the spread of this shot (the wear before it), aimed and from the hip: the nuclear round's, an SP's at the
        // same wear (the full wear spread), and NTM's wear part alone
        String sp = onServer(p -> {
            ItemStack stack = p.getMainHandItem();
            ItemGunBaseNT g = (ItemGunBaseNT) stack.getItem();
            var ctx = new ItemGunBaseNT.LambdaContext(g.getConfig(stack, 0), p, p.getInventory(), 0);
            Receiver r = ctx.config().getReceivers(stack)[0];
            float part = Lego.getStandardWearSpread(stack, ctx.config(), 0) * r.getDurabilitySpread(stack);
            StringBuilder out = new StringBuilder().append(part);
            for (boolean aim : new boolean[] {true, false})
                out.append(' ').append(Lego.calcSpread(ctx, stack, r, NuclearBmg.config(), true, 0, aim))
                        .append(' ').append(Lego.calcSpread(ctx, stack, r, com.hbm.items.weapon.sedna.factory.XFactory50.bmg50_sp, true, 0, aim));
            out.append(' ').append(r.getCanFire(stack).apply(stack, ctx));
            return out.toString();
        });
        String[] q = sp.split(" ");
        float part = Float.parseFloat(q[0]), nAim = Float.parseFloat(q[1]), sAim = Float.parseFloat(q[2]), nHip = Float.parseFloat(q[3]),
                sHip = Float.parseFloat(q[4]);
        // the mean of a bullet's miss: NTM's gaussian on each axis of the unit heading, sigma = the spread; 1.2533 sigma
        log("ядерный: выстрел {} AMAT, разброс износа NTM {}; в прицел ядерный {} (SP {}), на 100 м в среднем {} м (было бы {} м); от бедра {} (SP {}), {} м (было бы {} м)",
                n + 1, f(part), f(nAim), f(sAim), f(miss(nAim)), f(miss(sAim)), f(nHip), f(sHip), f(miss(nHip)), f(miss(sHip)));
        check(q[5].equals("true"), "ядерный: перед " + (n + 1) + "-м выстрелом AMAT стреляет");
        if (part > 0F) check(nAim < sAim && nHip < sHip, "ядерный: " + (n + 1) + "-й выстрел кучнее SP на том же износе");
        else check(Math.abs(nAim - sAim) < 1e-5F && Math.abs(nHip - sHip) < 1e-5F, "ядерный: " + (n + 1) + "-й выстрел без износа, как SP");
        float wear = onServer(p -> {
            ItemStack stack = p.getMainHandItem();
            fire(p, stack);
            return ItemGunBaseNT.getWear(stack, 0);
        });
        float dura = onServer(p -> ((ItemGunBaseNT) p.getMainHandItem().getItem()).getConfig(p.getMainHandItem(), 0).getDurability(p.getMainHandItem()));
        log("ядерный: выстрел {}, износ {} из {}", n + 1, f(wear), f(dura));
        if (n < 2) check(wear < dura, "ядерный: после " + (n + 1) + "-го выстрела AMAT ещё не изношена");
        else check(wear == dura, "ядерный: после 3-го выстрела AMAT изношена полностью");
        if (n == 2) {
            // worn out: no nuclear round fires, an SP does
            boolean[] can = onServer(p -> {
                ItemStack stack = p.getMainHandItem();
                ItemGunBaseNT g = (ItemGunBaseNT) stack.getItem();
                var ctx = new ItemGunBaseNT.LambdaContext(g.getConfig(stack, 0), p, p.getInventory(), 0);
                Receiver r = ctx.config().getReceivers(stack)[0];
                @SuppressWarnings({"unchecked", "rawtypes"})
                com.hbm.items.weapon.sedna.mags.IMagazine<BulletConfig> m = (com.hbm.items.weapon.sedna.mags.IMagazine) r.getMagazine(stack);
                boolean nuke = r.getCanFire(stack).apply(stack, ctx);
                m.setType(stack, com.hbm.items.weapon.sedna.factory.XFactory50.bmg50_sp);
                boolean plain = r.getCanFire(stack).apply(stack, ctx);
                m.setType(stack, NuclearBmg.config());
                return new boolean[] {nuke, plain};
            });
            check(!can[0], "ядерный: изношенная AMAT ядерным не стреляет");
            check(can[1], "ядерный: изношенная AMAT обычным SP стреляет");
        }
        wait(40, 51 + n);
    }

    private static void nuclearAfter() {
        int now = wall();
        boolean hurt = onServer(p -> !guard.isAlive() || guard.getHealth() < guard.getMaxHealth());
        float hp = onServer(p -> p.getHealth());
        log("ядерный: блоков стены {} -> {}, зомби у стены задет {}, игрок {}", wallBefore, now, hurt, f(hp));
        check(now == wallBefore, "ядерный: стена цела (" + wallBefore + " -> " + now + ")");
        check(hurt, "ядерный: зомби у стены задет взрывом");
        onServer(p -> {
            if (guard.isAlive()) guard.discard();
            return null;
        });
        wait(5, 60);
    }

    private static int wall() {
        return onServer(p -> {
            int n = 0;
            for (int x = -WALL_W; x <= WALL_W; x++) for (int y = 0; y < WALL_H; y++)
                if (p.level().getBlockState(O.offset(x, y, WALL_Z - 8)).is(Blocks.STONE)) n++;
            return n;
        });
    }

    // ---- helpers

    private static ItemStack gun(ServerPlayer p, String id, BulletConfig c, int n) {
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", id)));
        ItemGunBaseNT g = (ItemGunBaseNT) stack.getItem();
        var mag = g.getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack);
        @SuppressWarnings({"unchecked", "rawtypes"})
        com.hbm.items.weapon.sedna.mags.IMagazine<BulletConfig> m = (com.hbm.items.weapon.sedna.mags.IMagazine) mag;
        m.setType(stack, c);
        m.setAmount(stack, n);
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        return stack;
    }

    // NTM's standard fire of the gun in the player's hand, its first receiver
    private static void fire(ServerPlayer p, ItemStack stack) {
        ItemGunBaseNT g = (ItemGunBaseNT) stack.getItem();
        var ctx = new ItemGunBaseNT.LambdaContext(g.getConfig(stack, 0), p, p.getInventory(), 0);
        Lego.doStandardFire(stack, ctx, null, 0, true);
    }

    private static List<EntityBulletBaseMK4> bullets(ServerLevel level, ServerPlayer p) {
        return new ArrayList<>(level.getEntitiesOfClass(EntityBulletBaseMK4.class, new AABB(p.blockPosition()).inflate(6)));
    }

    private static <T extends Mob> T mob(ServerPlayer p, EntityType<T> type, double x, double z, boolean still, double health) {
        ServerLevel level = (ServerLevel) p.level();
        T m = type.create(level, EntitySpawnReason.COMMAND);
        m.snapTo(x, O.getY(), z, 180F, 0F);
        m.setPersistenceRequired();
        if (still) m.setNoAi(true);
        // the sun does not burn the stand's zombies (their losses are the bullets')
        m.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, 20 * 600, 0, false, false));
        if (health > 0) {
            var hp = m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
            if (hp != null) hp.setBaseValue(health);
            m.setHealth(m.getMaxHealth());
        }
        level.addFreshEntity(m);
        return m;
    }

    /** the mean miss at 100 blocks of a bullet with NTM's spread `s` */
    private static double miss(float s) {
        return Math.sqrt(Math.PI / 2) * s * 100;
    }

    private static double[] v(Vec3 v) {
        return new double[] {v.x, v.y, v.z};
    }

    private static void waitTicks(int n) throws InterruptedException {
        int end = now() + n;
        while (now() < end) Thread.sleep(5);
    }

    private static void wait(int ticks, int next) {
        until = now() + ticks;
        step = next;
    }

    private static int now() {
        return Minecraft.getInstance().getSingleplayerServer().getTickCount();
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static <T> T onServer(java.util.function.Function<ServerPlayer, T> f) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        Object[] out = new Object[1];
        server.submit(() -> out[0] = f.apply(player(server))).join();
        @SuppressWarnings("unchecked")
        T t = (T) out[0];
        return t;
    }

    private static void fail(String text) {
        ClientSmoke.check(false, "новые патроны: " + text);
        ClientSmoke.finish();
        step = 999;
    }

    private static void check(boolean ok, String text) {
        GunFeel.LOGGER.info("[new-ammo-stand] {} {}", ok ? "PASS" : "FAIL", text);
        ClientSmoke.check(ok, "новые патроны: " + text);
    }

    private static void log(String fmt, Object... args) {
        GunFeel.LOGGER.info("[new-ammo-stand] " + fmt, args);
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
