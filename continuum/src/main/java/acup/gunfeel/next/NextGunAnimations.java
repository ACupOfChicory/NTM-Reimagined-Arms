// port/continuum: the gun animations of NTM NEXT (HBM's Nuclear Tech Mod, 26.2), each NEXT's LAMBDA_*_ANIMS of its
// XFactory class as NEXT has it, on NTM Continuum's GunAnimation and gun API. ContinuumAnimations puts them in place of
// Continuum's own for the guns both have. Taken from NTM NEXT 1.0.3, LGPL-3.0-only: see continuum/third-party/ntm-next.txt.
package acup.gunfeel.next;

import java.util.HashMap;
import java.util.function.BiFunction;

import com.hbm.client.anim.HbmAnimations;
import com.hbm.items.weapon.sedna.GunAnimation;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.mods.XWeaponModManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

@SuppressWarnings("unused")
public final class NextGunAnimations {
    private NextGunAnimations() {
    }

    // NEXT's ResourceManager: the animations it loads from JSON (Continuum carries the same files), loaded on first use
    static final class ResourceManager {
        static final HashMap<String, BusAnimation> am180_anim = AnimationLoader.load(Identifier.parse("hbm:models/weapons/animations/am180.json"));
        static final HashMap<String, BusAnimation> congolake_anim = AnimationLoader.load(Identifier.parse("hbm:models/weapons/animations/congolake.json"));
        static final HashMap<String, BusAnimation> flamethrower_anim = AnimationLoader.load(Identifier.parse("hbm:models/weapons/animations/flamethrower.json"));
        static final HashMap<String, BusAnimation> lag_anim = AnimationLoader.load(Identifier.parse("hbm:models/weapons/animations/lag.json"));
        static final HashMap<String, BusAnimation> spas_12_anim = AnimationLoader.load(Identifier.parse("hbm:models/weapons/animations/spas12.json"));
        static final HashMap<String, BusAnimation> stg77_anim = AnimationLoader.load(Identifier.parse("hbm:models/weapons/animations/stg77.json"));
    }

    static final class ClientPlayerAccess {
        static LocalPlayer player() {
            return Minecraft.getInstance().player;
        }
    }

    // NEXT's config: its defaults
    static final class GunVisualConfig {
        static final boolean legacyAnimations = false;
    }

    // Lego.LAMBDA_DEBUG_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_DEBUG_ANIMS = (stack, type) -> {
        switch (type) {
            case CYCLE:
                return new BusAnimation()
                    .addBus("RECOIL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 50).addPos(0.0, 0.0, -3.0, 50).addPos(0.0, 0.0, 0.0, 250))
                    .addBus("HAMMER", new BusAnimationSequence().addPos(0.0, 0.0, 1.0, 50).addPos(0.0, 0.0, 1.0, 400).addPos(0.0, 0.0, 0.0, 200))
                    .addBus("DRUM", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 450).addPos(0.0, 0.0, 1.0, 200));
            case CYCLE_DRY:
                return new BusAnimation()
                    .addBus("HAMMER", new BusAnimationSequence().addPos(0.0, 0.0, 1.0, 50).addPos(0.0, 0.0, 1.0, 400).addPos(0.0, 0.0, 0.0, 200))
                    .addBus("DRUM", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 450).addPos(0.0, 0.0, 1.0, 200));
            case EQUIP:
                return new BusAnimation().addBus("ROTATE", new BusAnimationSequence().addPos(-360.0, 0.0, 0.0, 350));
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "RELAOD_TILT",
                        new BusAnimationSequence()
                            .addPos(-15.0, 0.0, 0.0, 100)
                            .addPos(65.0, 0.0, 0.0, 100)
                            .addPos(45.0, 0.0, 0.0, 50)
                            .addPos(0.0, 0.0, 0.0, 200)
                            .addPos(0.0, 0.0, 0.0, 1450)
                            .addPos(-80.0, 0.0, 0.0, 100)
                            .addPos(-80.0, 0.0, 0.0, 100)
                            .addPos(0.0, 0.0, 0.0, 200)
                    )
                    .addBus(
                        "RELOAD_CYLINDER",
                        new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 200).addPos(90.0, 0.0, 0.0, 100).addPos(90.0, 0.0, 0.0, 1700).addPos(0.0, 0.0, 0.0, 70)
                    )
                    .addBus(
                        "RELOAD_LIFT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 350)
                            .addPos(-45.0, 0.0, 0.0, 250)
                            .addPos(-45.0, 0.0, 0.0, 350)
                            .addPos(-15.0, 0.0, 0.0, 200)
                            .addPos(-15.0, 0.0, 0.0, 1050)
                            .addPos(0.0, 0.0, 0.0, 100)
                    )
                    .addBus("RELOAD_JOLT", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 600).addPos(2.0, 0.0, 0.0, 50).addPos(0.0, 0.0, 0.0, 100))
                    .addBus(
                        "RELOAD_BULLETS",
                        new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 650).addPos(10.0, 0.0, 0.0, 300).addPos(10.0, 0.0, 0.0, 200).addPos(0.0, 0.0, 0.0, 700)
                    )
                    .addBus("RELOAD_BULLETS_CON", new BusAnimationSequence().addPos(1.0, 0.0, 0.0, 0).addPos(1.0, 0.0, 0.0, 950).addPos(0.0, 0.0, 0.0, 1));
            case INSPECT:
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "RELAOD_TILT",
                        new BusAnimationSequence()
                            .addPos(-15.0, 0.0, 0.0, 100)
                            .addPos(65.0, 0.0, 0.0, 100)
                            .addPos(45.0, 0.0, 0.0, 50)
                            .addPos(0.0, 0.0, 0.0, 200)
                            .addPos(0.0, 0.0, 0.0, 200)
                            .addPos(-80.0, 0.0, 0.0, 100)
                            .addPos(-80.0, 0.0, 0.0, 100)
                            .addPos(0.0, 0.0, 0.0, 200)
                    )
                    .addBus(
                        "RELOAD_CYLINDER",
                        new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 200).addPos(90.0, 0.0, 0.0, 100).addPos(90.0, 0.0, 0.0, 450).addPos(0.0, 0.0, 0.0, 70)
                    );
            default:
                return null;
        }
    };

    // XFactory10ga.LAMBDA_DOUBLE_BARREL_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_DOUBLE_BARREL_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(-60.0, 0.0, 0.0, 0).addPos(0.0, 0.0, -3.0, 500, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                return new BusAnimation()
                    .addBus("RECOIL", new BusAnimationSequence().addPos(0.0, 0.0, -1.0, 50).addPos(0.0, 0.0, 0.0, 250))
                    .addBus("BUCKLE", new BusAnimationSequence().addPos(0.0, -60.0, 0.0, 50).addPos(0.0, 0.0, 0.0, 250));
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "TURN",
                        new BusAnimationSequence()
                            .addPos(0.0, 30.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 30.0, 0.0, 1150)
                            .addPos(0.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "LEVER",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(0.0, 0.0, -90.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, -90.0, 1300)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "BARREL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 300)
                            .addPos(60.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(60.0, 0.0, 0.0, 1150)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 350)
                            .addPos(-5.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 700)
                            .addPos(-5.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(45.0, 0.0, 0.0, 150)
                            .addPos(45.0, 0.0, 0.0, 150)
                            .addPos(-5.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SHELLS",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 450)
                            .addPos(0.0, 0.0, -2.5, 100)
                            .addPos(0.0, -5.0, -5.0, 350, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, -3.0, -2.0, 0)
                            .addPos(0.0, 0.0, -2.0, 250)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus("SHELL_FLIP", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 450).addPos(-360.0, 0.0, 0.0, 450).addPos(0.0, 0.0, 0.0, 0));
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "LEVER",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(0.0, 0.0, -90.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, -90.0, 800)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "BARREL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 300)
                            .addPos(60.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(60.0, 0.0, 0.0, 650)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 350)
                            .addPos(-5.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 200)
                            .addPos(-5.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(45.0, 0.0, 0.0, 150)
                            .addPos(45.0, 0.0, 0.0, 150)
                            .addPos(-5.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            default:
                return null;
        }
    };

    // XFactory12ga.LAMBDA_LIBERATOR_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_LIBERATOR_ANIMS = (stack, type) -> {
        int ammo = ((ItemGunBaseNT)stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0]
            .getMagazine(stack)
            .getAmount(stack, ClientPlayerAccess.player().getInventory());
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(60.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, -2.5, 50, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case CYCLE_DRY:
                return new BusAnimation();
            case RELOAD:
                if (ammo == 0) {
                    return new BusAnimation()
                        .addBus("LATCH", new BusAnimationSequence().addPos(15.0, 0.0, 0.0, 100))
                        .addBus("BREAK", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 100).addPos(60.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_DOWN))
                        .addBus(
                            "SHELL1",
                            new BusAnimationSequence()
                                .addPos(2.0, -4.0, -2.0, 0)
                                .addPos(2.0, -4.0, -2.0, 400)
                                .addPos(0.0, 0.0, -2.0, 450, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(0.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                        )
                        .addBus("SHELL2", new BusAnimationSequence().addPos(2.0, -4.0, -2.0, 0))
                        .addBus("SHELL3", new BusAnimationSequence().addPos(2.0, -4.0, -2.0, 0))
                        .addBus("SHELL4", new BusAnimationSequence().addPos(2.0, -4.0, -2.0, 0));
                } else if (ammo == 1) {
                    return new BusAnimation()
                        .addBus("LATCH", new BusAnimationSequence().addPos(15.0, 0.0, 0.0, 100))
                        .addBus("BREAK", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 100).addPos(60.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_DOWN))
                        .addBus("SHELL1", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                        .addBus(
                            "SHELL2",
                            new BusAnimationSequence()
                                .addPos(2.0, -4.0, -2.0, 0)
                                .addPos(2.0, -4.0, -2.0, 400)
                                .addPos(0.0, 0.0, -2.0, 450, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(0.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                        )
                        .addBus("SHELL3", new BusAnimationSequence().addPos(2.0, -4.0, -2.0, 0))
                        .addBus("SHELL4", new BusAnimationSequence().addPos(2.0, -4.0, -2.0, 0));
                } else if (ammo == 2) {
                    return new BusAnimation()
                        .addBus("LATCH", new BusAnimationSequence().addPos(15.0, 0.0, 0.0, 100))
                        .addBus("BREAK", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 100).addPos(60.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_DOWN))
                        .addBus("SHELL1", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                        .addBus("SHELL2", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                        .addBus(
                            "SHELL3",
                            new BusAnimationSequence()
                                .addPos(2.0, -4.0, -2.0, 0)
                                .addPos(2.0, -4.0, -2.0, 400)
                                .addPos(0.0, 0.0, -2.0, 450, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(0.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                        )
                        .addBus("SHELL4", new BusAnimationSequence().addPos(2.0, -4.0, -2.0, 0));
                } else if (ammo == 3) {
                    return new BusAnimation()
                        .addBus("LATCH", new BusAnimationSequence().addPos(15.0, 0.0, 0.0, 100))
                        .addBus("BREAK", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 100).addPos(60.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_DOWN))
                        .addBus("SHELL1", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                        .addBus("SHELL2", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                        .addBus("SHELL3", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                        .addBus(
                            "SHELL4",
                            new BusAnimationSequence()
                                .addPos(2.0, -4.0, -2.0, 0)
                                .addPos(2.0, -4.0, -2.0, 400)
                                .addPos(0.0, 0.0, -2.0, 450, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(0.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                        );
                }
            case RELOAD_CYCLE:
                if (ammo == 0) {
                    return new BusAnimation()
                        .addBus("LATCH", new BusAnimationSequence().addPos(15.0, 0.0, 0.0, 0))
                        .addBus("BREAK", new BusAnimationSequence().addPos(60.0, 0.0, 0.0, 0))
                        .addBus("SHELL1", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                        .addBus(
                            "SHELL2",
                            new BusAnimationSequence()
                                .addPos(2.0, -4.0, -2.0, 0)
                                .addPos(0.0, 0.0, -2.0, 450, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(0.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                        )
                        .addBus("SHELL3", new BusAnimationSequence().addPos(2.0, -4.0, -2.0, 0))
                        .addBus("SHELL4", new BusAnimationSequence().addPos(2.0, -4.0, -2.0, 0));
                } else if (ammo == 1) {
                    return new BusAnimation()
                        .addBus("LATCH", new BusAnimationSequence().addPos(15.0, 0.0, 0.0, 0))
                        .addBus("BREAK", new BusAnimationSequence().addPos(60.0, 0.0, 0.0, 0))
                        .addBus("SHELL1", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                        .addBus("SHELL2", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                        .addBus(
                            "SHELL3",
                            new BusAnimationSequence()
                                .addPos(2.0, -4.0, -2.0, 0)
                                .addPos(0.0, 0.0, -2.0, 450, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(0.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                        )
                        .addBus("SHELL4", new BusAnimationSequence().addPos(2.0, -4.0, -2.0, 0));
                } else {
                    if (ammo == 2) {
                        return new BusAnimation()
                            .addBus("LATCH", new BusAnimationSequence().addPos(15.0, 0.0, 0.0, 0))
                            .addBus("BREAK", new BusAnimationSequence().addPos(60.0, 0.0, 0.0, 0))
                            .addBus("SHELL1", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                            .addBus("SHELL2", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                            .addBus("SHELL3", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                            .addBus(
                                "SHELL4",
                                new BusAnimationSequence()
                                    .addPos(2.0, -4.0, -2.0, 0)
                                    .addPos(0.0, 0.0, -2.0, 450, BusAnimationKeyframe.IType.SIN_FULL)
                                    .addPos(0.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                            );
                    }

                    return null;
                }
            case RELOAD_END:
                return new BusAnimation()
                    .addBus("LATCH", new BusAnimationSequence().addPos(15.0, 0.0, 0.0, 0).addPos(15.0, 0.0, 0.0, 250).addPos(0.0, 0.0, 0.0, 50))
                    .addBus("BREAK", new BusAnimationSequence().addPos(60.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_UP))
                    .addBus(ammo >= 0 ? "SHELL1" : "NULL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                    .addBus(ammo >= 1 ? "SHELL2" : "NULL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                    .addBus(ammo >= 2 ? "SHELL3" : "NULL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                    .addBus(ammo >= 3 ? "SHELL4" : "NULL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                    .addBus(ammo < 0 ? "SHELL1" : "NULL", new BusAnimationSequence().addPos(2.0, -8.0, -2.0, 0))
                    .addBus(ammo < 1 ? "SHELL2" : "NULL", new BusAnimationSequence().addPos(2.0, -8.0, -2.0, 0))
                    .addBus(ammo < 2 ? "SHELL3" : "NULL", new BusAnimationSequence().addPos(2.0, -8.0, -2.0, 0))
                    .addBus(ammo < 3 ? "SHELL4" : "NULL", new BusAnimationSequence().addPos(2.0, -8.0, -2.0, 0));
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "LATCH",
                        new BusAnimationSequence()
                            .addPos(15.0, 0.0, 0.0, 0)
                            .addPos(15.0, 0.0, 0.0, 250)
                            .addPos(0.0, 0.0, 0.0, 50)
                            .addPos(0.0, 0.0, 0.0, 550)
                            .addPos(15.0, 0.0, 0.0, 100)
                            .addPos(15.0, 0.0, 0.0, 600)
                            .addPos(0.0, 0.0, 0.0, 50)
                    )
                    .addBus(
                        "BREAK",
                        new BusAnimationSequence()
                            .addPos(60.0, 0.0, 0.0, 0)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 600)
                            .addPos(45.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(45.0, 0.0, 0.0, 300)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(ammo >= 0 ? "SHELL1" : "NULL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                    .addBus(ammo >= 1 ? "SHELL2" : "NULL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                    .addBus(ammo >= 2 ? "SHELL3" : "NULL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                    .addBus(ammo >= 3 ? "SHELL4" : "NULL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                    .addBus(ammo < 0 ? "SHELL1" : "NULL", new BusAnimationSequence().addPos(2.0, -8.0, -2.0, 0))
                    .addBus(ammo < 1 ? "SHELL2" : "NULL", new BusAnimationSequence().addPos(2.0, -8.0, -2.0, 0))
                    .addBus(ammo < 2 ? "SHELL3" : "NULL", new BusAnimationSequence().addPos(2.0, -8.0, -2.0, 0))
                    .addBus(ammo < 3 ? "SHELL4" : "NULL", new BusAnimationSequence().addPos(2.0, -8.0, -2.0, 0));
            case INSPECT:
                return new BusAnimation()
                    .addBus("LATCH", new BusAnimationSequence().addPos(15.0, 0.0, 0.0, 100).addPos(15.0, 0.0, 0.0, 1100).addPos(0.0, 0.0, 0.0, 50))
                    .addBus(
                        "BREAK",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 100)
                            .addPos(60.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(60.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(ammo > 0 ? "SHELL1" : "NULL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                    .addBus(ammo > 1 ? "SHELL2" : "NULL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                    .addBus(ammo > 2 ? "SHELL3" : "NULL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                    .addBus(ammo > 3 ? "SHELL4" : "NULL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 0))
                    .addBus(ammo < 1 ? "SHELL1" : "NULL", new BusAnimationSequence().addPos(2.0, -8.0, -2.0, 0))
                    .addBus(ammo < 2 ? "SHELL2" : "NULL", new BusAnimationSequence().addPos(2.0, -8.0, -2.0, 0))
                    .addBus(ammo < 3 ? "SHELL3" : "NULL", new BusAnimationSequence().addPos(2.0, -8.0, -2.0, 0))
                    .addBus(ammo < 4 ? "SHELL4" : "NULL", new BusAnimationSequence().addPos(2.0, -8.0, -2.0, 0));
            default:
                return null;
        }
    };

    // XFactory12ga.LAMBDA_SPAS_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_SPAS_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(-60.0, 0.0, 0.0, 0).addPos(0.0, 0.0, -3.0, 500, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                return ResourceManager.spas_12_anim.get("Fire");
            case CYCLE_DRY:
                return ResourceManager.spas_12_anim.get("FireDry");
            case RELOAD:
                boolean empty = ((ItemGunBaseNT)stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0]
                        .getMagazine(stack)
                        .getAmount(stack, ClientPlayerAccess.player().getInventory())
                    <= 0;
                return ResourceManager.spas_12_anim.get(empty ? "ReloadEmptyStart" : "ReloadStart");
            case RELOAD_CYCLE:
                return ResourceManager.spas_12_anim.get("Reload");
            case RELOAD_END:
                return ResourceManager.spas_12_anim.get("ReloadEnd");
            case JAMMED:
                return ResourceManager.spas_12_anim.get("Jammed");
            case INSPECT:
                return ResourceManager.spas_12_anim.get("Inspect");
            case ALT_CYCLE:
                return ResourceManager.spas_12_anim.get("FireAlt");
            default:
                return null;
        }
    };

    // XFactory12ga.LAMBDA_SHREDDER_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_SHREDDER_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(60.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, -1.0, 50, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("CYCLE", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 150).addPos(0.0, 0.0, 18.0, 100));
            case CYCLE_DRY:
                return new BusAnimation().addBus("CYCLE", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 150).addPos(0.0, 0.0, 18.0, 100));
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "MAG",
                        new BusAnimationSequence()
                            .addPos(0.0, -8.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, -8.0, 0.0, 1000)
                            .addPos(0.0, 0.0, 0.0, 300)
                    )
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 750)
                            .addPos(-25.0, 0.0, 0.0, 300, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-25.0, 0.0, 0.0, 500)
                            .addPos(-27.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(-25.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-25.0, 0.0, 0.0, 150)
                            .addPos(0.0, 0.0, 0.0, 300, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case RELOAD_CYCLE:
            case RELOAD_END:
            default:
                return null;
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "MAG",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, -2.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 100)
                    )
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 750)
                            .addPos(-2.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "MAG",
                        new BusAnimationSequence()
                            .addPos(0.0, -1.0, 0.0, 150)
                            .addPos(6.0, -1.0, 0.0, 150)
                            .addPos(6.0, 12.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(6.0, -2.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(6.0, -1.0, 0.0, 50)
                            .addPos(6.0, -1.0, 0.0, 100)
                            .addPos(0.0, -1.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus("SPEEN", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 300).addPos(360.0, 0.0, 0.0, 700))
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 1450)
                            .addPos(-2.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    );
        }
    };

    // XFactory12ga.LAMBDA_SEXY_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_SEXY_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(45.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 1000, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                int amount = ((ItemGunBaseNT)stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack).getAmount(stack, null);
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .hold(50)
                            .addPos(0.0, 0.0, -0.25, 50, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("BARREL", new BusAnimationSequence().addPos(0.0, 0.0, -1.0, 50, BusAnimationKeyframe.IType.SIN_DOWN).addPos(0.0, 0.0, 0.0, 150))
                    .addBus("CYCLE", new BusAnimationSequence().addPos(1.0, 0.0, 0.0, 150))
                    .addBus(
                        "HOOD",
                        new BusAnimationSequence()
                            .hold(50)
                            .addPos(3.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus("SHELLS", new BusAnimationSequence().setPos(amount - 1, 0.0, 0.0));
            case CYCLE_DRY:
                return new BusAnimation().addBus("CYCLE", new BusAnimationSequence().addPos(0.0, 0.0, 18.0, 50));
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "LOWER",
                        new BusAnimationSequence()
                            .addPos(15.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(2750)
                            .addPos(12.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(15.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(1050)
                            .addPos(18.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(15.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(300)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("LEVER", new BusAnimationSequence().addPos(0.0, 0.0, 1.0, 150).hold(4700).addPos(0.0, 0.0, 0.0, 150))
                    .addBus(
                        "HOOD",
                        new BusAnimationSequence()
                            .hold(250)
                            .addPos(60.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(3250)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "BELT",
                        new BusAnimationSequence()
                            .setPos(1.0, 0.0, 0.0)
                            .hold(750)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_UP)
                            .hold(2000)
                            .addPos(1.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "MAG",
                        new BusAnimationSequence()
                            .hold(1500)
                            .addPos(0.0, -1.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(2.0, -1.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(7.0, 1.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(15.0, 2.0, 0.0, 250)
                            .setPos(0.0, -2.0, 0.0)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus("MAGROT", new BusAnimationSequence().hold(2250).addPos(0.0, 0.0, -180.0, 500, BusAnimationKeyframe.IType.SIN_FULL).setPos(0.0, 0.0, 0.0));
            case RELOAD_CYCLE:
            case RELOAD_END:
            case JAMMED:
            default:
                return null;
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "BOTTLE",
                        new BusAnimationSequence()
                            .setPos(8.0, -8.0, -2.0)
                            .addPos(6.0, -4.0, -2.0, 500, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(3.0, -3.0, -5.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(3.0, -2.0, -5.0, 1000)
                            .addPos(4.0, -6.0, -2.0, 750, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(6.0, -8.0, -2.0, 500, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "SIP",
                        new BusAnimationSequence()
                            .setPos(25.0, 0.0, 0.0)
                            .hold(500)
                            .addPos(-90.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-110.0, 0.0, 0.0, 1000)
                            .addPos(25.0, 0.0, 0.0, 750, BusAnimationKeyframe.IType.SIN_FULL)
                    );
        }
    };

    // XFactory12ga.LAMBDA_MARESLEG_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_MARESLEG_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(-60.0, 0.0, 0.0, 0).addPos(0.0, 0.0, -3.0, 500, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                return new BusAnimation()
                    .addBus("RECOIL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 50).addPos(0.0, 0.0, -1.0, 50).addPos(0.0, 0.0, 0.0, 250))
                    .addBus(
                        "SIGHT",
                        new BusAnimationSequence()
                            .addPos(35.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("LEVER", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 600).addPos(-85.0, 0.0, 0.0, 200).addPos(0.0, 0.0, 0.0, 200))
                    .addBus(
                        "TURN",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 600)
                            .addPos(0.0, 0.0, 45.0, 200, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus("HAMMER", new BusAnimationSequence().addPos(30.0, 0.0, 0.0, 50).addPos(30.0, 0.0, 0.0, 550).addPos(0.0, 0.0, 0.0, 200));
            case CYCLE_DRY:
                return new BusAnimation()
                    .addBus("LEVER", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 600).addPos(-90.0, 0.0, 0.0, 200).addPos(0.0, 0.0, 0.0, 200))
                    .addBus(
                        "TURN",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 600)
                            .addPos(0.0, 0.0, 45.0, 200, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus("HAMMER", new BusAnimationSequence().addPos(30.0, 0.0, 0.0, 50).addPos(30.0, 0.0, 0.0, 550).addPos(0.0, 0.0, 0.0, 200));
            case RELOAD:
                boolean empty = ((ItemGunBaseNT)stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0]
                        .getMagazine(stack)
                        .getAmount(stack, ClientPlayerAccess.player().getInventory())
                    <= 0;
                return new BusAnimation()
                    .addBus("LIFT", new BusAnimationSequence().addPos(30.0, 0.0, 0.0, 400, BusAnimationKeyframe.IType.SIN_FULL))
                    .addBus("LEVER", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 400).addPos(-85.0, 0.0, 0.0, 200))
                    .addBus(
                        "SHELL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 600)
                            .addPos(0.0, 0.25, -3.0, 0)
                            .addPos(0.0, empty ? 0.25 : 0.125, -1.5, 150, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, empty ? 0.25 : -0.25, 0.0, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                    )
                    .addBus("FLAG", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, empty ? 900 : 0).addPos(1.0, 1.0, 1.0, 0));
            case RELOAD_CYCLE:
                return new BusAnimation()
                    .addBus("LIFT", new BusAnimationSequence().addPos(30.0, 0.0, 0.0, 0))
                    .addBus("LEVER", new BusAnimationSequence().addPos(-85.0, 0.0, 0.0, 0))
                    .addBus(
                        "SHELL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.25, -3.0, 0)
                            .addPos(0.0, 0.125, -1.5, 150, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, -0.125, 0.0, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                    )
                    .addBus("FLAG", new BusAnimationSequence().addPos(1.0, 1.0, 1.0, 0));
            case RELOAD_END:
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(30.0, 0.0, 0.0, 0)
                            .addPos(30.0, 0.0, 0.0, 250)
                            .addPos(0.0, 0.0, 0.0, 400, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("LEVER", new BusAnimationSequence().addPos(-85.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 200))
                    .addBus("FLAG", new BusAnimationSequence().addPos(1.0, 1.0, 1.0, 0));
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(30.0, 0.0, 0.0, 0)
                            .addPos(30.0, 0.0, 0.0, 250)
                            .addPos(0.0, 0.0, 0.0, 400, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "LEVER",
                        new BusAnimationSequence()
                            .addPos(-85.0, 0.0, 0.0, 0)
                            .addPos(-15.0, 0.0, 0.0, 200)
                            .addPos(-15.0, 0.0, 0.0, 650)
                            .addPos(-85.0, 0.0, 0.0, 200)
                            .addPos(-15.0, 0.0, 0.0, 200)
                            .addPos(-15.0, 0.0, 0.0, 200)
                            .addPos(-85.0, 0.0, 0.0, 200)
                            .addPos(0.0, 0.0, 0.0, 200)
                    )
                    .addBus(
                        "TURN",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 850)
                            .addPos(0.0, 0.0, 45.0, 200, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 45.0, 800)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus("FLAG", new BusAnimationSequence().addPos(1.0, 1.0, 1.0, 0));
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(-35.0, 0.0, 0.0, 300, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-35.0, 0.0, 0.0, 1150)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "TURN",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 450)
                            .addPos(0.0, 0.0, -90.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, -90.0, 500)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            default:
                return null;
        }
    };

    // XFactory12ga.LAMBDA_MARESLEG_SHORT_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_MARESLEG_SHORT_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(-60.0, 0.0, 0.0, 0).addPos(0.0, 0.0, -3.0, 250, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                return new BusAnimation()
                    .addBus("RECOIL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 50).addPos(0.0, 0.0, -1.0, 50).addPos(0.0, 0.0, 0.0, 250))
                    .addBus(
                        "SIGHT",
                        new BusAnimationSequence()
                            .addPos(35.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("LEVER", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 600).addPos(-85.0, 0.0, 0.0, 200).addPos(0.0, 0.0, 0.0, 200))
                    .addBus("HAMMER", new BusAnimationSequence().addPos(30.0, 0.0, 0.0, 50).addPos(30.0, 0.0, 0.0, 550).addPos(0.0, 0.0, 0.0, 200))
                    .addBus("FLIP", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 600).addPos(360.0, 0.0, 0.0, 400))
                    .addBus("SHELL", new BusAnimationSequence().addPos(-20.0, 0.0, 0.0, 0));
            case CYCLE_DRY:
                return new BusAnimation()
                    .addBus("LEVER", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 600).addPos(-90.0, 0.0, 0.0, 200).addPos(0.0, 0.0, 0.0, 200))
                    .addBus("HAMMER", new BusAnimationSequence().addPos(30.0, 0.0, 0.0, 50).addPos(30.0, 0.0, 0.0, 550).addPos(0.0, 0.0, 0.0, 200))
                    .addBus("FLIP", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 600).addPos(360.0, 0.0, 0.0, 400))
                    .addBus("SHELL", new BusAnimationSequence().addPos(-20.0, 0.0, 0.0, 0));
            case RELOAD:
            case RELOAD_CYCLE:
            case RELOAD_END:
            default:
                return NextGunAnimations.LAMBDA_MARESLEG_ANIMS.apply(stack, type);
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(30.0, 0.0, 0.0, 0)
                            .addPos(30.0, 0.0, 0.0, 250)
                            .addPos(0.0, 0.0, 0.0, 400, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "LEVER",
                        new BusAnimationSequence()
                            .addPos(-85.0, 0.0, 0.0, 0)
                            .addPos(-15.0, 0.0, 0.0, 200)
                            .addPos(-15.0, 0.0, 0.0, 650)
                            .addPos(-85.0, 0.0, 0.0, 200)
                            .addPos(-15.0, 0.0, 0.0, 200)
                            .addPos(-15.0, 0.0, 0.0, 200)
                            .addPos(-85.0, 0.0, 0.0, 200)
                            .addPos(0.0, 0.0, 0.0, 200)
                    )
                    .addBus("FLAG", new BusAnimationSequence().addPos(1.0, 1.0, 1.0, 0));
        }
    };

    // XFactory22lr.LAMBDA_AM180_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_AM180_ANIMS = (stack, type) -> {
        if (GunVisualConfig.legacyAnimations) {
            switch (type) {
                case EQUIP:
                    return new BusAnimation()
                        .addBus("EQUIP", new BusAnimationSequence().addPos(45.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL));
                case CYCLE:
                    return new BusAnimation()
                        .addBus(
                            "RECOIL",
                            new BusAnimationSequence()
                                .addPos(0.0, 0.0, ItemGunBaseNT.getIsAiming(stack) ? -0.125 : -0.25, 15, BusAnimationKeyframe.IType.SIN_DOWN)
                                .addPos(0.0, 0.0, 0.0, 35, BusAnimationKeyframe.IType.SIN_FULL)
                        );
                case CYCLE_DRY:
                    return new BusAnimation()
                        .addBus(
                            "BOLT",
                            new BusAnimationSequence()
                                .addPos(0.0, 0.0, 0.0, 550)
                                .addPos(0.0, 0.0, -1.5, 100, BusAnimationKeyframe.IType.SIN_UP)
                                .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                        )
                        .addBus(
                            "TURN",
                            new BusAnimationSequence()
                                .addPos(0.0, 0.0, 0.0, 300)
                                .addPos(0.0, 0.0, 15.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(0.0, 0.0, 15.0, 400)
                                .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                        );
                case RELOAD:
                    return new BusAnimation()
                        .addBus(
                            "MAGTURN",
                            new BusAnimationSequence()
                                .addPos(15.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(15.0, 0.0, 0.0, 250)
                                .addPos(15.0, 0.0, 70.0, 300, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(15.0, 0.0, 0.0, 0)
                                .addPos(15.0, 0.0, 0.0, 750)
                                .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                        )
                        .addBus(
                            "MAG",
                            new BusAnimationSequence()
                                .addPos(0.0, 0.0, 0.0, 250)
                                .addPos(2.0, 0.0, -4.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(-10.0, 2.0, -4.0, 300, BusAnimationKeyframe.IType.SIN_UP)
                                .addPos(3.0, -6.0, -4.0, 0)
                                .addPos(2.0, 0.0, -4.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                        )
                        .addBus(
                            "BOLT",
                            new BusAnimationSequence()
                                .addPos(0.0, 0.0, 0.0, 2250)
                                .addPos(0.0, 0.0, -1.5, 100, BusAnimationKeyframe.IType.SIN_UP)
                                .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                        )
                        .addBus(
                            "TURN",
                            new BusAnimationSequence()
                                .addPos(0.0, 0.0, 0.0, 2000)
                                .addPos(0.0, 0.0, 15.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(0.0, 0.0, 15.0, 400)
                                .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                        );
                case JAMMED:
                    return new BusAnimation()
                        .addBus(
                            "BOLT",
                            new BusAnimationSequence()
                                .addPos(0.0, 0.0, 0.0, 750)
                                .addPos(0.0, 0.0, -1.5, 100, BusAnimationKeyframe.IType.SIN_UP)
                                .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                        )
                        .addBus(
                            "TURN",
                            new BusAnimationSequence()
                                .addPos(0.0, 0.0, 0.0, 500)
                                .addPos(0.0, 0.0, 45.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(0.0, 0.0, 45.0, 400)
                                .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                        );
                case INSPECT:
                    return new BusAnimation()
                        .addBus(
                            "MAGTURN",
                            new BusAnimationSequence()
                                .addPos(15.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(15.0, 0.0, 0.0, 1400)
                                .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                        )
                        .addBus(
                            "MAG",
                            new BusAnimationSequence()
                                .addPos(0.0, 0.0, 0.0, 200)
                                .addPos(4.0, -1.0, -4.0, 200, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(4.0, -1.5, -4.0, 50)
                                .addPos(4.0, 0.0, -4.0, 100)
                                .addPos(4.0, 6.0, -4.0, 250, BusAnimationKeyframe.IType.SIN_DOWN)
                                .addPos(4.0, 0.0, -4.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                                .addPos(4.0, -1.0, -4.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                                .addPos(4.0, -1.0, -4.0, 250)
                                .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                        )
                        .addBus(
                            "MAGSPIN",
                            new BusAnimationSequence()
                                .addPos(0.0, 0.0, 0.0, 600)
                                .addPos(-400.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(-400.0, 0.0, 0.0, 250)
                                .addPos(-360.0, 0.0, 0.0, 250)
                        );
            }
        } else {
            switch (type) {
                case EQUIP:
                    return new BusAnimation()
                        .addBus("EQUIP", new BusAnimationSequence().addPos(45.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL));
                case CYCLE:
                    return ResourceManager.am180_anim.get("Fire");
                case CYCLE_DRY:
                    return ResourceManager.am180_anim.get("FireDry");
                case RELOAD:
                    return ResourceManager.am180_anim.get("Reload");
                case JAMMED:
                    return ResourceManager.am180_anim.get("Jammed");
                case INSPECT:
                    return ResourceManager.am180_anim.get("Inspect");
            }
        }

        return null;
    };

    // XFactory22lr.LAMBDA_STAR_F_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_STAR_F_ANIMS = (stack, type) -> {
        int ammo = ((ItemGunBaseNT)stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0]
            .getMagazine(stack)
            .getAmount(stack, ClientPlayerAccess.player().getInventory());
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(45.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 50)
                            .addPos(0.0, 0.0, ItemGunBaseNT.getIsAiming(stack) ? -0.125 : -0.5, 15, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 35, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SLIDE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 50)
                            .addPos(0.0, 0.0, ItemGunBaseNT.getIsAiming(stack) ? -0.5 : -1.0, 25, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 75, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "HAMMER",
                        new BusAnimationSequence()
                            .addPos(1.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_DOWN)
                    )
                    .addBus(
                        "BULLET",
                        ammo <= 1
                            ? new BusAnimationSequence().setPos(100.0, 0.0, 0.0)
                            : new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 90).addPos(0.0, 0.5, 2.25, 50)
                    );
            case CYCLE_DRY:
                return new BusAnimation()
                    .addBus(
                        "HAMMER",
                        new BusAnimationSequence()
                            .addPos(1.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                            .hold(450)
                            .addPos(0.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_DOWN)
                    )
                    .addBus(
                        "SLIDE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, ItemGunBaseNT.getIsAiming(stack) ? -0.5 : -1.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(100)
                            .addPos(0.0, 0.0, 0.0, 75, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 600)
                            .addPos(-3.0, 0.0, 0.0, 175, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("BULLET", new BusAnimationSequence().setPos(100.0, 0.0, 0.0));
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "TILT",
                        new BusAnimationSequence()
                            .addPos(-30.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(1500)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SLIDE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(0.0, 0.0, -1.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(1125)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "MAG",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(0.0, -7.0, -1.5, 300, BusAnimationKeyframe.IType.SIN_UP)
                            .hold(400)
                            .addPos(0.0, 0.0, 0.0, 300, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(3.0, 0.0, 0.0, 750, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-3.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "TURN",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 200)
                            .addPos(0.0, 0.0, 15.0, 300, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(900)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "BULLET",
                        new BusAnimationSequence().setPos(ammo <= 1 ? 100.0 : 0.0, 0.0, 0.0).hold(750).setPos(0.0, 0.0, 0.0).hold(750).addPos(0.0, 0.5, 2.25, 50)
                    );
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "TILT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(-30.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(800)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "TURN",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 25.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(800)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SLIDE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 750)
                            .addPos(0.0, 0.0, -0.5, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(100)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                            .hold(100)
                            .addPos(0.0, 0.0, -0.5, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(100)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "BULLET",
                        new BusAnimationSequence()
                            .setPos(0.0, 0.5, 2.25)
                            .hold(750)
                            .addPos(0.0, 0.5, 1.25, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(100)
                            .addPos(0.0, 0.5, 2.25, 100, BusAnimationKeyframe.IType.SIN_UP)
                            .hold(100)
                            .addPos(0.0, 0.5, 1.25, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(100)
                            .addPos(0.0, 0.5, 2.25, 100, BusAnimationKeyframe.IType.SIN_UP)
                    );
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "TILT",
                        new BusAnimationSequence()
                            .addPos(-30.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(1500)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "TURN",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 25.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(1500)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SLIDE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 350)
                            .addPos(0.0, 0.0, -0.5, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(1125)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "BULLET",
                        ammo <= 1
                            ? new BusAnimationSequence().setPos(100.0, 0.0, 0.0)
                            : new BusAnimationSequence()
                                .setPos(0.0, 0.5, 2.25)
                                .hold(350)
                                .addPos(0.0, 0.5, 1.25, 100, BusAnimationKeyframe.IType.SIN_FULL)
                                .hold(1125)
                                .addPos(0.0, 0.5, 2.25, 100, BusAnimationKeyframe.IType.SIN_UP)
                    );
            default:
                return null;
        }
    };

    // XFactory357.LAMBDA_ATLAS_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_ATLAS_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(-90.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                return new BusAnimation()
                    .addBus("RECOIL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 50).addPos(0.0, 0.0, -3.0, 50).addPos(0.0, 0.0, 0.0, 250))
                    .addBus("HAMMER", new BusAnimationSequence().addPos(0.0, 0.0, 1.0, 50).addPos(0.0, 0.0, 1.0, 300).addPos(0.0, 0.0, 0.0, 200))
                    .addBus("DRUM", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 250).addPos(0.0, 0.0, 1.0, 200));
            case CYCLE_DRY:
                return new BusAnimation()
                    .addBus("HAMMER", new BusAnimationSequence().addPos(0.0, 0.0, 1.0, 50).addPos(0.0, 0.0, 1.0, 200).addPos(0.0, 0.0, 0.0, 200))
                    .addBus("DRUM", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 250).addPos(0.0, 0.0, 1.0, 200));
            case RELOAD:
                return new BusAnimation()
                    .addBus("LATCH", new BusAnimationSequence().addPos(0.0, 0.0, 90.0, 300).addPos(0.0, 0.0, 90.0, 2000).addPos(0.0, 0.0, 0.0, 150))
                    .addBus(
                        "FRONT",
                        new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 200).addPos(0.0, 0.0, 45.0, 150).addPos(0.0, 0.0, 45.0, 2000).addPos(0.0, 0.0, 0.0, 75)
                    )
                    .addBus(
                        "RELOAD_ROT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 300)
                            .addPos(60.0, 0.0, 0.0, 500)
                            .addPos(60.0, 0.0, 0.0, 500)
                            .addPos(0.0, -90.0, -90.0, 0)
                            .addPos(0.0, -90.0, -90.0, 600)
                            .addPos(0.0, 0.0, 0.0, 300)
                            .addPos(0.0, 0.0, 0.0, 100)
                            .addPos(-45.0, 0.0, 0.0, 50)
                            .addPos(-45.0, 0.0, 0.0, 100)
                            .addPos(0.0, 0.0, 0.0, 300)
                    )
                    .addBus("RELOAD_MOVE", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 300).addPos(0.0, -15.0, 0.0, 1000).addPos(0.0, 0.0, 0.0, 450))
                    .addBus("DRUM_PUSH", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 1600).addPos(0.0, 0.0, -5.0, 0).addPos(0.0, 0.0, 0.0, 300));
            case INSPECT:
                return new BusAnimation()
                    .addBus("LATCH", new BusAnimationSequence().addPos(0.0, 0.0, 90.0, 300).addPos(0.0, 0.0, 90.0, 1000).addPos(0.0, 0.0, 0.0, 150))
                    .addBus(
                        "FRONT",
                        new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 200).addPos(0.0, 0.0, 45.0, 150).addPos(0.0, 0.0, 45.0, 1000).addPos(0.0, 0.0, 0.0, 75)
                    )
                    .addBus(
                        "RELOAD_ROT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 300)
                            .addPos(45.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(45.0, 0.0, 0.0, 500)
                            .addPos(-45.0, 0.0, 0.0, 50)
                            .addPos(-45.0, 0.0, 0.0, 100)
                            .addPos(0.0, 0.0, 0.0, 300)
                    )
                    .addBus(
                        "RELOAD_MOVE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 300)
                            .addPos(0.0, -2.5, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, -2.5, 0.0, 500)
                            .addPos(0.0, 0.0, 0.0, 350)
                    );
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "LATCH",
                        new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 500).addPos(0.0, 0.0, 90.0, 300).addPos(0.0, 0.0, 90.0, 1000).addPos(0.0, 0.0, 0.0, 150)
                    )
                    .addBus(
                        "FRONT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 0.0, 200)
                            .addPos(0.0, 0.0, 45.0, 150)
                            .addPos(0.0, 0.0, 45.0, 1000)
                            .addPos(0.0, 0.0, 0.0, 75)
                    )
                    .addBus(
                        "RELOAD_ROT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 0.0, 300)
                            .addPos(45.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(45.0, 0.0, 0.0, 500)
                            .addPos(-45.0, 0.0, 0.0, 50)
                            .addPos(-45.0, 0.0, 0.0, 100)
                            .addPos(0.0, 0.0, 0.0, 300)
                    )
                    .addBus(
                        "RELOAD_MOVE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 0.0, 300)
                            .addPos(0.0, -2.5, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, -2.5, 0.0, 500)
                            .addPos(0.0, 0.0, 0.0, 350)
                    );
            default:
                return null;
        }
    };

    // XFactory357.LAMBDA_DANI_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_DANI_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation().addBus("EQUIP", new BusAnimationSequence().addPos(1080.0, 0.0, 0.0, 1000, BusAnimationKeyframe.IType.SIN_DOWN));
            default:
                return NextGunAnimations.LAMBDA_ATLAS_ANIMS.apply(stack, type);
        }
    };

    // XFactory35800.LAMBDA_ABERRATOR
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_ABERRATOR = (stack, type) -> {
        boolean aim = ItemGunBaseNT.getIsAiming(stack);
        int ammo = ((ItemGunBaseNT)stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack).getAmount(stack, null);
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(360.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL))
                    .addBus("RISE", new BusAnimationSequence().addPos(0.0, -3.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL));
            case CYCLE:
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 50)
                            .addPos(aim ? -15.0 : -25.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SIGHT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 50)
                            .addPos(aim ? 5.0 : 15.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SLIDE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 50)
                            .addPos(0.0, 0.0, -1.125, 50, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, -1.125, 50)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        ammo <= 1 ? "NULL" : "BULLET",
                        new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 150).addPos(0.0, 0.375, 1.125, 150, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "HAMMER",
                        new BusAnimationSequence()
                            .addPos(45.0, 0.0, 0.0, 50)
                            .addPos(-45.0, 0.0, -1.125, 50, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(-20.0, 0.0, -1.125, 50)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                    );
            case CYCLE_DRY:
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 700)
                            .addPos(-5.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SLIDE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 550)
                            .addPos(0.0, 0.0, -1.125, 150, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, -1.125, 50)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "HAMMER",
                        new BusAnimationSequence()
                            .addPos(45.0, 0.0, 0.0, 50)
                            .addPos(45.0, 0.0, 0.0, 500)
                            .addPos(-45.0, 0.0, -1.125, 150, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-20.0, 0.0, -1.125, 50)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                    );
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "ROLL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 20.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 20.0, 50)
                            .addPos(0.0, 0.0, -45.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "MAG",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 350)
                            .addPos(0.0, -2.0, 0.0, 0)
                            .addPos(-15.0, -5.0, 0.0, 350)
                            .addPos(-15.0, 0.0, 0.0, 0)
                            .addPos(-15.0, 0.0, 0.0, 700)
                            .addPos(3.0, 3.0, 0.0, 0)
                            .addPos(0.0, -2.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, -2.0, 0.0, 50)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                    )
                    .addBus("MAGROLL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 350).addPos(0.0, 0.0, -180.0, 250).addPos(0.0, 0.0, 0.0, 0))
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 750)
                            .addPos(5.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-190.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-190.0, 0.0, 0.0, 450)
                            .addPos(-360.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 0)
                    )
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 2350)
                            .addPos(-5.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SLIDE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 2200)
                            .addPos(0.0, 0.0, -1.125, 150, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, -1.125, 50)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "HAMMER",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 2250)
                            .addPos(-45.0, 0.0, -1.125, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-20.0, 0.0, -1.125, 50)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "BULLET",
                        new BusAnimationSequence()
                            .addPos(ammo > 0 ? 0.0 : -100.0, 0.0, 0.0, 0)
                            .addPos(ammo > 0 ? 0.0 : -100.0, 0.0, 0.0, 2400)
                            .addPos(0.0, 0.0, 0.0, 0)
                            .addPos(0.0, 0.375, 1.125, 150, BusAnimationKeyframe.IType.SIN_UP)
                    );
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 0)
                            .addPos(-720.0, 0.0, 0.0, 1000, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-720.0, 0.0, 0.0, 250)
                            .addPos(0.0, 0.0, 0.0, 1000, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            default:
                return null;
        }
    };

    // XFactory40mm.LAMBDA_FLAREGUN_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_FLAREGUN_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(-90.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                return new BusAnimation()
                    .addBus("RECOIL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 50).addPos(0.0, 0.0, -3.0, 50).addPos(0.0, 0.0, 0.0, 250))
                    .addBus("HAMMER", new BusAnimationSequence().addPos(15.0, 0.0, 0.0, 50).addPos(15.0, 0.0, 0.0, 550).addPos(0.0, 0.0, 0.0, 100));
            case CYCLE_DRY:
                return new BusAnimation()
                    .addBus("HAMMER", new BusAnimationSequence().addPos(15.0, 0.0, 0.0, 50).addPos(15.0, 0.0, 0.0, 550).addPos(0.0, 0.0, 0.0, 100));
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "OPEN",
                        new BusAnimationSequence()
                            .addPos(45.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(45.0, 0.0, 0.0, 750)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "SHELL",
                        new BusAnimationSequence()
                            .addPos(4.0, -8.0, -4.0, 0)
                            .addPos(4.0, -8.0, -4.0, 200)
                            .addPos(0.0, 0.0, -5.0, 500, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "FLIP",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 200)
                            .addPos(25.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(25.0, 0.0, 0.0, 800)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_DOWN)
                    );
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "OPEN",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(45.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(45.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "FLIP",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 0.0, 200)
                            .addPos(25.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(25.0, 0.0, 0.0, 550)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_DOWN)
                    );
            case INSPECT:
                return new BusAnimation().addBus("FLIP", new BusAnimationSequence().addPos(-1080.0, 0.0, 0.0, 1500, BusAnimationKeyframe.IType.SIN_FULL));
            default:
                return null;
        }
    };

    // XFactory40mm.LAMBDA_CONGOLAKE_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_CONGOLAKE_ANIMS = (stack, type) -> {
        int ammo = ((ItemGunBaseNT)stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0]
            .getMagazine(stack)
            .getAmount(stack, ClientPlayerAccess.player().getInventory());
        switch (type) {
            case EQUIP:
                return ResourceManager.congolake_anim.get("Equip");
            case CYCLE:
                return ResourceManager.congolake_anim.get(ammo <= 1 ? "FireEmpty" : "Fire");
            case CYCLE_DRY:
            default:
                return null;
            case RELOAD:
                return ResourceManager.congolake_anim.get(ammo == 0 ? "ReloadEmpty" : "ReloadStart");
            case JAMMED:
                return ResourceManager.congolake_anim.get("Jammed");
            case INSPECT:
                return ResourceManager.congolake_anim.get("Inspect");
            case RELOAD_CYCLE:
                return ResourceManager.congolake_anim.get("Reload");
            case RELOAD_END:
                return ResourceManager.congolake_anim.get("ReloadEnd");
        }
    };

    // XFactory40mm.LAMBDA_MK108_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_MK108_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().setPos(45.0, 0.0, 0.0).addPos(0.0, 0.0, 0.0, 1000, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                int amount = ((ItemGunBaseNT)stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack).getAmount(stack, null);
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .hold(50)
                            .addPos(0.0, 0.0, -0.25, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "BARREL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, -1.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("CYCLE", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 100).addPos(1.0, 0.0, 0.0, 150))
                    .addBus("SHELLS", new BusAnimationSequence().setPos(amount - 1, 0.0, 0.0));
            case CYCLE_DRY:
                return new BusAnimation()
                    .addBus("HAMMER", new BusAnimationSequence().addPos(15.0, 0.0, 0.0, 50).addPos(15.0, 0.0, 0.0, 550).addPos(0.0, 0.0, 0.0, 100));
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(10.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .holdUntil(1250)
                            .addPos(-50.0, 0.0, 0.0, 750, BusAnimationKeyframe.IType.SIN_FULL)
                            .holdUntil(5500)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(500)
                            .addPos(1.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "LID",
                        new BusAnimationSequence()
                            .addPos(60.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .holdUntil(6000)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "BELT",
                        new BusAnimationSequence()
                            .setPos(1.0, 0.0, 0.0)
                            .hold(500)
                            .addPos(0.0, 0.0, 0.0, 750, BusAnimationKeyframe.IType.SIN_UP)
                            .holdUntil(4500)
                            .addPos(1.0, 0.0, 0.0, 750, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "DRUM",
                        new BusAnimationSequence()
                            .hold(2000)
                            .addPos(2.5, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(2.5, -2.0, -8.0, 500, BusAnimationKeyframe.IType.SIN_UP)
                            .setPos(4.0, -3.0, -8.0)
                            .addPos(2.5, 0.0, 0.0, 1000, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_UP)
                    );
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "LID",
                        new BusAnimationSequence()
                            .hold(250)
                            .addPos(45.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .hold(1000)
                            .addPos(1.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case INSPECT:
                int yeetHorizontal = 750;
                int untilImpact = yeetHorizontal * 9 / 15;
                int delay = 250;
                int height = 6;
                int arcUp = untilImpact * 5 / 8;
                int arcDown = untilImpact * 3 / 8;
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .hold(untilImpact)
                            .addPos(1.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(delay - 150)
                            .addPos(1.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(delay - 150)
                            .addPos(1.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("GRENH1", new BusAnimationSequence().setPos(9.0, 0.0, 0.0).addPos(-6.0, 0.0, 0.0, yeetHorizontal))
                    .addBus(
                        "GRENV1",
                        new BusAnimationSequence()
                            .setPos(0.0, -2.0, 0.0)
                            .addPos(0.0, height, 0.0, arcUp, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 2.0, 0.0, arcDown, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 3.0, 0.0, yeetHorizontal - untilImpact, BusAnimationKeyframe.IType.SIN_DOWN)
                    )
                    .addBus(
                        "GRENS1",
                        new BusAnimationSequence().addPos(720.0, 0.0, 0.0, untilImpact).setPos(0.0, 0.0, 0.0).addPos(360.0, 0.0, 0.0, yeetHorizontal - untilImpact)
                    )
                    .addBus("GRENH2", new BusAnimationSequence().setPos(9.0, 0.0, 0.0).hold(delay).addPos(-6.0, 0.0, 0.0, yeetHorizontal))
                    .addBus(
                        "GRENV2",
                        new BusAnimationSequence()
                            .setPos(0.0, -2.0, 0.0)
                            .hold(delay)
                            .addPos(0.0, height, 0.0, arcUp, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 2.0, 0.0, arcDown, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 3.0, 0.0, yeetHorizontal - untilImpact, BusAnimationKeyframe.IType.SIN_DOWN)
                    )
                    .addBus(
                        "GRENS2",
                        new BusAnimationSequence()
                            .hold(delay)
                            .addPos(720.0, 0.0, 0.0, untilImpact)
                            .setPos(0.0, 0.0, 0.0)
                            .addPos(360.0, 0.0, 0.0, yeetHorizontal - untilImpact)
                    )
                    .addBus("GRENH3", new BusAnimationSequence().setPos(9.0, 0.0, 0.0).hold(delay * 2).addPos(-6.0, 0.0, 0.0, yeetHorizontal))
                    .addBus(
                        "GRENV3",
                        new BusAnimationSequence()
                            .setPos(0.0, -2.0, 0.0)
                            .hold(delay * 2)
                            .addPos(0.0, height, 0.0, arcUp, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 2.0, 0.0, arcDown, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 3.0, 0.0, yeetHorizontal - untilImpact, BusAnimationKeyframe.IType.SIN_DOWN)
                    )
                    .addBus(
                        "GRENS3",
                        new BusAnimationSequence()
                            .hold(delay * 2)
                            .addPos(720.0, 0.0, 0.0, untilImpact)
                            .setPos(0.0, 0.0, 0.0)
                            .addPos(360.0, 0.0, 0.0, yeetHorizontal - untilImpact)
                    );
            default:
                return null;
        }
    };

    // XFactory44.LAMBDA_HENRY_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_HENRY_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(-90.0, 0.0, 0.0, 0).addPos(0.0, 0.0, -3.0, 350, BusAnimationKeyframe.IType.SIN_DOWN))
                    .addBus(
                        "SIGHT",
                        new BusAnimationSequence()
                            .addPos(80.0, 0.0, 0.0, 0)
                            .addPos(80.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, -3.0, 250, BusAnimationKeyframe.IType.SIN_DOWN)
                    );
            case CYCLE:
                return new BusAnimation()
                    .addBus("RECOIL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 50).addPos(0.0, 0.0, -1.0, 50).addPos(0.0, 0.0, 0.0, 250))
                    .addBus(
                        "SIGHT",
                        new BusAnimationSequence()
                            .addPos(35.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("LEVER", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 600).addPos(-90.0, 0.0, 0.0, 200).addPos(0.0, 0.0, 0.0, 200))
                    .addBus(
                        "TURN",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 600)
                            .addPos(0.0, 0.0, 45.0, 200, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus("HAMMER", new BusAnimationSequence().addPos(30.0, 0.0, 0.0, 50).addPos(30.0, 0.0, 0.0, 550).addPos(0.0, 0.0, 0.0, 200));
            case CYCLE_DRY:
                return new BusAnimation()
                    .addBus("LEVER", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 600).addPos(-90.0, 0.0, 0.0, 200).addPos(0.0, 0.0, 0.0, 200))
                    .addBus(
                        "TURN",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 600)
                            .addPos(0.0, 0.0, 45.0, 200, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus("HAMMER", new BusAnimationSequence().addPos(30.0, 0.0, 0.0, 50).addPos(30.0, 0.0, 0.0, 550).addPos(0.0, 0.0, 0.0, 200));
            case RELOAD:
                return new BusAnimation()
                    .addBus("LIFT", new BusAnimationSequence().addPos(-60.0, 0.0, 0.0, 400, BusAnimationKeyframe.IType.SIN_FULL))
                    .addBus("TWIST", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 500).addPos(0.0, 0.0, -90.0, 200, BusAnimationKeyframe.IType.SIN_FULL))
                    .addBus(
                        "BULLET",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 700)
                            .addPos(3.0, 0.0, -6.0, 0)
                            .addPos(0.0, 0.0, 1.0, 300, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case RELOAD_CYCLE:
                return new BusAnimation()
                    .addBus("LIFT", new BusAnimationSequence().addPos(-60.0, 0.0, 0.0, 0))
                    .addBus("TWIST", new BusAnimationSequence().addPos(0.0, 0.0, -90.0, 0))
                    .addBus(
                        "BULLET",
                        new BusAnimationSequence()
                            .addPos(3.0, 0.0, -6.0, 0)
                            .addPos(0.0, 0.0, 1.0, 300, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case RELOAD_END:
                boolean empty = ((ItemGunBaseNT)stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack).getAmountBeforeReload(stack) <= 0;
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(-60.0, 0.0, 0.0, 0)
                            .addPos(-60.0, 0.0, 0.0, 300)
                            .addPos(0.0, 0.0, 0.0, 400, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("TWIST", new BusAnimationSequence().addPos(0.0, 0.0, -90.0, 0).addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_FULL))
                    .addBus("LEVER", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 700).addPos(empty ? -90.0 : 0.0, 0.0, 0.0, 200).addPos(0.0, 0.0, 0.0, 200))
                    .addBus(
                        "TURN",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 700)
                            .addPos(0.0, 0.0, empty ? 45.0 : 0.0, 200, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_UP)
                    );
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(-60.0, 0.0, 0.0, 0)
                            .addPos(-60.0, 0.0, 0.0, 300)
                            .addPos(0.0, 0.0, 0.0, 400, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("TWIST", new BusAnimationSequence().addPos(0.0, 0.0, -90.0, 0).addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_FULL))
                    .addBus(
                        "LEVER",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 700)
                            .addPos(-90.0, 0.0, 0.0, 200)
                            .addPos(0.0, 0.0, 0.0, 200)
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(-90.0, 0.0, 0.0, 200)
                            .addPos(0.0, 0.0, 0.0, 200)
                            .addPos(0.0, 0.0, 0.0, 200)
                            .addPos(-90.0, 0.0, 0.0, 200)
                            .addPos(0.0, 0.0, 0.0, 200)
                    )
                    .addBus(
                        "TURN",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 700)
                            .addPos(0.0, 0.0, 45.0, 200, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 45.0, 200, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 45.0, 600)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "YEET",
                        new BusAnimationSequence()
                            .addPos(0.0, 2.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus("ROLL", new BusAnimationSequence().addPos(0.0, 0.0, 360.0, 400));
            default:
                return null;
        }
    };

    // XFactory44.LAMBDA_NOPIP_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_NOPIP_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("ROTATE", new BusAnimationSequence().addPos(90.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                return new BusAnimation()
                    .addBus("RECOIL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 50).addPos(0.0, 0.0, -3.0, 50).addPos(0.0, 0.0, 0.0, 250))
                    .addBus("HAMMER", new BusAnimationSequence().addPos(0.0, 0.0, 1.0, 50).addPos(0.0, 0.0, 1.0, 400).addPos(0.0, 0.0, 0.0, 200))
                    .addBus("DRUM", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 450).addPos(0.0, 0.0, 1.0, 200));
            case CYCLE_DRY:
                return new BusAnimation()
                    .addBus("HAMMER", new BusAnimationSequence().addPos(0.0, 0.0, 1.0, 50).addPos(0.0, 0.0, 1.0, 400).addPos(0.0, 0.0, 0.0, 200))
                    .addBus("DRUM", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 450).addPos(0.0, 0.0, 1.0, 200));
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "RELAOD_TILT",
                        new BusAnimationSequence()
                            .addPos(-15.0, 0.0, 0.0, 100)
                            .addPos(65.0, 0.0, 0.0, 100)
                            .addPos(45.0, 0.0, 0.0, 50)
                            .addPos(0.0, 0.0, 0.0, 200)
                            .addPos(0.0, 0.0, 0.0, 1450)
                            .addPos(-80.0, 0.0, 0.0, 100)
                            .addPos(-80.0, 0.0, 0.0, 100)
                            .addPos(0.0, 0.0, 0.0, 200)
                    )
                    .addBus(
                        "RELOAD_CYLINDER",
                        new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 200).addPos(90.0, 0.0, 0.0, 100).addPos(90.0, 0.0, 0.0, 1700).addPos(0.0, 0.0, 0.0, 70)
                    )
                    .addBus(
                        "RELOAD_LIFT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 350)
                            .addPos(-45.0, 0.0, 0.0, 250)
                            .addPos(-45.0, 0.0, 0.0, 350)
                            .addPos(-15.0, 0.0, 0.0, 200)
                            .addPos(-15.0, 0.0, 0.0, 1050)
                            .addPos(0.0, 0.0, 0.0, 100)
                    )
                    .addBus("RELOAD_JOLT", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 600).addPos(2.0, 0.0, 0.0, 50).addPos(0.0, 0.0, 0.0, 100))
                    .addBus(
                        "RELOAD_BULLETS",
                        new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 650).addPos(10.0, 0.0, 0.0, 300).addPos(10.0, 0.0, 0.0, 200).addPos(0.0, 0.0, 0.0, 700)
                    )
                    .addBus("RELOAD_BULLETS_CON", new BusAnimationSequence().addPos(1.0, 0.0, 0.0, 0).addPos(1.0, 0.0, 0.0, 950).addPos(0.0, 0.0, 0.0, 1));
            case RELOAD_CYCLE:
            case RELOAD_END:
            default:
                return null;
            case JAMMED:
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "RELAOD_TILT",
                        new BusAnimationSequence()
                            .addPos(-15.0, 0.0, 0.0, 100)
                            .addPos(65.0, 0.0, 0.0, 100)
                            .addPos(45.0, 0.0, 0.0, 50)
                            .addPos(0.0, 0.0, 0.0, 200)
                            .addPos(0.0, 0.0, 0.0, 200)
                            .addPos(-80.0, 0.0, 0.0, 100)
                            .addPos(-80.0, 0.0, 0.0, 100)
                            .addPos(0.0, 0.0, 0.0, 200)
                    )
                    .addBus(
                        "RELOAD_CYLINDER",
                        new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 200).addPos(90.0, 0.0, 0.0, 100).addPos(90.0, 0.0, 0.0, 450).addPos(0.0, 0.0, 0.0, 70)
                    );
        }
    };

    // XFactory44.LAMBDA_LILMAC_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_LILMAC_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation().addBus("SPIN", new BusAnimationSequence().addPos(-360.0, 0.0, 0.0, 350));
            default:
                return NextGunAnimations.LAMBDA_NOPIP_ANIMS.apply(stack, type);
        }
    };

    // XFactory44.LAMBDA_HANGMAN_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_HANGMAN_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(60.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                return new BusAnimation()
                    .addBus("RECOIL", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 50).addPos(0.0, 0.0, -3.0, 50).addPos(0.0, 0.0, 0.0, 250));
            case CYCLE_DRY:
            case RELOAD_CYCLE:
            case RELOAD_END:
            default:
                return null;
            case RELOAD:
                return new BusAnimation()
                    .addBus("LID", new BusAnimationSequence().addPos(0.0, 0.0, -90.0, 250).addPos(0.0, 0.0, -90.0, 1500).addPos(0.0, 0.0, 0.0, 250))
                    .addBus(
                        "MAG",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(0.0, -10.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, -10.0, 0.0, 500)
                            .addPos(0.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("BULLETS", new BusAnimationSequence().addPos(1.0, 1.0, 1.0, 0).addPos(0.0, 0.0, 0.0, 500))
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(-15.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-15.0, 0.0, 0.0, 850)
                            .addPos(-25.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "ROLL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 25.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 25.0, 1000)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "LID",
                        new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 500).addPos(0.0, 0.0, -90.0, 250).addPos(0.0, 0.0, -90.0, 300).addPos(0.0, 0.0, 0.0, 250)
                    )
                    .addBus(
                        "MAG",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(0.0, -3.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 1000)
                            .addPos(-10.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "ROLL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 25.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 25.0, 300)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "TURN",
                        new BusAnimationSequence()
                            .addPos(0.0, 170.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 170.0, 0.0, 550)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "ROLL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 110.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 110.0, 550)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SMACK",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 1.0, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, -3.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_FULL)
                    );
        }
    };

    // XFactory50.LAMBDA_AMAT_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_AMAT_ANIMS = (stack, type) -> {
        double turn = -60.0;
        double pullAmount = -2.5;
        double side = 4.0;
        double down = -2.0;
        double detach = 0.5;
        double apex = 7.0;
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(45.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL))
                    .addBus("BIPOD", new BusAnimationSequence().hold(500).addPos(80.0, 0.0, 0.0, 350).addPos(80.0, 25.0, 0.0, 150));
            case CYCLE:
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, -0.5, 50, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("BOLT_TURN", new BusAnimationSequence().hold(250).addPos(0.0, 0.0, turn, 150).hold(700).addPos(0.0, 0.0, 0.0, 150))
                    .addBus(
                        "BOLT_PULL",
                        new BusAnimationSequence()
                            .hold(350)
                            .addPos(0.0, 0.0, pullAmount, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .hold(250)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.LINEAR)
                    )
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .hold(600)
                            .addPos(-3.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                            .hold(300)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case CYCLE_DRY:
                return new BusAnimation()
                    .addBus("BOLT_TURN", new BusAnimationSequence().hold(250).addPos(0.0, 0.0, turn, 150).hold(700).addPos(0.0, 0.0, 0.0, 150))
                    .addBus(
                        "BOLT_PULL",
                        new BusAnimationSequence()
                            .hold(350)
                            .addPos(0.0, 0.0, pullAmount, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .hold(250)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.LINEAR)
                    )
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .hold(600)
                            .addPos(-3.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                            .hold(300)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "MAG",
                        new BusAnimationSequence()
                            .addPos(0.0, -10.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 650, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .hold(1000)
                            .addPos(-2.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(450)
                            .addPos(-3.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                            .hold(300)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("BOLT_TURN", new BusAnimationSequence().hold(1500).addPos(0.0, 0.0, turn, 150).hold(700).addPos(0.0, 0.0, 0.0, 150))
                    .addBus(
                        "BOLT_PULL",
                        new BusAnimationSequence()
                            .hold(1600)
                            .addPos(0.0, 0.0, pullAmount, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .hold(250)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.LINEAR)
                    );
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .hold(250)
                            .addPos(-15.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .holdUntil(1650)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("BOLT_TURN", new BusAnimationSequence().hold(250).addPos(0.0, 0.0, turn, 150).holdUntil(1250).addPos(0.0, 0.0, 0.0, 150))
                    .addBus(
                        "BOLT_PULL",
                        new BusAnimationSequence()
                            .hold(350)
                            .addPos(0.0, 0.0, pullAmount, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.LINEAR)
                            .addPos(0.0, 0.0, pullAmount, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.LINEAR)
                    );
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "SCOPE_THROW",
                        new BusAnimationSequence()
                            .addPos(0.0, detach, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(side, down, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(side, down - 0.5, 0.0, 100)
                            .addPos(side, apex, 0.0, 350, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(side, down - 0.5, 0.0, 350, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(side, down, 0.0, 100)
                            .hold(250)
                            .addPos(0.0, detach, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("SCOPE_SPIN", new BusAnimationSequence().hold(700).addPos(-360.0, 0.0, 0.0, 700));
            default:
                return null;
        }
    };

    // XFactory50.LAMBDA_M2_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_M2_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(80.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL));
            case CYCLE:
                return new BusAnimation().addBus("RECOIL", new BusAnimationSequence().addPos(0.0, 0.0, -0.25, 25).addPos(0.0, 0.0, 0.0, 75));
            default:
                return null;
        }
    };

    // XFactory556mm.LAMBDA_G3_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_G3_ANIMS = (stack, type) -> {
        boolean empty = ((ItemGunBaseNT)stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0]
                .getMagazine(stack)
                .getAmount(stack, ClientPlayerAccess.player().getInventory())
            <= 0;
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(45.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL));
            case CYCLE:
                return new BusAnimation()
                    .addBus("BOLT", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 20).addPos(0.0, 0.0, -4.5, 40).addPos(0.0, 0.0, 0.0, 40))
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(
                                0.0,
                                0.0,
                                !ItemGunBaseNT.getIsAiming(stack) && XWeaponModManager.hasUpgrade(stack, 0, 205) ? -0.75 : -0.25,
                                25,
                                BusAnimationKeyframe.IType.SIN_DOWN
                            )
                            .addPos(0.0, 0.0, 0.0, 75, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case CYCLE_DRY:
                return new BusAnimation()
                    .addBus(
                        "BOLT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(0.0, 0.0, -0.3125, 100)
                            .hold(25)
                            .addPos(0.0, 0.0, -2.75, 130)
                            .hold(50)
                            .addPos(0.0, 0.0, -2.4375, 50)
                            .addPos(0.0, 0.0, 0.0, 85)
                    )
                    .addBus(
                        "PLUG", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 250).hold(125).addPos(0.0, 0.0, -2.4375, 130).hold(100).addPos(0.0, 0.0, 0.0, 85)
                    )
                    .addBus("HANDLE", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 250).addPos(0.0, 90.0, 0.0, 100).hold(25).hold(180).addPos(0.0, 0.0, 0.0, 50))
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 400)
                            .addPos(-1.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "MAG",
                        new BusAnimationSequence()
                            .addPos(0.0, -8.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, -8.0, 0.0, 1050)
                            .addPos(0.0, 0.0, 0.0, 250)
                    )
                    .addBus(
                        "BOLT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 200)
                            .addPos(0.0, 0.0, -0.3125, 100)
                            .hold(10)
                            .addPos(0.0, 0.0, -3.25, 200)
                            .holdUntil(1875)
                            .addPos(0.0, 0.0, -2.9375, 50)
                            .addPos(0.0, 0.0, 0.0, 100)
                    )
                    .addBus("PLUG", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 310).addPos(0.0, 0.0, -2.9375, 200).holdUntil(1925).addPos(0.0, 0.0, 0.0, 100))
                    .addBus(
                        "HANDLE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 200)
                            .addPos(0.0, 90.0, 0.0, 100)
                            .hold(210)
                            .addPos(0.0, 90.0, 45.0, 75)
                            .holdUntil(1775)
                            .addPos(0.0, 90.0, 0.0, 100)
                            .addPos(0.0, 0.0, 0.0, 50)
                    )
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 750)
                            .addPos(-25.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .holdUntil(1550)
                            .addPos(-26.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(-25.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .holdUntil(2000)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("BULLET", new BusAnimationSequence().addPos(empty ? 1.0 : 0.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 1000));
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "MAG",
                        new BusAnimationSequence()
                            .addPos(0.0, -1.0, 0.0, 150)
                            .addPos(2.0, -1.0, 0.0, 150)
                            .addPos(2.0, 8.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(2.0, -2.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(2.0, -1.0, 0.0, 50)
                            .addPos(2.0, -1.0, 0.0, 100)
                            .addPos(0.0, -1.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus("SPEEN", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 300).addPos(0.0, 360.0, 360.0, 700))
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 1450)
                            .addPos(-2.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("BULLET", new BusAnimationSequence().addPos(empty ? 1.0 : 0.0, 0.0, 0.0, 0));
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(-25.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-25.0, 0.0, 0.0, 1250)
                            .addPos(0.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "BOLT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 1000)
                            .addPos(0.0, 0.0, -3.25, 150)
                            .addPos(0.0, 0.0, 0.0, 100)
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(0.0, 0.0, -3.25, 150)
                            .addPos(0.0, 0.0, 0.0, 100)
                    )
                    .addBus(
                        "PLUG",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 1000)
                            .addPos(0.0, 0.0, -3.25, 150)
                            .addPos(0.0, 0.0, 0.0, 100)
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(0.0, 0.0, -3.25, 150)
                            .addPos(0.0, 0.0, 0.0, 100)
                    );
            default:
                return null;
        }
    };

    // XFactory556mm.LAMBDA_STG77_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_STG77_ANIMS = (stack, type) -> {
        if (GunVisualConfig.legacyAnimations) {
            switch (type) {
                case EQUIP:
                    return new BusAnimation()
                        .addBus("EQUIP", new BusAnimationSequence().addPos(45.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL));
                case CYCLE:
                    return new BusAnimation()
                        .addBus(
                            "RECOIL",
                            new BusAnimationSequence()
                                .addPos(0.0, 0.0, ItemGunBaseNT.getIsAiming(stack) ? -0.125 : -0.375, 25, BusAnimationKeyframe.IType.SIN_DOWN)
                                .addPos(0.0, 0.0, 0.0, 75, BusAnimationKeyframe.IType.SIN_FULL)
                        )
                        .addBus("SAFETY", new BusAnimationSequence().addPos(0.25, 0.0, 0.0, 0).addPos(0.25, 0.0, 0.0, 2000).addPos(0.0, 0.0, 0.0, 50));
                case CYCLE_DRY:
                    return new BusAnimation()
                        .addBus(
                            "BOLT",
                            new BusAnimationSequence()
                                .addPos(0.0, 0.0, 0.0, 250)
                                .addPos(0.0, 0.0, -2.0, 150)
                                .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                        )
                        .addBus("SAFETY", new BusAnimationSequence().addPos(0.25, 0.0, 0.0, 0).addPos(0.25, 0.0, 0.0, 2000).addPos(0.0, 0.0, 0.0, 50));
                case RELOAD:
                    return new BusAnimation()
                        .addBus(
                            "BOLT",
                            new BusAnimationSequence()
                                .addPos(0.0, 0.0, -2.0, 150)
                                .addPos(0.0, 0.0, -2.0, 1600)
                                .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                        )
                        .addBus(
                            "HANDLE",
                            new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 150).addPos(0.0, 0.0, 20.0, 50).addPos(0.0, 0.0, 20.0, 1500).addPos(0.0, 0.0, 0.0, 50)
                        )
                        .addBus(
                            "LIFT",
                            new BusAnimationSequence()
                                .addPos(0.0, 0.0, 0.0, 200)
                                .addPos(-2.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                                .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                        );
                case INSPECT:
                    return new BusAnimation()
                        .addBus(
                            "BOLT",
                            new BusAnimationSequence()
                                .addPos(0.0, 0.0, -2.0, 150)
                                .addPos(0.0, 0.0, -2.0, 6100)
                                .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                        )
                        .addBus(
                            "HANDLE",
                            new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 150).addPos(0.0, 0.0, 20.0, 50).addPos(0.0, 0.0, 20.0, 6000).addPos(0.0, 0.0, 0.0, 50)
                        )
                        .addBus(
                            "INSPECT_LEVER",
                            new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 500).addPos(0.0, 0.0, -10.0, 100).addPos(0.0, 0.0, -10.0, 100).addPos(0.0, 0.0, 0.0, 100)
                        )
                        .addBus(
                            "INSPECT_BARREL",
                            new BusAnimationSequence()
                                .addPos(0.0, 0.0, 0.0, 600)
                                .addPos(0.0, 0.0, 20.0, 150)
                                .addPos(0.0, 0.0, 0.0, 400)
                                .addPos(0.0, 0.0, 0.0, 500)
                                .addPos(15.0, 0.0, 0.0, 500)
                                .addPos(15.0, 0.0, 0.0, 2000)
                                .addPos(0.0, 0.0, 0.0, 500)
                                .addPos(0.0, 0.0, 0.0, 500)
                                .addPos(0.0, 0.0, 20.0, 200)
                                .addPos(0.0, 0.0, 20.0, 400)
                                .addPos(0.0, 0.0, 0.0, 150)
                        )
                        .addBus(
                            "INSPECT_MOVE",
                            new BusAnimationSequence()
                                .addPos(0.0, 0.0, 0.0, 750)
                                .addPos(0.0, 0.0, 6.0, 1000)
                                .addPos(2.0, 0.0, 3.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(2.0, 0.75, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(2.0, 0.75, 0.0, 1000)
                                .addPos(2.0, 0.0, 3.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(0.0, 0.0, 6.0, 500)
                                .addPos(0.0, 0.0, 0.0, 1000)
                        )
                        .addBus(
                            "INSPECT_GUN",
                            new BusAnimationSequence()
                                .addPos(0.0, 0.0, 0.0, 1750)
                                .addPos(15.0, 0.0, -70.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                                .addPos(15.0, 0.0, -70.0, 1500)
                                .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                        );
            }
        } else {
            switch (type) {
                case EQUIP:
                    return new BusAnimation()
                        .addBus("EQUIP", new BusAnimationSequence().addPos(45.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL));
                case CYCLE:
                    return new BusAnimation()
                        .addBus(
                            "RECOIL",
                            new BusAnimationSequence()
                                .addPos(0.0, 0.0, ItemGunBaseNT.getIsAiming(stack) ? -0.125 : -0.375, 25, BusAnimationKeyframe.IType.SIN_DOWN)
                                .addPos(0.0, 0.0, 0.0, 75, BusAnimationKeyframe.IType.SIN_FULL)
                        )
                        .addBus("SAFETY", new BusAnimationSequence().addPos(0.25, 0.0, 0.0, 0).addPos(0.25, 0.0, 0.0, 2000).addPos(0.0, 0.0, 0.0, 50));
                case CYCLE_DRY:
                    return ResourceManager.stg77_anim.get("FireDry");
                case RELOAD:
                    return ResourceManager.stg77_anim.get("Reload");
                case INSPECT:
                    return ResourceManager.stg77_anim.get("Inspect");
            }
        }

        return null;
    };

    // XFactory75Bolt.LAMBDA_BOLTER_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_BOLTER_ANIMS = (stack, type) -> {
        switch (type) {
            case CYCLE:
                return new BusAnimation().addBus("RECOIL", new BusAnimationSequence().addPos(1.0, 0.0, 0.0, 25).addPos(0.0, 0.0, 0.0, 75));
            case RELOAD:
                return new BusAnimation()
                    .addBus("TILT", new BusAnimationSequence().addPos(1.0, 0.0, 0.0, 250).addPos(1.0, 0.0, 0.0, 1500).addPos(0.0, 0.0, 0.0, 250))
                    .addBus("MAG", new BusAnimationSequence().addPos(0.0, 0.0, 1.0, 500).addPos(1.0, 0.0, 1.0, 500).addPos(0.0, 0.0, 0.0, 500));
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "TILT",
                        new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 500).addPos(1.0, 0.0, 0.0, 250).addPos(1.0, 0.0, 0.0, 700).addPos(0.0, 0.0, 0.0, 250)
                    )
                    .addBus("MAG", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 750).addPos(0.6, 0.0, 0.0, 250).addPos(0.0, 0.0, 0.0, 250));
            default:
                return null;
        }
    };

    // XFactory762mm.LAMBDA_CARBINE_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_CARBINE_ANIMS = (stack, type) -> {
        int ammo = ((ItemGunBaseNT)stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0]
            .getMagazine(stack)
            .getAmount(stack, ClientPlayerAccess.player().getInventory());
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(45.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL));
            case CYCLE:
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, ItemGunBaseNT.getIsAiming(stack) ? -0.25 : -0.5, 50, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SLIDE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, -1.0, 50, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        ammo <= 1 ? "NULL" : "REL",
                        new BusAnimationSequence().addPos(0.0, 0.0, 0.25, 50).addPos(0.0, 0.125, 1.25, 100, BusAnimationKeyframe.IType.SIN_UP)
                    );
            case CYCLE_DRY:
                return new BusAnimation()
                    .addBus(
                        "SLIDE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, -1.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, -1.0, 50)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                    );
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "MAG",
                        new BusAnimationSequence()
                            .addPos(0.0, -4.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, -4.0, 0.0, 750)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN)
                    )
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(-25.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-25.0, 0.0, 0.0, 1000)
                    )
                    .addBus("BULLET", new BusAnimationSequence().addPos(ammo == 0 ? 1.0 : 0.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 1000));
            case RELOAD_END:
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(-25.0, 0.0, 0.0, 0)
                            .addPos(-25.0, 0.0, 0.0, 750)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SLIDE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(0.0, 0.0, -1.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, -1.0, 50)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "REL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(0.0, 0.0, 0.25, 150)
                            .addPos(0.0, 0.125, 1.25, 100, BusAnimationKeyframe.IType.SIN_UP)
                    );
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(-25.0, 0.0, 0.0, 0)
                            .addPos(-25.0, 0.0, 0.0, 750)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(-25.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-25.0, 0.0, 0.0, 750)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SLIDE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(0.0, 0.0, -1.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, -1.0, 50)
                            .addPos(0.0, 0.0, -0.25, 100, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, -0.25, 1250)
                            .addPos(0.0, 0.0, -1.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, -1.0, 50)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "REL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(0.0, 0.0, 0.25, 150)
                            .addPos(0.0, 0.125, 1.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.125, 1.0, 1250)
                            .addPos(0.0, 0.125, 0.25, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.125, 1.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                    );
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(-25.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-25.0, 0.0, 0.0, 1500)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SLIDE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, -0.75, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, -0.75, 1000)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        ammo == 0 ? "NULL" : "REL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.125, 1.25, 0)
                            .addPos(0.0, 0.125, 1.25, 500)
                            .addPos(0.0, 0.125, 0.5, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.125, 0.5, 1000)
                            .addPos(0.0, 0.125, 1.25, 100, BusAnimationKeyframe.IType.SIN_UP)
                    );
            default:
                return null;
        }
    };

    // XFactory762mm.LAMBDA_MINIGUN_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_MINIGUN_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(45.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 1000, BusAnimationKeyframe.IType.SIN_FULL));
            case CYCLE:
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, ItemGunBaseNT.getIsAiming(stack) ? -0.25 : -0.5, 0)
                            .addPos(0.0, 0.0, ItemGunBaseNT.getIsAiming(stack) ? -0.25 : -0.5, 100)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("ROTATE", new BusAnimationSequence().addPos(0.0, 0.0, 60.0, 50).addPos(0.0, 0.0, 720.0, 1000, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE_DRY:
                return new BusAnimation()
                    .addBus("ROTATE", new BusAnimationSequence().addPos(0.0, 0.0, 60.0, 50).addPos(0.0, 0.0, 720.0, 1000, BusAnimationKeyframe.IType.SIN_DOWN));
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(-15.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("ROTATE", new BusAnimationSequence().addPos(0.0, 0.0, 60.0, 50).addPos(0.0, 0.0, 720.0, 1000, BusAnimationKeyframe.IType.SIN_DOWN));
            case RELOAD_END:
            case JAMMED:
            default:
                return null;
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(3.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("ROTATE", new BusAnimationSequence().addPos(0.0, 0.0, -720.0, 1000, BusAnimationKeyframe.IType.SIN_DOWN));
        }
    };

    // XFactory762mm.LAMBDA_MAS36_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_MAS36_ANIMS = (stack, type) -> {
        int mag = ((ItemGunBaseNT)stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0]
            .getMagazine(stack)
            .getAmount(stack, ClientPlayerAccess.player().getInventory());
        double turn = -90.0;
        double pullAmount = ItemGunBaseNT.getIsAiming(stack) ? -1.0 : -1.5;
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("STOCK", new BusAnimationSequence().setPos(-158.0, 0.0, 0.0).hold(500).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL))
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .setPos(45.0, 0.0, 0.0)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(500)
                            .addPos(1.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case CYCLE:
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, -0.5, 50, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("BOLT_TURN", new BusAnimationSequence().hold(250).addPos(0.0, 0.0, turn, 150).hold(700).addPos(0.0, 0.0, 0.0, 150))
                    .addBus(
                        "BOLT_PULL",
                        new BusAnimationSequence()
                            .hold(350)
                            .addPos(0.0, 0.0, pullAmount, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .hold(250)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.LINEAR)
                    )
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .hold(600)
                            .addPos(-3.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                            .hold(300)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "BULLET",
                        mag <= 1
                            ? new BusAnimationSequence().setPos(-100.0, 0.0, 0.0)
                            : new BusAnimationSequence().hold(850).addPos(0.0, 0.1875, 1.5, 200, BusAnimationKeyframe.IType.LINEAR)
                    );
            case CYCLE_DRY:
                return new BusAnimation()
                    .addBus("BOLT_TURN", new BusAnimationSequence().hold(250).addPos(0.0, 0.0, turn, 150).hold(700).addPos(0.0, 0.0, 0.0, 150))
                    .addBus(
                        "BOLT_PULL",
                        new BusAnimationSequence()
                            .hold(350)
                            .addPos(0.0, 0.0, pullAmount, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .hold(250)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.LINEAR)
                    )
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .hold(600)
                            .addPos(-3.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                            .hold(300)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("BULLET", new BusAnimationSequence().setPos(-100.0, 0.0, 0.0));
            case RELOAD:
                return new BusAnimation()
                    .addBus("BOLT_TURN", new BusAnimationSequence().addPos(0.0, 0.0, turn, 150).holdUntil(2000).addPos(0.0, 0.0, 0.0, 150))
                    .addBus(
                        "BOLT_PULL",
                        new BusAnimationSequence()
                            .hold(100)
                            .addPos(0.0, 0.0, -1.5, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .holdUntil(1800)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.LINEAR)
                    )
                    .addBus(
                        "BULLET",
                        new BusAnimationSequence()
                            .setPos(-100.0, 0.0, 0.0)
                            .holdUntil(1200)
                            .setPos(0.0, 0.0, 0.0)
                            .hold(600)
                            .addPos(0.0, 0.1875, 1.5, 200, BusAnimationKeyframe.IType.LINEAR)
                    )
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .hold(200)
                            .addPos(30.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .holdUntil(1200)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("SHOW_CLIP", new BusAnimationSequence().setPos(1.0, 1.0, 1.0))
                    .addBus(
                        "CLIP",
                        new BusAnimationSequence()
                            .setPos(2.0, -3.0, 0.0)
                            .hold(250)
                            .addPos(0.5, 1.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(400)
                            .addPos(-0.5, 0.5, 0.0, 150)
                            .addPos(-3.0, -3.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "BULLETS",
                        new BusAnimationSequence()
                            .setPos(2.0, -3.0, 0.0)
                            .hold(250)
                            .addPos(0.5, 1.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(150)
                            .addPos(0.0, -1.5, 0.0, 250, BusAnimationKeyframe.IType.SIN_DOWN)
                    );
            case RELOAD_END:
            default:
                return null;
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .hold(250)
                            .addPos(-15.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .holdUntil(1650)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("BOLT_TURN", new BusAnimationSequence().hold(250).addPos(0.0, 0.0, turn, 150).holdUntil(1250).addPos(0.0, 0.0, 0.0, 150))
                    .addBus(
                        "BOLT_PULL",
                        new BusAnimationSequence()
                            .hold(350)
                            .addPos(0.0, 0.0, pullAmount, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.LINEAR)
                            .addPos(0.0, 0.0, pullAmount, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.LINEAR)
                    );
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .hold(350)
                            .addPos(-3.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                            .holdUntil(1050)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("BOLT_TURN", new BusAnimationSequence().addPos(0.0, 0.0, turn, 150).holdUntil(1050).addPos(0.0, 0.0, 0.0, 150))
                    .addBus(
                        "BOLT_PULL",
                        new BusAnimationSequence()
                            .hold(100)
                            .addPos(0.0, 0.0, -1.0, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .hold(500)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.LINEAR)
                    )
                    .addBus(
                        "BULLET",
                        mag == 0
                            ? new BusAnimationSequence().setPos(-100.0, 0.0, 0.0)
                            : new BusAnimationSequence()
                                .setPos(0.0, 0.1875, 1.5)
                                .hold(100)
                                .addPos(0.0, 0.125, 0.5, 250, BusAnimationKeyframe.IType.SIN_UP)
                                .hold(500)
                                .addPos(0.0, 0.1875, 1.5, 200, BusAnimationKeyframe.IType.LINEAR)
                    );
        }
    };

    // XFactory9mm.LAMBDA_LAG_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_LAG_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(-90.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                return ResourceManager.lag_anim.get("Firing");
            case CYCLE_DRY:
                return ResourceManager.lag_anim.get("Dryfire");
            case RELOAD:
                return ResourceManager.lag_anim.get("Reload");
            case JAMMED:
                return ResourceManager.lag_anim.get("Jam");
            case INSPECT:
                return ResourceManager.lag_anim
                    .get("Inspect")
                    .addBus("ADD_TRANS", new BusAnimationSequence().addPos(-4.0, 0.0, -3.0, 500).addPos(-4.0, 0.0, -3.0, 2000).addPos(0.0, 0.0, 0.0, 500))
                    .addBus("ADD_ROT", new BusAnimationSequence().addPos(0.0, -2.0, 5.0, 500).addPos(0.0, -2.0, 5.0, 2000).addPos(0.0, 0.0, 0.0, 500));
            default:
                return null;
        }
    };

    // XFactory9mm.LAMBDA_GREASEGUN_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_GREASEGUN_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(80.0, 0.0, 0.0, 0)
                            .addPos(80.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "STOCK",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, -4.0, 0)
                            .addPos(0.0, 0.0, -4.0, 200)
                            .addPos(0.0, 0.0, 0.0, 300, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case CYCLE:
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, ItemGunBaseNT.getIsAiming(stack) ? -0.25 : -0.5, 50, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "FLAP",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 15.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, -5.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case CYCLE_DRY:
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(-25.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-25.0, 0.0, 0.0, 750)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "TURN",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, -45.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, -45.0, 750)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "HANDLE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(-90.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case RELOAD:
                boolean empty = ((ItemGunBaseNT)stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0]
                        .getMagazine(stack)
                        .getAmount(stack, ClientPlayerAccess.player().getInventory())
                    <= 0;
                return new BusAnimation()
                    .addBus(
                        "MAG",
                        new BusAnimationSequence()
                            .addPos(0.0, -8.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, -8.0, 0.0, 750)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN)
                    )
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(-25.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-25.0, 0.0, 0.0, 1750)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "TURN",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 1750)
                            .addPos(0.0, 0.0, -45.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, -45.0, 500)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "HANDLE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 2000)
                            .addPos(-90.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("BULLET", new BusAnimationSequence().addPos(empty ? 1.0 : 0.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 1000));
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(-25.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-25.0, 0.0, 0.0, 1500)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "TURN",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, -45.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, -45.0, 1500)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "HANDLE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(-90.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(-90.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "TURN",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, -45.0, 150)
                            .addPos(0.0, 0.0, 45.0, 150)
                            .addPos(0.0, 0.0, 45.0, 50)
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 45.0, 150)
                            .addPos(0.0, 0.0, -45.0, 150)
                            .addPos(0.0, 0.0, 0.0, 150)
                    )
                    .addBus(
                        "FLAP",
                        new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 300).addPos(0.0, 0.0, 180.0, 150).addPos(0.0, 0.0, 180.0, 850).addPos(0.0, 0.0, 0.0, 150)
                    );
            default:
                return null;
        }
    };

    // XFactory9mm.LAMBDA_UZI_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_UZI_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(80.0, 0.0, 0.0, 0)
                            .addPos(80.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("STOCKBACK", new BusAnimationSequence().addPos(-200.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL))
                    .addBus("STOCKFRONT", new BusAnimationSequence().addPos(180.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL));
            case CYCLE:
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, ItemGunBaseNT.getIsAiming(stack) ? -0.5 : -0.75, 25, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 75, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case CYCLE_DRY:
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(-25.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-25.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SLIDE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, -2.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                    );
            case RELOAD:
                boolean empty = ((ItemGunBaseNT)stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0]
                        .getMagazine(stack)
                        .getAmount(stack, ClientPlayerAccess.player().getInventory())
                    <= 0;
                return new BusAnimation()
                    .addBus(
                        "MAG",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(0.0, -10.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, -10.0, 0.0, 750)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN)
                    )
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(-25.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-25.0, 0.0, 0.0, 2000)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SLIDE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 2000)
                            .addPos(0.0, 0.0, -2.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "BULLET", new BusAnimationSequence().addPos(empty ? 0.0 : 1.0, 0.0, 0.0, 0).addPos(empty ? 0.0 : 1.0, 0.0, 0.0, 500).addPos(1.0, 0.0, 0.0, 0)
                    );
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(-25.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-25.0, 0.0, 0.0, 1250)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SLIDE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 1000)
                            .addPos(0.0, 0.0, -2.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, -2.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                    );
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "YEET",
                        new BusAnimationSequence()
                            .addPos(0.0, -1.0, 0.0, 100)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 12.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, -1.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("SPEEN", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 250).addPos(-360.0, 0.0, 0.0, 600));
            default:
                return null;
        }
    };

    // XFactoryAccelerator.LAMBDA_TAU_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_TAU_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(45.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL));
            case CYCLE:
                return new BusAnimation()
                    .addBus("RECOIL", new BusAnimationSequence().addPos(0.0, 0.0, -0.5, 50).addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL))
                    .addBus(
                        "ROTATE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, -5.0, 50, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 5.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                    );
            case ALT_CYCLE:
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, -3.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "ROTATE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, -5.0, 50, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 5.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                    );
            case CYCLE_DRY:
                return new BusAnimation();
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(2.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("ROTATE", new BusAnimationSequence().addPos(0.0, 0.0, -1080.0, 1500, BusAnimationKeyframe.IType.SIN_DOWN));
            case SPINUP:
                return new BusAnimation()
                    .addBus(
                        "ROTATE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 2160.0, 3000, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 0)
                            .addPos(0.0, 0.0, 14400.0, 10000)
                    );
            default:
                return null;
        }
    };

    // XFactoryAccelerator.LAMBDA_COILGUN_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_COILGUN_ANIMS = (stack, type) -> {
        if (type == GunAnimation.EQUIP) {
            return new BusAnimation().addBus("RELOAD", new BusAnimationSequence().addPos(1.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 250));
        } else if (type == GunAnimation.CYCLE) {
            return new BusAnimation()
                .addBus("RECOIL", new BusAnimationSequence().addPos(ItemGunBaseNT.getIsAiming(stack) ? 0.5 : 1.0, 0.0, 0.0, 100).addPos(0.0, 0.0, 0.0, 200));
        } else {
            return type == GunAnimation.RELOAD
                ? new BusAnimation().addBus("RELOAD", new BusAnimationSequence().addPos(1.0, 0.0, 0.0, 250).addPos(1.0, 0.0, 0.0, 500).addPos(0.0, 0.0, 0.0, 250))
                : null;
        }
    };

    // XFactoryAccelerator.LAMBDA_NI4NI_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_NI4NI_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation().addBus("EQUIP", new BusAnimationSequence().addPos(-720.0, 0.0, 0.0, 500));
            case CYCLE:
                boolean aiming = ItemGunBaseNT.getIsAiming(stack);
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(aiming ? -5.0 : -30.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("DRUM", new BusAnimationSequence().hold(50).addPos(0.0, 0.0, 120.0, 300, BusAnimationKeyframe.IType.SIN_FULL));
            case ALT_CYCLE:
            case CYCLE_DRY:
            default:
                return null;
            case INSPECT:
                return new BusAnimation().addBus("EQUIP", new BusAnimationSequence().addPos(-1080.0, 0.0, 0.0, 750).hold(100).addPos(0.0, 0.0, 0.0, 750));
        }
    };

    // XFactoryBlackPowder.LAMBDA_PEPPERBOX_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_PEPPERBOX_ANIMS = (stack, type) -> {
        switch (type) {
            case CYCLE:
                return new BusAnimation()
                    .addBus("ROTATE", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 1025).addPos(60.0, 0.0, 0.0, 250))
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 50)
                            .addPos(45.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(45.0, 0.0, 0.0, 50)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("HAMMER", new BusAnimationSequence().addPos(80.0, 0.0, 0.0, 25).addPos(80.0, 0.0, 0.0, 1000).addPos(0.0, 0.0, 0.0, 250))
                    .addBus("TRIGGER", new BusAnimationSequence().addPos(1.0, 0.0, 0.0, 25).addPos(1.0, 0.0, 0.0, 250).addPos(0.0, 0.0, 0.0, 100));
            case CYCLE_DRY:
                return new BusAnimation()
                    .addBus("ROTATE", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 525).addPos(60.0, 0.0, 0.0, 250))
                    .addBus("HAMMER", new BusAnimationSequence().addPos(80.0, 0.0, 0.0, 25).addPos(80.0, 0.0, 0.0, 500).addPos(0.0, 0.0, 0.0, 250))
                    .addBus("TRIGGER", new BusAnimationSequence().addPos(1.0, 0.0, 0.0, 25).addPos(1.0, 0.0, 0.0, 250).addPos(0.0, 0.0, 0.0, 100));
            case EQUIP:
                return new BusAnimation()
                    .addBus("RECOIL", new BusAnimationSequence().addPos(-45.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_DOWN));
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(90.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(90.0, 0.0, 0.0, 1600)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-5.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_DOWN)
                    )
                    .addBus(
                        "TRANSLATE",
                        new BusAnimationSequence()
                            .addPos(0.0, -12.0, 5.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, -12.0, 5.0, 700)
                            .addPos(0.0, -13.0, 5.0, 200)
                            .addPos(0.0, -12.0, 5.0, 200)
                            .addPos(0.0, -12.0, 5.0, 500)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "LOADER",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 5.0, -5.0, 0)
                            .addPos(0.0, 0.0, -0.1, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, -1.0, 200)
                            .addPos(0.0, 0.0, -1.0, 200)
                            .addPos(0.0, 0.0, -0.1, 200)
                            .addPos(0.0, 5.0, -5.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 0)
                    )
                    .addBus("ROTATE", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 2600).addPos(-360.0, 0.0, 0.0, 750, BusAnimationKeyframe.IType.SIN_FULL))
                    .addBus("SHOT", new BusAnimationSequence().addPos(1.0, 0.0, 0.0, 1400).addPos(0.0, 0.0, 0.0, 0));
            case INSPECT:
                return new BusAnimation()
                    .addBus("ROTATE", new BusAnimationSequence().addPos(-360.0, 0.0, 0.0, 750, BusAnimationKeyframe.IType.SIN_FULL))
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(-5.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_DOWN)
                    );
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "ROTATE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 1300)
                            .addPos(60.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(60.0, 0.0, 0.0, 400)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "TRANSLATE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, -6.0, 0.0, 400, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, -6.0, 0.0, 2000)
                            .addPos(0.0, 0.0, 0.0, 400, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(45.0, 0.0, 0.0, 400, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(45.0, 0.0, 0.0, 2000)
                            .addPos(0.0, 0.0, 0.0, 400, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            default:
                return null;
        }
    };

    // XFactoryCatapult.LAMBDA_FATMAN_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_FATMAN_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(60.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 1000, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                RandomSource rand = ClientPlayerAccess.player().getRandom();
                return new BusAnimation()
                    .addBus(
                        "GAUGE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 135 + rand.nextInt(136), 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN)
                    )
                    .addBus("PISTON", new BusAnimationSequence().addPos(0.0, 0.0, 3.0, 100, BusAnimationKeyframe.IType.SIN_UP))
                    .addBus("NUKE", new BusAnimationSequence().addPos(0.0, 0.0, 3.0, 100, BusAnimationKeyframe.IType.SIN_UP).addPos(0.0, 0.0, 0.0, 0));
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "LID",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(0.0, 0.0, -45.0, 250, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, -45.0, 1200)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "HANDLE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, -2.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, -2.0, 1700)
                            .addPos(0.0, 0.0, 0.0, 750, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "NUKE",
                        new BusAnimationSequence()
                            .addPos(5.0, -4.0, 3.0, 0)
                            .addPos(5.0, -4.0, 3.0, 750)
                            .addPos(2.0, 0.5, 3.0, 500, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(1.0, 0.5, 3.0, 100)
                            .addPos(0.0, 0.0, 3.0, 100)
                            .addPos(0.0, 0.0, 3.0, 750)
                            .addPos(0.0, 0.0, 0.0, 750, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "PISTON",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 3.0, 0)
                            .addPos(0.0, 0.0, 3.0, 2200)
                            .addPos(0.0, 0.0, 0.0, 750, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(5.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 450)
                            .addPos(3.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(-10.0, 0.0, 0.0, 375, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 375, BusAnimationKeyframe.IType.SIN_UP)
                    );
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "HANDLE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 750)
                            .addPos(0.0, 0.0, -2.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, -2.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(-15.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-15.0, 0.0, 0.0, 1000)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "HANDLE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(0.0, 0.0, -2.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, -2.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(-15.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(-15.0, 0.0, 0.0, 1000)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            default:
                return null;
        }
    };

    // XFactoryDrill.LAMBDA_DRILL_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_DRILL_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().setPos(-1.0, 0.0, 0.0).addPos(0.0, 0.0, 0.0, 750, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                double deploy = HbmAnimations.getRelevantTransformation("DEPLOY")[0];
                double speed = HbmAnimations.getRelevantTransformation("SPEED")[0];
                double spin = HbmAnimations.getRelevantTransformation("SPIN")[0] % 360.0;
                return new BusAnimation()
                    .addBus(
                        "DEPLOY",
                        new BusAnimationSequence()
                            .setPos(deploy, 0.0, 0.0)
                            .addPos(1.0, 0.0, 0.0, (int)(500.0 * (1.0 - deploy)), BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(1000)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SPIN",
                        new BusAnimationSequence()
                            .setPos(spin, 0.0, 0.0)
                            .addPos(spin + 540.0, 0.0, 0.0, 1500)
                            .addPos(1080.0, 0.0, 0.0, 750 + (int)(1000.0 * (1.0 - spin / 360.0)), BusAnimationKeyframe.IType.SIN_DOWN)
                    )
                    .addBus(
                        "SPEED",
                        new BusAnimationSequence()
                            .setPos(speed, 0.0, 0.0)
                            .addPos(1.0, 0.0, 0.0, 500)
                            .hold(1000)
                            .addPos(0.0, 0.0, 0.0, 750 + (int)(1000.0 * (1.0 - spin / 360.0)), BusAnimationKeyframe.IType.SIN_DOWN)
                    );
            case CYCLE_DRY:
                return new BusAnimation()
                    .addBus(
                        "DEPLOY",
                        new BusAnimationSequence()
                            .addPos(0.25, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("SPIN", new BusAnimationSequence().addPos(360.0, 0.0, 0.0, 1500, BusAnimationKeyframe.IType.SIN_DOWN))
                    .addBus("SPEED", new BusAnimationSequence().addPos(0.75, 0.0, 0.0, 250).addPos(0.0, 0.0, 0.0, 1000, BusAnimationKeyframe.IType.SIN_DOWN));
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .addPos(-45.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(1000)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN)
                    );
            default:
                return null;
        }
    };

    // XFactoryEnergy.LAMBDA_TESLA_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_TESLA_ANIMS = (stack, type) -> {
        int amount = ((ItemGunBaseNT)stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0]
            .getMagazine(stack)
            .getAmount(stack, ClientPlayerAccess.player().getInventory());
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(60.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 1000, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, ItemGunBaseNT.getIsAiming(stack) ? -0.5 : -1.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("CYCLE", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 150).addPos(0.0, 0.0, 22.5, 350))
                    .addBus("COUNT", new BusAnimationSequence().addPos(amount, 0.0, 0.0, 0));
            case CYCLE_DRY:
                return new BusAnimation().addBus("CYCLE", new BusAnimationSequence().addPos(0.0, 0.0, 0.0, 150).addPos(0.0, 0.0, 22.5, 350));
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "YOMI",
                        new BusAnimationSequence()
                            .addPos(8.0, -4.0, 0.0, 0)
                            .addPos(4.0, -1.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(4.0, -1.0, 0.0, 1000)
                            .addPos(6.0, -6.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "SQUEEZE",
                        new BusAnimationSequence().addPos(1.0, 1.0, 1.0, 0).addPos(1.0, 1.0, 1.0, 750).addPos(1.0, 1.0, 0.5, 125).addPos(1.0, 1.0, 1.0, 125)
                    );
            default:
                return null;
        }
    };

    // XFactoryEnergy.LAMBDA_LASER_PISTOL
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_LASER_PISTOL = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(60.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, -0.5, 50, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case CYCLE_DRY:
            default:
                return null;
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "SWIRL",
                        new BusAnimationSequence()
                            .addPos(-720.0, 0.0, 0.0, 750, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(500)
                            .addPos(0.0, 0.0, 0.0, 750, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case RELOAD:
                return new BusAnimation()
                    .addBus("LATCH", new BusAnimationSequence().addPos(0.0, -20.0, 0.0, 100).hold(1900).addPos(0.0, 0.0, 0.0, 100))
                    .addBus(
                        "LIFT",
                        new BusAnimationSequence()
                            .hold(100)
                            .addPos(-45.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(500)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "JOLT",
                        new BusAnimationSequence()
                            .hold(350)
                            .addPos(0.0, 0.0, 0.5, 100, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, -1.5, 100, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                            .holdUntil(2100)
                            .addPos(-0.0625, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "BATTERY",
                        new BusAnimationSequence()
                            .hold(550)
                            .addPos(0.0, 0.0, 5.0, 250)
                            .hold(550)
                            .setPos(0.0, -2.0, -2.0)
                            .addPos(0.0, 0.0, -2.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_UP)
                    );
            case JAMMED:
                return new BusAnimation()
                    .addBus("LATCH", new BusAnimationSequence().hold(500).addPos(0.0, -20.0, 0.0, 100).hold(250).addPos(0.0, 0.0, 0.0, 100))
                    .addBus(
                        "JOLT",
                        new BusAnimationSequence()
                            .hold(950)
                            .addPos(-0.0625, 0.0, 0.0, 50, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .hold(1500)
                            .addPos(7.5, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    );
        }
    };

    // XFactoryEnergy.LAMBDA_LASRIFLE
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_LASRIFLE = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(60.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, -0.5, 50, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case CYCLE_DRY:
            default:
                return null;
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "LEVER",
                        new BusAnimationSequence()
                            .addPos(-90.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(-90.0, 0.0, 0.0, 600)
                            .addPos(0.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "MAG",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 350)
                            .addPos(0.0, -2.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, -0.25, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, -0.25, 0.0, 150)
                            .addPos(0.0, 0.0, 0.0, 350)
                    )
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 800)
                            .addPos(-2.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "LEVER",
                        new BusAnimationSequence()
                            .addPos(-90.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(-90.0, 0.0, 0.0, 1500)
                            .addPos(0.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "MAG",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 350)
                            .addPos(0.0, -5.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, -5.0, 0.0, 500)
                            .addPos(0.0, -0.25, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, -0.25, 0.0, 150)
                            .addPos(0.0, 0.0, 0.0, 350)
                    )
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 1700)
                            .addPos(-2.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "LEVER",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(-90.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(-90.0, 0.0, 0.0, 600)
                            .addPos(0.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "MAG",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 0.0, 350)
                            .addPos(0.0, -2.0, 0.0, 200, BusAnimationKeyframe.IType.SIN_UP)
                            .addPos(0.0, -0.25, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, -0.25, 0.0, 150)
                            .addPos(0.0, 0.0, 0.0, 350)
                    )
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 0.0, 800)
                            .addPos(-2.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    );
        }
    };

    // XFactoryFlamer.LAMBDA_FLAMER_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_FLAMER_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(-45.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN));
            case RELOAD:
                return ResourceManager.flamethrower_anim.get("Reload");
            case INSPECT:
            case JAMMED:
                return new BusAnimation()
                    .addBus(
                        "ROTATE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 45.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 45.0, 350)
                            .addPos(0.0, 0.0, -15.0, 150, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 100, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            default:
                return null;
        }
    };

    // XFactoryFlamer.LAMBDA_CHEMTHROWER_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_CHEMTHROWER_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(-45.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN));
            default:
                return null;
        }
    };

    // XFactoryFolly.LAMBDA_FOLLY_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_FOLLY_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(-60.0, 0.0, 0.0, 0)
                            .addPos(5.0, 0.0, 0.0, 1500, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case CYCLE:
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, -4.5, 50)
                            .addPos(0.0, 0.0, -4.5, 500)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "LOAD",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 50)
                            .addPos(-25.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 1000, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "LOAD",
                        new BusAnimationSequence()
                            .addPos(60.0, 0.0, 0.0, 1000, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(60.0, 0.0, 0.0, 6000)
                            .addPos(0.0, 0.0, 0.0, 1000, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SCREW",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 1000)
                            .addPos(0.0, 0.0, -135.0, 1000, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, -135.0, 4000)
                            .addPos(0.0, 0.0, 0.0, 1000, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "BREECH",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 1000)
                            .addPos(0.0, 0.0, -0.5, 1000, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, -4.0, -0.5, 1000, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, -4.0, -0.5, 2000)
                            .addPos(0.0, 0.0, -0.5, 1000, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 1000, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "SHELL",
                        new BusAnimationSequence()
                            .addPos(0.0, -4.0, -4.5, 0)
                            .addPos(0.0, -4.0, -4.5, 3000)
                            .addPos(0.0, 0.0, -4.5, 1000, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_UP)
                    );
            default:
                return null;
        }
    };

    // XFactoryRocket.LAMBDA_PANZERSCHRECK_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_PANZERSCHRECK_ANIMS = (stack, type) -> {
        boolean empty = ((ItemGunBaseNT)stack.getItem()).getConfig(stack, 0).getReceivers(stack)[0]
                .getMagazine(stack)
                .getAmount(stack, ClientPlayerAccess.player().getInventory())
            <= 0;
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(60.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN));
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "RELOAD",
                        new BusAnimationSequence()
                            .addPos(90.0, 0.0, 0.0, 750, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(90.0, 0.0, 0.0, 1000)
                            .addPos(0.0, 0.0, 0.0, 750, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "ROCKET",
                        new BusAnimationSequence()
                            .addPos(0.0, -3.0, -6.0, 0)
                            .addPos(0.0, -3.0, -6.0, 750)
                            .addPos(0.0, 0.0, -6.5, 500, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_UP)
                    );
            case JAMMED:
                empty = false;
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "RELOAD",
                        new BusAnimationSequence()
                            .addPos(90.0, 0.0, 0.0, 750, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(90.0, 0.0, 0.0, 500)
                            .addPos(0.0, 0.0, 0.0, 750, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus("ROCKET", new BusAnimationSequence().addPos(0.0, empty ? -3.0 : 0.0, 0.0, 0));
            default:
                return null;
        }
    };

    // XFactoryRocket.LAMBDA_QUADRO_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_QUADRO_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(60.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN));
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "RELOAD_ROTATE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 60.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 60.0, 1500)
                            .addPos(0.0, 0.0, 0.0, 750, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "RELOAD_PUSH",
                        new BusAnimationSequence().addPos(-1.0, -1.0, 0.0, 0).addPos(-1.0, -1.0, 0.0, 500).addPos(-1.0, 0.0, 0.0, 350).addPos(0.0, 0.0, 0.0, 1000)
                    );
            case JAMMED:
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "RELOAD_ROTATE",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 60.0, 750, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 60.0, 500)
                            .addPos(0.0, 0.0, 0.0, 750, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case CYCLE:
                return new BusAnimation().addBus("RECOIL", new BusAnimationSequence().addPos(0.0, 0.0, -0.5, 50).addPos(0.0, 0.0, 0.0, 50));
            default:
                return null;
        }
    };

    // XFactoryRocket.LAMBDA_MISSILE_LAUNCHER_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_MISSILE_LAUNCHER_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(60.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 1000, BusAnimationKeyframe.IType.SIN_DOWN));
            case RELOAD:
                return new BusAnimation()
                    .addBus("BARREL", new BusAnimationSequence().addPos(0.0, 0.0, 1.5, 150).addPos(0.0, 0.0, 1.5, 2100).addPos(0.0, 0.0, 0.0, 150))
                    .addBus(
                        "OPEN",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(90.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(90.0, 0.0, 0.0, 1000)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 2250)
                            .addPos(-1.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                    )
                    .addBus(
                        "MISSILE",
                        new BusAnimationSequence()
                            .addPos(-10.0, 0.0, 0.0, 0)
                            .addPos(-10.0, 0.0, 0.0, 750)
                            .addPos(3.0, 0.0, 2.0, 0)
                            .addPos(0.0, 0.0, -6.0, 350, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 350, BusAnimationKeyframe.IType.SIN_UP)
                    );
            case JAMMED:
            case INSPECT:
                return new BusAnimation()
                    .addBus("BARREL", new BusAnimationSequence().addPos(0.0, 0.0, 1.5, 150).addPos(0.0, 0.0, 1.5, 1350).addPos(0.0, 0.0, 0.0, 150))
                    .addBus(
                        "OPEN",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 250)
                            .addPos(90.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(90.0, 0.0, 0.0, 250)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "EQUIP",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, 0.0, 1500)
                            .addPos(-1.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 150, BusAnimationKeyframe.IType.SIN_UP)
                    );
            default:
                return null;
        }
    };

    // XFactoryTool.LAMBDA_CT_ANIMS
    public static final BiFunction<ItemStack, GunAnimation, BusAnimation> LAMBDA_CT_ANIMS = (stack, type) -> {
        switch (type) {
            case EQUIP:
                return new BusAnimation()
                    .addBus("EQUIP", new BusAnimationSequence().addPos(-45.0, 0.0, 0.0, 0).addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_DOWN));
            case CYCLE:
                return new BusAnimation()
                    .addBus(
                        "RECOIL",
                        new BusAnimationSequence()
                            .addPos(0.0, 0.0, -1.0, 100, BusAnimationKeyframe.IType.SIN_DOWN)
                            .addPos(0.0, 0.0, 0.0, 250, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            case RELOAD:
                return new BusAnimation()
                    .addBus(
                        "RAISE",
                        new BusAnimationSequence()
                            .addPos(-45.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(2000)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "AMMO",
                        new BusAnimationSequence()
                            .setPos(0.0, -10.0, -5.0)
                            .hold(500)
                            .addPos(0.0, 0.0, 5.0, 750, BusAnimationKeyframe.IType.SIN_FULL)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_UP)
                            .hold(4000)
                    )
                    .addBus("TWIST", new BusAnimationSequence().setPos(0.0, 0.0, 25.0).hold(2000).addPos(0.0, 0.0, 0.0, 150));
            case INSPECT:
                return new BusAnimation()
                    .addBus(
                        "TURN",
                        new BusAnimationSequence()
                            .addPos(0.0, 60.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(1750)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    )
                    .addBus(
                        "ROLL",
                        new BusAnimationSequence()
                            .hold(750)
                            .addPos(0.0, 0.0, -90.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                            .hold(1000)
                            .addPos(0.0, 0.0, 0.0, 500, BusAnimationKeyframe.IType.SIN_FULL)
                    );
            default:
                return null;
        }
    };
}
