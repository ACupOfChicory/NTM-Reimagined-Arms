package acup.gunfeel.water;

import java.util.Map;

import com.hbm.items.weapon.sedna.BulletConfig;

import com.hbm.items.ModItems;
import com.hbm.items.weapon.sedna.factory.GunFactory;

import net.minecraft.item.Item;

// How a bullet slows in water. Water drag is quadratic in speed, dv/dx = -v/L with L = 2m / (rho·Cd·A), so the speed
// falls off exponentially with the path in water: f <- f·exp(-rho·s / L). L is the class's stopping length in blocks
// (one block = 1 m); the bullet stops (f = 0.15) after L·ln(1/0.15) ≈ L·1.9 blocks of water. The numbers follow the
// MythBusters measurements ("Bulletproof Water", 2005): supersonic rounds up to .50 broke up or stopped within ~0.9 m,
// slow pistol rounds stayed dangerous to ~2.4 m, slugs went deeper; buckshot by the formula (00 pellet 3.5 g, Cd 0.45).
// density = the projectile's density in g/cm³, for the critical angle of a skip off water, 18° / sqrt(density / 1)
// (Birkhoff 1944, Hutchings 1976).
public enum WaterClass {
    PISTOL(1.25, 11.3, false),
    RIFLE(0.65, 8.9, false),
    HEAVY(0.7, 8.9, false),
    AP(0.9, 7.85, false),
    SLUG(1.4, 11.3, false),
    SHOT(0.3, 11.3, false),
    // 10 gauge DU: ten uranium mini-bullets 6×14 mm (~6 g, density 19), not balls: by the name
    MINI(0.7, 19, false),
    DART(2.0, 7.85, false),
    GRENADE(3.0, 2.5, true),
    ROCKET(4.0, 2.0, true),
    // lasers, beams, the tau and capacitor guns: water does not slow them (steam on the surface is the effects' part)
    BEAM(Double.POSITIVE_INFINITY, 0, false),
    // flamethrower fuel: goes out where it meets water
    FLAME(0, 0, false),
    // what water does nothing to: the fire extinguisher's spray, the debug round
    NONE(Double.POSITIVE_INFINITY, 0, false);

    // stopping length L in blocks: +inf = not slowed, 0 = stops where it meets the liquid
    public final double length;
    public final double density;
    // an impact fuze: goes off on water (the explosives part, waterFuze), skips under waterFuzeSkipAngle
    public final boolean explosive;

    WaterClass(double length, double density, boolean explosive) {
        this.length = length;
        this.density = density;
        this.explosive = explosive;
    }

    public boolean slows() {
        return length > 0 && length < Double.POSITIVE_INFINITY;
    }

    // the critical skip angle by the formula, degrees: lead 5.4, copper 6.0, steel 6.4, a grenade 11.4, a rocket 12.7;
    // NaN for what does not skip. The rule itself goes by the settings (waterSkipAngle, waterFuzeSkipAngle).
    public double formulaAngle() {
        return density > 0 ? 18.0 / Math.sqrt(density) : Double.NaN;
    }

    // an expanding bullet (hollow or soft point): over the first `span` blocks of water it opens and its L falls
    // smoothly to (1 - drop)·L, L(u) = L·(1 - drop·min(1, u / span)), u = the path in water so far (× the density)
    public record Expansion(double span, double drop) {
        // hollow point: open within 0.12 blocks, L to 55%
        public static final Expansion JHP = new Expansion(0.12, 0.45);
        // soft point: within 0.2 blocks, L to 75%
        public static final Expansion SP = new Expansion(0.2, 0.25);

        // by the ammo's name: *_jhp, *_sp (not by the armour piercing: b75_exp has the JHP's -0.25 too); express is not
        // expanding, NTM gives it the FMJ's piercing
        public static Expansion of(String ammo) {
            if (ammo.endsWith("_jhp")) return JHP;
            if (ammo.endsWith("_sp")) return SP;
            return null;
        }

        // port: not of(BulletConfig), the tests' javac has no NTM to tell the overloads apart
        public static Expansion ofConfig(BulletConfig c) {
            return of(ammoName(c));
        }
    }

    // ammo whose class the numbers do not tell: by the ammo item's path (hbm:<path>)
    static final Map<String, WaterClass> BY_NAME = Map.ofEntries(
            Map.entry("ammo_standard_g12_flechette", DART),
            Map.entry("ammo_standard_g10_du", MINI),
            Map.entry("ammo_standard_coil_tungsten", AP),
            Map.entry("ammo_standard_coil_ferrouranium", AP),
            Map.entry("ammo_standard_ct_hook", GRENADE),
            Map.entry("ammo_standard_ct_mortar", GRENADE),
            Map.entry("ammo_standard_ct_mortar_charge", GRENADE),
            Map.entry("ammo_standard_g26_flare", GRENADE),
            Map.entry("ammo_standard_g26_flare_supply", GRENADE),
            Map.entry("ammo_standard_g26_flare_weapon", GRENADE),
            Map.entry("ammo_secret_folly_nuke", GRENADE),
            Map.entry("ammo_secret_folly_sm", BEAM),
            Map.entry("ammo_fireext", NONE),
            Map.entry("ammo_fireext_foam", NONE),
            Map.entry("ammo_fireext_sand", NONE),
            Map.entry("ammo_debug", NONE));

    // how a bullet goes off (NTM's onImpact lambdas, notes/water/README.md): IMPACT blows up where it touches
    // anything; WEAK (b75_inc) only sets a target alight in NTM, under water it gives a small blast instead; NONE
    // has no charge (a flare or a phosphorus pellet sets alight, it does not blow up)
    public enum Fuze {
        NONE, IMPACT, WEAK;

        public boolean any() {
            return this != NONE;
        }

        // the Jeremy turret's 240 mm shells: the plain and the explosive one blow up, the W9 is a nuke; the APFSDS ones do
        // not
        static final java.util.Set<String> IMPACT_NAMES = java.util.Set.of("ammo_standard_r762_he", "ammo_standard_bmg50_he",
                "ammo_standard_b75", "ammo_standard_b75_exp", "ammo_standard_g12_explosive", "ammo_standard_g10_explosive",
                "ammo_standard_ct_mortar", "ammo_standard_ct_mortar_charge", "ammo_secret_folly_nuke", "ammo_shell", "ammo_shell_explosive",
                "ammo_shell_w9");

        public static Fuze of(String ammo) {
            if (ammo.equals("ammo_standard_b75_inc")) return WEAK;
            if (IMPACT_NAMES.contains(ammo) || ammo.startsWith("ammo_standard_g40_") || ammo.startsWith("ammo_standard_rocket_")
                    || ammo.startsWith("ammo_standard_nuke_")) return IMPACT;
            return NONE;
        }

        private static final java.util.Map<BulletConfig, Fuze> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

        // asked every tick of a bullet: kept per config (port: ofConfig, as WaterClass.ofConfig)
        public static Fuze ofConfig(BulletConfig c) {
            if (c == null) return NONE;
            return CACHE.computeIfAbsent(c, Fuze::find);
        }

        private static Fuze find(BulletConfig c) {
            // a cluster grenade's bomblets blow up where they land (ItemGrenadeFilling.pellets)
            if (c.ammo == null && (c == com.hbm.items.weapon.grenade.ItemGrenadeFilling.pellets
                    || c == com.hbm.items.weapon.grenade.ItemGrenadeFilling.pellets_heavy)) return IMPACT;
            if (field(c) != null) return IMPACT;
            return of(ammoName(c));
        }

        // NTM's configs that blow up where they hit but whose ammo does not say so: no ammo of their own (the masked
        // man's bolts, meteors, rockets and orbs, the UFO's rockets, the fat man's cluster bomblets, a pile's debris) or
        // a plain flame's (the Daybreaker's). Not the equestrian rounds (they drop a boxcar, a boat, a building from
        // the sky) nor the masked man's tracer (it calls a meteor down): those do not go off themselves. By their
        // fields, looked up by name, so one missing in another NTM leaves out only its own fuze.
        // port: NTM 1.7.10 X5863 has the Daybreaker's and the cluster submunition; the masked man and the UFO fire its
        // old bullets there, not these. Its pile's debris is TileEntityPileCore.pile_debris (a full class name: not in
        // the factories)
        static final String[][] IMPACT_FIELDS = {
                {"XFactoryFlamer", "flame_daybreaker_diesel", "flame_daybreaker_gas", "flame_daybreaker_napalm", "flame_daybreaker_balefire"},
                {"XFactoryCatapult", "cluster_submunition"},
                {"XFactoryNPC", "maskman_bolt", "maskman_meteor", "maskman_rocket", "maskman_orb", "ufo_rocket"},
                {"XFactoryPile", "debris"},
                {"com.hbm.tileentity.machine.pile.TileEntityPileCore", "pile_debris"}};
        private static volatile Map<BulletConfig, String> fields;

        // port: an old EntityBulletBaseNT's: what blows up where it hits, by name as 26.2's XFactoryNPC configs in
        // IMPACT_FIELDS (the masked man's bolt, orb, rocket and meteor: an ExplosionVNT at the impact; the UFO's
        // rocket: explosive 0, NTM's dealDamage in its impact). Not the masked man's tracer (it calls a meteor down)
        // nor BOT Prime's bolts
        public static Fuze ofOld(com.hbm.handler.BulletConfiguration c) {
            if (c == null) return NONE;
            for (int id : new int[] {com.hbm.handler.BulletConfigSyncingUtil.MASKMAN_BOLT, com.hbm.handler.BulletConfigSyncingUtil.MASKMAN_ORB,
                    com.hbm.handler.BulletConfigSyncingUtil.MASKMAN_ROCKET, com.hbm.handler.BulletConfigSyncingUtil.MASKMAN_METEOR,
                    com.hbm.handler.BulletConfigSyncingUtil.UFO_ROCKET})
                if (c == com.hbm.handler.BulletConfigSyncingUtil.pullConfig(id)) return IMPACT;
            return NONE;
        }

        // the IMPACT_FIELDS name of this config, or null
        public static String field(BulletConfig c) {
            Map<BulletConfig, String> f = fields;
            if (f == null) {
                f = NtmFields.configs(IMPACT_FIELDS);
                // none found: asked before NTM made its configs, try again later
                if (!f.isEmpty()) fields = f;
            }
            return f.get(c);
        }
    }

    // sets a target alight where it hits (phosphorus, fire): under water it does not
    public static boolean incendiary(String ammo) {
        return ammo.endsWith("_inc") || ammo.endsWith("_phosphorus") || ammo.startsWith("ammo_standard_g26_flare");
    }

    // (port: incendiaryConfig, as ofConfig)
    public static boolean incendiaryConfig(BulletConfig c) {
        return c != null && (incendiary(ammoName(c)) || c.onImpact != null && c.onImpact == NtmFields.R556_INCENDIARY.get());
    }

    // the class of a bullet NTM fires (port: ofConfig, as Expansion.ofConfig)
    // port: NTM 1.7.10's old EntityBulletBaseNT (the masked man's orbs, bolts, rockets and meteors, the UFO's rockets).
    // 26.2 fires these as its own bullets (XFactoryNPC) and classes them by ofConfig: no ammo of their own, an impact
    // fuze, so by their speed (a rocket at 0.5 or less, a grenade at 4 or less, else a pistol bullet)
    public static WaterClass ofOld(com.hbm.handler.BulletConfiguration c) {
        if (c == null) return PISTOL;
        return of("", false, c.bulletsMax, 0F, 0F, c.velocity, Fuze.ofOld(c).any());
    }

    public static WaterClass ofConfig(BulletConfig c) {
        if (c == null) return PISTOL;
        // a grenade's fragments and bomblets: no ammo of their own, they slow like buckshot
        if (c.ammo == null && (c == com.hbm.items.weapon.grenade.ItemGrenadeFilling.fragmentation
                || c == com.hbm.items.weapon.grenade.ItemGrenadeFilling.pellets
                || c == com.hbm.items.weapon.grenade.ItemGrenadeFilling.pellets_heavy)) return SHOT;
        return of(ammoName(c), c.pType == BulletConfig.ProjectileType.BEAM, c.projectilesMax, c.armorThresholdNegation,
                c.armorPiercingPercent, c.velocity, c.onImpact != null);
    }

    // the ammo item's path (hbm:<path>), "" when it has none
    // port: NTM 1.7.10's ammo is one item with metadata (ammo_standard by GunFactory.EnumAmmo, ammo_secret by
    // EnumAmmoSecret, the fire extinguisher's by 0 water, 1 foam, 2 sand); the name is 26.2's item id, as ExplosiveAmmo
    // has it. Other items by their registry name without "hbm:" and "item."
    // (port: public, for the water stand)
    public static String ammoName(BulletConfig c) {
        if (c == null || c.ammo == null || c.ammo.item == null) return "";
        try {
            Item item = c.ammo.item;
            int meta = c.ammo.meta;
            if (item == ModItems.ammo_standard) {
                GunFactory.EnumAmmo[] all = GunFactory.EnumAmmo.values();
                return meta >= 0 && meta < all.length ? "ammo_standard_" + all[meta].name().toLowerCase(java.util.Locale.ROOT) : "";
            }
            if (item == ModItems.ammo_secret) {
                GunFactory.EnumAmmoSecret[] all = GunFactory.EnumAmmoSecret.values();
                return meta >= 0 && meta < all.length ? "ammo_secret_" + all[meta].name().toLowerCase(java.util.Locale.ROOT) : "";
            }
            // the turret's 240 mm shells: one item by Ammo240Shell, 26.2's ammo_shell, ammo_shell_explosive, ...
            if (item == ModItems.ammo_shell) {
                com.hbm.items.ItemAmmoEnums.Ammo240Shell[] all = com.hbm.items.ItemAmmoEnums.Ammo240Shell.values();
                if (meta < 0 || meta >= all.length) return "";
                return meta == 0 ? "ammo_shell" : "ammo_shell_" + all[meta].name().toLowerCase(java.util.Locale.ROOT);
            }
            if (item == ModItems.ammo_fireext) return meta == 1 ? "ammo_fireext_foam" : meta == 2 ? "ammo_fireext_sand" : "ammo_fireext";
            Object name = Item.itemRegistry.getNameForObject(item);
            if (name == null) return "";
            String n = name.toString();
            if (!n.startsWith("hbm:")) return "";
            n = n.substring(4);
            return n.startsWith("item.") ? n.substring(5) : n;
        } catch (RuntimeException e) {
            // no item: by the numbers alone
        }
        return "";
    }

    // the same on plain numbers (the tests run it on NTM's dump): ammo = the item's path without "hbm:", beam = NTM's
    // BEAM projectile type, projectiles = projectilesMax, thresholdNegation and piercing = the armour piercing,
    // velocity = blocks per tick, explosive = it has an impact lambda (onImpact)
    public static WaterClass of(String ammo, boolean beam, int projectiles, float thresholdNegation, float piercing,
            float velocity, boolean explosive) {
        WaterClass named = BY_NAME.get(ammo);
        if (named != null) return named;
        if (beam) return BEAM;
        if (ammo.startsWith("ammo_standard_flame_")) return FLAME;
        if (ammo.startsWith("ammo_standard_rocket_")) return ROCKET;
        if (ammo.startsWith("ammo_standard_g40_") || ammo.startsWith("ammo_standard_nuke_")) return GRENADE;
        // a launched charge with no muzzle velocity of its own: a rocket; a slow one with a fuze: a grenade
        if (explosive && velocity <= 0.5F) return ROCKET;
        if (explosive && velocity <= 4F) return GRENADE;
        if (projectiles > 1) return SHOT;
        if (ammo.contains("slug")) return SLUG;
        // a hard core that goes through armour: rifle and heavy rounds with real armour piercing (a pistol AP round
        // keeps the pistol's L, it is the longer one)
        boolean core = thresholdNegation >= 10F && piercing >= 0.15F;
        WaterClass calibre = calibre(ammo);
        if (core && (calibre == RIFLE || calibre == HEAVY)) return AP;
        if (calibre != null) return calibre;
        return core ? AP : PISTOL;
    }

    private static WaterClass calibre(String ammo) {
        String a = ammo.replace("ammo_secret_", "ammo_standard_");
        if (!a.startsWith("ammo_standard_")) return null;
        String c = a.substring("ammo_standard_".length());
        int cut = c.indexOf('_');
        if (cut >= 0) c = c.substring(0, cut);
        return switch (c) {
            case "p22", "p9", "p45", "p35", "m357", "m44", "stone" -> PISTOL;
            case "r556", "r545", "r762", "r308" -> RIFLE;
            case "bmg50", "b75" -> HEAVY;
            case "g12", "g10" -> SLUG; // a single projectile from a shotgun shell
            default -> null;
        };
    }
}
