package acup.gunfeel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The balance: one switch in the server's file, {@code balance = arms} (the mod's balance, the default) or
 * {@code balance = ntm} (NTM's own damage, armour, spread, range and blasts: no rule of the balance touches them). Every
 * number of the balance is fixed in the mod (owner 2026-11-13: "it is all final"): {@link #ARMS}, with its reasons. The
 * server's file no longer sets any of them; an old file's balance keys are not read (one line in the log, ReaimTimes).
 *
 * <p>Dev only: with the JVM property {@code -Dntmgunfeel.balanceKeys=true} (the balance's stands, build.gradle.kts) the
 * server's file sets them again, as before, and {@code config/ntm-gunfeel-balance-guns.tsv} is read in place of the jar's
 * gun table: the stands switch the old lines (X908 and the like) and single rules on and off that way.
 */
public final class BalanceKeys {
    /** the switch in the server's file */
    public static final String KEY = "balance";
    /** the JVM property of the stands: the server's file sets the balance's keys again */
    public static final String DEV = "ntmgunfeel.balanceKeys";
    private static final boolean DEV_ON = Boolean.getBoolean(DEV);
    private static final Pattern CHOKE = Pattern.compile("(^|\\.)spread(Aim|Hip)(Knee|Over)$");

    private BalanceKeys() {
    }

    /** the mod's balance, every key as the server's file had it by default up to X977 */
    static final Map<String, String> ARMS;
    /** NTM's: what {@code balance = ntm} puts in place of {@link #ARMS} (the rest of ARMS is not read then) */
    static final Map<String, String> NTM;

    static {
        Map<String, String> a = new LinkedHashMap<>();
        // The aimed choke (Fable №3): aimed, what of NTM's spread is past spreadAimKnee counts spreadAimOver times, before the
        // multipliers above and what is added below; from the hip NTM's spread as it is. spreadAimKnee = 0: off (X942). The
        // shotguns that aim have their own (shot 0.035 aimed -> 0.021, flechettes 0.025 -> 0.018); an akimbo has no aim and no
        // choke (owner 2026-11-13). spreadHipKnee, spreadHipOver: the same from the hip, only the SPAS-12's (it has no aim in
        // NTM; owner 2026-11-13); 0: off (X942).
        a.put("spreadAimKnee", "0");
        a.put("spreadAimOver", "1");
        a.put("spreadHipKnee", "0");
        a.put("spreadHipOver", "1");

        // the shotguns' choke (Fable №3): aimed; the SPAS-12's from the hip
        a.put("hbm:gun_maresleg.spreadAimKnee", "0.015");
        a.put("hbm:gun_maresleg.spreadAimOver", "0.3");
        a.put("hbm:gun_maresleg_broken.spreadAimKnee", "0.015");
        a.put("hbm:gun_maresleg_broken.spreadAimOver", "0.3");
        a.put("hbm:gun_spas12.spreadHipKnee", "0.015");
        a.put("hbm:gun_spas12.spreadHipOver", "0.3");
        a.put("hbm:gun_autoshotgun.spreadAimKnee", "0.015");
        a.put("hbm:gun_autoshotgun.spreadAimOver", "0.3");
        a.put("hbm:gun_autoshotgun_sexy.spreadAimKnee", "0.015");
        a.put("hbm:gun_autoshotgun_sexy.spreadAimOver", "0.3");
        a.put("hbm:gun_autoshotgun_heretic.spreadAimKnee", "0.015");
        a.put("hbm:gun_autoshotgun_heretic.spreadAimOver", "0.3");
        a.put("hbm:gun_double_barrel.spreadAimKnee", "0.015");
        a.put("hbm:gun_double_barrel.spreadAimOver", "0.3");
        a.put("hbm:gun_double_barrel_sacred_dragon.spreadAimKnee", "0.015");
        a.put("hbm:gun_double_barrel_sacred_dragon.spreadAimOver", "0.3");

        // NTM's power armour and Trenchmaster hold the gun steadier, the later the set the more: what moving, jumping and the
        // bloom add counts powerArmorSpread.<set> times, by the chestplate's set (charged or not; 1 turns a set off). Security,
        // the steam and diesel suits: no bonus. NTM's own spread, standing, sneaking, crawling and aiming stay as they are.
        // powerArmorSpread: only a power armour chestplate with no line of its own (another mod's, a set NTM adds later).
        a.put("powerArmorSpread", "0.5");
        a.put("powerArmorSpread.t51", "0.8");
        a.put("powerArmorSpread.hev", "0.7");
        a.put("powerArmorSpread.ajr", "0.7");
        a.put("powerArmorSpread.ajro", "0.7");
        a.put("powerArmorSpread.envsuit", "0.7");
        a.put("powerArmorSpread.trenchmaster", "0.7");
        a.put("powerArmorSpread.bj", "0.6");
        a.put("powerArmorSpread.ncrpa", "0.6");
        a.put("powerArmorSpread.rpa", "0.5");
        a.put("powerArmorSpread.fau", "0.4");
        a.put("powerArmorSpread.dns", "0.4");

        // The recoil by the same tiers: the camera kick, the gun model kick and NTM's recoil animation count
        // powerArmorRecoil.<set> times (the players' clients go by these; on a server with cameraScaleMin the camera's no lower
        // than 0.5). powerArmorRecoil: only a power armour chestplate with no line of its own.
        a.put("powerArmorRecoil", "0.5");
        a.put("powerArmorRecoil.t51", "0.8");
        a.put("powerArmorRecoil.hev", "0.7");
        a.put("powerArmorRecoil.ajr", "0.7");
        a.put("powerArmorRecoil.ajro", "0.7");
        a.put("powerArmorRecoil.envsuit", "0.7");
        a.put("powerArmorRecoil.trenchmaster", "0.7");
        a.put("powerArmorRecoil.bj", "0.6");
        a.put("powerArmorRecoil.ncrpa", "0.6");
        a.put("powerArmorRecoil.rpa", "0.5");
        a.put("powerArmorRecoil.fau", "0.4");
        a.put("powerArmorRecoil.dns", "0.4");

        // The balance's armour: NTM's combat sets take bullets, beams and blasts (NTM's PHYS, EN and EXPL) by one formula instead
        // of NTM's threshold and resistance. A hit of damage D, its ammo's threshold negation neg and armour piercing pen, on a
        // set of rating S: rho = (D + neg)·(1 + pen) / S; the share that gets through m = b + (1 − d − b)·rho³/(1 + rho³), so
        // a weak hit loses most of itself and a strong one gets nearly all through, but no hit is ever 0 (m ≥ b) and none
        // all (m ≤ 1 − d); b and d go by the set's tier (b 0.25 → 0.025, d 0.06 → 0.20 from steel to euphemium). What is left
        // is F(D)·m, F squeezing one hit's damage past balanceFleshKnee towards balanceFleshMax (never more). Beams and lasers
        // (EN) meet balanceEnShare of S; blasts S itself, but one of more than balanceExplRawLimit raw damage (a mine, C4,
        // TNT) is not squeezed. A piece alone takes its share of the body (helmet 3, chestplate 8, leggings 6, boots 3 of 20)
        // with its own set's numbers, the rest of the body what NTM gives. Fire, radiation, gas, nuclear blasts and falling are
        // NTM's. Anyone wearing the sets: players, FBI agents, AI bots. Euphemium's and DNS's invulnerability stay NTM's. The
        // joke set (the dineutronium ingot set) keeps NTM's numbers, but a hit keeps at least balanceRoflFloor of itself;
        // starmetal is a combat set of tier 6 with bismuth's numbers. The GL-54's acid corrodes S as it does NTM's numbers. balanceS.<set>: each set's rating (0: NTM's numbers
        // for that set). Off: NTM's armour as it is.
        a.put("balanceArmor", "true");

        // balanceArmorBlasts off: blasts take NTM's threshold and resistance, bullets and beams the formula's (euphemium's pool
        // then takes no blasts: they are NTM's, stopped by its invulnerability).
        a.put("balanceArmorBlasts", "true");
        a.put("balanceEnShare", "0.75");
        a.put("balanceFleshKnee", "8");
        a.put("balanceFleshMax", "14");
        a.put("balanceExplRawLimit", "60");

        // A weapon's blast (a hand grenade, a 40 mm shell, a rocket) hits a combat set as if balanceBlastPower times stronger:
        // the formula takes that damage (its power and the flesh's squeeze), but never more than the blast does with no
        // armour; whether it is squeezed (balanceExplRawLimit) and capped goes by its own damage. Explosive bullets' blasts
        // (their gun's table multiplier) and big charges are not touched. 2.7: as X865, 1: as X858.
        a.put("balanceBlastPower", "3");

        // The power by the set's tier, at most balanceBlastPower: sets of tier 4 and below (steel to Security) take
        // balanceBlastPowerLow, so a grenade needs 3-4 hits on them, not 2; a rocket's direct hit on tier 7 (RPA, CMB,
        // Schrabidium) balanceBlastPowerRocketHigh (a Missile Launcher's round: 6 hits on RPA). DNS, FAU and euphemium take
        // balanceBlastPower. All three 2.7: as X865.
        a.put("balanceBlastPowerLow", "1.7");
        a.put("balanceBlastPowerRocketHigh", "2.2");

        // balanceBlastPowerRocketHigh is only for a rocket of more raw damage than this (the Missile Launcher's 50, Stinger's
        // 35): Panzerschreck's 25 takes balanceBlastPower on tier 7, 12 hits on RPA as the 40 mm MK108's 25, not 18. 0: every
        // rocket, as X873.
        a.put("balanceBlastRocketHighFrom", "30");

        // A weapon's blast on a set of tier 4 and below hits it with at least this share of the set's rating as its power: a
        // gunpowder or DEMO grenade or a 40 mm shell's splash takes 3-4 hits on tier 4 (9-15 without; 3 with no armour), the HE
        // grenade's 3 as it was. A blast weaker than 7 raw (its far edge) gets the floor in its share of 7. 0: as X873.
        // The floor lifts only the share of the blast that passes, its flesh is the blast's own (X875: the floor's flesh too,
        // and a set of a greater rating took more of a weak blast: Congo Lake's splash at 3 blocks took 5 hits on tier 4, 6 on
        // T-51b; 0.83 then).
        a.put("balanceBlastLowFloor", "0.9");

        // The tier Trenchmaster takes a weapon's blast at (the power and the floor above). NTM gives it HEV's explosion
        // resistance; at its own tier 6 it took MK108's or a rocket's direct hit in 3 hits against HEV's 4. 6: as X877.
        a.put("balanceBlastTrenchTier", "4");

        // An electric hit (the Tesla Cannon's beam and blast) on a powered chestplate counts this many times, with the balance's
        // armour on; NTM counts it five times, so one click took a DNS-class set whole. 5: as X865.
        a.put("balanceElectricPowered", "1.5");
        a.put("balanceRoflFloor", "0.18");
        a.put("balanceS.steel", "8");
        a.put("balanceS.cobalt", "8");
        a.put("balanceS.alloy", "8");
        a.put("balanceS.titanium", "8");
        a.put("balanceS.taurun", "8");
        a.put("balanceS.dieselsuit", "13");
        a.put("balanceS.t51", "23");
        a.put("balanceS.steamsuit", "66");
        a.put("balanceS.hev", "60");
        a.put("balanceS.ajr", "55");
        a.put("balanceS.ajro", "55");
        a.put("balanceS.security", "56");
        a.put("balanceS.trenchmaster", "72");
        a.put("balanceS.bismuth", "96");
        a.put("balanceS.starmetal", "96");
        a.put("balanceS.bj", "120");
        a.put("balanceS.ncrpa", "120");
        a.put("balanceS.cmb", "159");
        a.put("balanceS.schrabidium", "159");
        a.put("balanceS.rpa", "158");
        a.put("balanceS.fau", "168");
        a.put("balanceS.dns", "186");
        a.put("balanceS.euphemium", "280");

        // The balance's gun damage: a shot of an NTM gun does NTM's damage times its gun and ammo's mult, and its ammo's armour
        // threshold negation and piercing get the pair's additions (the table ntmgunfeel-balance-guns.tsv in the mod jar). An
        // ammo with no line of its own takes its gun's nearest pair of the same kind (piercing, blast, soft); a gun with no
        // lines is NTM's. A gun's own line changes its receiver: Heretic one shot in 12 ticks, not auto, 24 rounds; AM180 one
        // in 3, 60 rounds; AMAT Penance one in 15, Subtlety and MAS-36 one in 17; Morning Glory base damage 40. Anyone's
        // shots: players, NTM's mobs, AI bots. Off: NTM's damage and receivers.
        a.put("balanceGuns", "true");

        // The balance's final scheme: a bullet or a beam of a pair of the scheme's table (ntmgunfeel-balance-final-pairs.tsv) on
        // a player, or on anyone in a piece of the table's sets, does 20/N HP a click whose projectiles all hit. N is worked
        // out so the pair kills the set in the TTK of the ladder balance.final.ttk by d = the pair's tier - the set's (with
        // its ammo's and the pedestal's shifts), times its role's share (balance.final.roles) and its raw DPS's (character),
        // real TTK with its share of hits P, pause and reloads. NTM's damage, the set's threshold and resistance, formula C
        // and the gun table's mult, neg, pen, floor and under= are not in it. The part hit (head x1.75, limb x0.5), the damage
        // cap and the pools of DNS, FAU and euphemium come after, as before. Blasts, the pools' rounds, shields and melee are
        // as before. A shot of no pair of the table (NTM's mobs and turrets with no gun) is NTM's own numbers. false: X908.
        a.put("balance.final", "true");

        // d:seconds. The usual fight of equals 3.5 s (body, the role's range, P). Up a tier x0.77 to 1.25 s at +4 (the floor
        // 0.7 times the head's 1.75); down the hours' ladder: a group of players a tier or more below with as many hours in all
        // as the one above is an equal fight (one tier: about 2 players, two: 3, three: 5). Below -9 x below a step (at most
        // max); no TTK below min (the damage cap's window: 0.8 of the health in 14 ticks). X912: +3:1.63 +2:2.08 +1:2.68 0:3.5
        // -1:7.0 -2:18 -3:105 -4:210 and below 2.
        a.put("balance.final.ttk", "+4:1.25 +3:1.6 +2:2.1 +1:2.7 0:3.5 -1:7 -2:13.5 -3:28 -4:40 -5:50 -6:60 -7:68 -8:75 -9:82");
        a.put("balance.final.ttk.min", "0.7");
        a.put("balance.final.ttk.max", "900");
        a.put("balance.final.ttk.below", "1.1");

        // role:share of the usual TTK: an SMG from the hip is slower to kill with, a shotgun close up quicker (by the roles'
        // accuracy, range and P; the pairs' roles are in the pairs' file). slug: a shotgun's shot of fewer than
        // balance.final.slug.pellets projectiles a round (a slug, an explosive shell), timed as a rifle. X922: shotgun:0.75;
        // X912: pistol:1.2, no slug.
        a.put("balance.final.roles", "smg:1.6 mg:1.3 rifle:1.0 pistol:1.0 shotgun:0.35 slug:1.0 sniper:0.85 beam:1.0 airburst:1.2");

        // role:tiers: the role's share fades back to 1 over this many tiers of the set above the pair (a shotgun kills its
        // equal in two clicks, not a set two tiers up); empty: no fade (X922)
        a.put("balance.final.roles.fade", "shotgun:2");

        // gun:role in place of the pairs' file's (the Shredder fires shot); empty: the file's (X922)
        a.put("balance.final.roleOf", "autoshotgun_shredder:shotgun");

        // 0: no slug role (X912)
        a.put("balance.final.slug.pellets", "4");

        // The magazine's rule: a kill of the equal fight that falls on the last clicks of a magazine (small of one below 10
        // clicks, reserve of a bigger one) or on the first click after a reload moves to click M - spare (the click's damage a
        // little higher), so a miss or two does not double the fight; not when the TTK would get shorter than min of the target,
        // nor for a magazine below 3 clicks. G3 on HEV: 26 rounds of 30 (2.5 s), the heavy revolver 5 of 6. reserve=0: off (X912).
        a.put("balance.final.mag", "reserve=0.15 small=1 min=0.65");

        // Damage by range (Fable 10.4, a plateau and a floor): role:xT/full/floor@at, the shooter's distance to the target at
        // the hit. Up to balance.final.range.close blocks a role's close-up time T (an SMG x0.5, a pistol x0.8: twice and 1.25
        // times the damage), back to 1 by its home distance (balance.final.range.home); full damage up to full blocks; then
        // straight down to floor of it at at blocks, and no lower. No line: 1. Blasts, the Tesla Cannon's arc and its IR beam: 1.
        // X942: shotgun:x1/16/0.4@48 slug:x1/24/0.4@64, no shell. X922: no shotgun line, slug:x1/32/0.4@96 beam:x1/32/0.4@96. off: no range
        // rule (X912).
        // shell: a shotgun's explosive shell's direct hit (Fable №3; none: its role's line, X942).
        a.put("balance.final.range", "smg:x0.5/24/0.4@64 pistol:x0.8/24/0.4@64 shotgun:x1/16/0.4@64 slug:x1/32/0.3@128 shell:x1/24/0.25@80 beam:x1/64/0.5@160 rifle:x1/64/0.5@160 mg:x1/64/0.5@160 sniper:x1/160/0.7@256");

        // A gun's own line in place of its role's, gun:line by spaces: every hit of the gun's the scheme takes, an explosive
        // drop's blast on the creature it struck too. The Shredder fires beams of plasma (its role stays the shotgun's share):
        // the drops its beam leaves where it strikes do its damage, by the lasers' line and the shooter's distance (owner
        // 2026-11-13). Empty or off: the role's line (X967); a value of which nothing reads: this default.
        a.put("balance.final.range.lineOf", "autoshotgun_shredder:beam");
        a.put("balance.final.range.close", "8");
        a.put("balance.final.range.home", "pistol:12 smg:12");

        // the pools of DNS, FAU and euphemium the scheme counts in, and their pools' size while no balance.immune*.pool is set
        // (the pool grows with the tier). X912: dns:40 fau:45 euph:150. With balance.final off the pools are X908's.
        a.put("balance.final.pools", "dns:45 fau:40 euph:150");

        // Blasts on the ladder (Fable 12.3): a launcher's round's or a hand grenade's blast on a player, or on anyone in a set
        // of the scheme, takes 1/N of the target's health and pool near it (up to a block), N from the ladder's TTK for d = the
        // blast's tier + shift - the set's: a launcher's the rounds (2 or more) whose real TTK with its interval, magazine and
        // reload comes closest, a hand grenade's 3 at d = 0 times the TTK over the equal fight's. A direct hit 1.5 times, 3
        // blocks off 0.75, the reach's edge 0.4; at most half a hit (two hits at least), one's own blast half; no head nor limb.
        // Radius, blocks and the big charges are NTM's. false: formula C (balanceBlast*), as X912.
        a.put("balance.final.blast", "true");

        // a launcher's round's tier: cap:N the smaller of the launcher's and the round's + N; max the larger of the two
        a.put("balance.final.blast.pair", "cap:2");
        a.put("balance.final.blast.shift", "1");
        a.put("balance.final.blast.hits", "near=3 direct=1.5 far=0.75@3 edge=0.4");

        // launcher:tier:seconds between shots/magazine/reload seconds (a one-round one: launcher:tier:seconds from a shot to the next)
        a.put("balance.final.blast.launchers", "congolake:2:1.2/4/3.2 mk108:4:0.5/30/6.75 panzerschreck:4:2.5 stinger:4:2.5 quadro:4:0.5/4/2.75 missile_launcher:5:2.4 gl54:5:0.7/8/2.25");

        // the rounds' explosive's tier by NTM's ammo name (the GL-54's by its grenade's kind)
        a.put("balance.final.blast.rounds", "g40_he:1 g40_heat:4 g40_demo:4 g40_inc:3 rocket_he:1 rocket_heat:4 rocket_demo:4 rocket_inc:3 he:1 airburst:1 inc:1 ap:4");

        // NTM's hand grenades by filling (the others: NTM's numbers)
        a.put("balance.final.blast.grenades", "powder:0 he:1 demo:4");

        // The GL-54's fragment grenades, kind:share[/piercing]: a burst whose cone all hits takes the share of the health and pool
        // for the bursts (no fewer than a click of 12 HP allows) whose time with the gun's interval and magazine is closest to the
        // ladder's at the grenade's tier (+ the piercing's lift on armour of tier 3 and up) times the role airburst; share: the
        // cone's fragments in the target the count is made for (Fable's guess; the stand: AB 0.49 at 1.5 blocks, 0.13 at 3).
        a.put("balance.final.blast.frags", "airburst:0.35 ap:0.6/0.15 inc:0.35");

        // The fight log: a line for every kill of a player by a player in logs/ntmgunfeel-fights.log (the killer's gun and
        // ammo, both sets and tiers, the distance, the time from the first hit, shots, hit clicks, head hits, reloads), at most
        // size MB (then kept as .1). It measures the usual fight on the server: the median of equals (tiers within half a tier)
        // over a week is meant to be 4-7 s; below 4 multiply balance.final.ttk by 1.25, above 7 divide it. false: nothing
        // written, no work in a shot.
        a.put("balance.final.log", "true");
        a.put("balance.final.log.size", "4");

        // beta:lo:hi: (the tier's median raw DPS / the pair's)^beta within lo..hi times the TTK (bullets)
        a.put("balance.final.character", "0.3:0.7:1.4");

        // beams and lasers: their TTK times this (X922: 1.15)
        a.put("balance.final.energy", "1.0");

        // a hit of the scheme on a mob meets the vanilla armour a player in the same pieces has (the pieces' own): player; own:
        // the mob's own armour too (an FBI agent's 20 and balance.fbiArmor took a hit of the scheme down to a fifth), as X949
        a.put("balance.final.mobArmor", "player");

        // the acid's corrosion K (0 to 0.75) takes the set's tier down by tiers x K / 0.75, in full up to tier + full, fading to
        // nothing over fade tiers above it (HEV at K 0.75 as T-51, BJ as 5.33, FAU as it is; never below bare); off: none (X922)
        a.put("balance.final.acid", "tiers=1.0 tier=4 full=+1 fade=3");

        // an armour-piercing round (piercing from 0.15) a tier up to max for pen x piercing, a hollow point jhp; full from tier 3
        a.put("balance.final.ammo", "pen=4.0 from=0.15 max=1.0 jhp=-1");

        // a pedestal's, secret or loot gun: its tier plus this
        a.put("balance.final.ped", "0.5");

        // a mixed or incomplete set: its tier by the slots' weights (helmet, chestplate, leggings, boots), an empty slot or a
        // piece not of the table counts as empty (X922: 0.5); the pool of DNS, FAU and euphemium only with a full set of one of them
        a.put("balance.final.armor.weights", "3,8,6,3");
        a.put("balance.final.armor.empty", "0");

        // a one-shot on the head with these guns on a set of a tier by the hours at least gap below the pair's; empty: no gun
        // kills with one click on the head (the scheme's head hits take damageCapHead, a sniper rifle's too)
        a.put("balance.final.head.guns", "");
        a.put("balance.final.head.gap", "1");

        // DNS is never killed quicker than FAU: its TTK at least this times FAU's
        a.put("balance.final.dnsOverFau", "1.15");

        // Rules "pair:set:share" by spaces: a pair of the gun table of tier pair or below (the table's sixth column) hits a set
        // of tier set and up with the armour formula's least share times share (on top of the pair's own floor; of several
        // rules the least share). 2:4:0.5: a gun and ammo of tier 2 and below get through tier 4 armour (HEV, AJR, Security,
        // steam) and up with half the floor, so a man in HEV with a tier 4 gun beats three in steel with tier 2 guns (wins 66 %
        // of the pairs and keeps a median 3 HP of 20 without it, 97 % and 10.7 HP with it; the damage cap is per shooter, but
        // their guns do not reach it on HEV, so a cap shared by the target would change nothing). 5:8:0.5: a gun of tier 5 and
        // below gets through DNS, FAU and euphemium (their pools too) with half the floor: three machine guns of tier 4 took
        // DNS in 11 s, now in 21 s and up. Empty or off: no rules, as X884.
        a.put("balanceArmorTierFloor", "2:4:0.5 5:8:0.5");

        // The balance's recipes: the diesel suit at the oil tier (helmet: an integrated circuit board instead of the analog one;
        // chestplate: two of them and two cast high-speed steel plates, one wool less; leggings: two cast high-speed steel
        // plates more), the T-51 chestplate, leggings and boots (an integrated circuit board each, as the helmet), Tesla Cannon
        // at tier 7 (a quantum processing unit instead of the military grade board, two welded
        // combine steel plates instead of two copper coils), Lasrifle at tier 7 (a quantum processing unit, schraranium
        // crystals instead of redstone crystals), Tau at tier 8 (a quantum processing unit, two 4000K coils instead of the
        // copper tori, two dineutronium compound plates instead of the copper pipe). One recipe of each in the recipe book and
        // JEI. Read when the data packs load: a change counts after /reload or a restart. Off: NTM's recipes.
        a.put("balanceRecipes", "true");

        // The balance's armour abilities: a full set's Speed and Jump Boost by its tier (abilities.mobilityCap: up to tier 6
        // Speed II and Jump I at most, from tier 7 Speed III and Jump II: the diesel suit III/III -> II/I, Trenchmaster Jump II
        // -> I, DNS Jump III -> II), Strength at most abilities.strengthCap on every set (2: NCRPA and RPA IV, CMB V, DNS X,
        // Trenchmaster and schrabidium III -> II; 0: NTM's). abilities.bismuth: the bismuth set as a tier 6 set, Speed III,
        // Jump II, Regeneration I, night vision, two dashes (off: NTM's VII, VII, II, three dashes, not cut by the keys
        // above). The sets' VATS and thermal vision show no players (abilities.esp.players = tier8): only a tier 8 set's
        // thermal vision (FAU, DNS) shows the players it sees within abilities.esp.playerRange blocks; off: no players at all;
        // all: NTM's, everyone through walls within 64 blocks. AMAT Penance's thermal sight shows players as a tier 8 set.
        // The rest (haste, fire resistance, step, hard landing, immunity) is NTM's.
        a.put("abilities.mobilityCap", "true");
        a.put("abilities.strengthCap", "2");
        a.put("abilities.bismuth", "true");
        a.put("abilities.esp.players", "tier8");
        a.put("abilities.esp.playerRange", "16");

        // The balance's kit (what a player carries on top of the armour makes a fight about twice as long, not ten times).
        // balance.shield: the player's shield. The shield mod (australium III) gives balance.shield.mod (NTM: 25), a shield
        // flask balance.shield.flask (NTM: 5; no more flasks once they alone reach the cap), all of it at most
        // balance.shield.cap (NTM: 100 of flasks, the mod on top); it comes back balance.shield.delay ticks after the last hit
        // (NTM: 60), then balance.shield.rate * t a tick, t the ticks since (NTM: 0.005). NTM keeps its own count of flasks:
        // off gives its numbers back. balance.medx: Med-X gives Resistance I for 60 s (NTM: III for 4 min); with
        // balanceRecipes its recipe takes two plastic plates and two quartz (NTM: four quartz). balance.superStimpak: the super
        // stimpak heals its 25 HP over 12 s (NTM: at once). balance.inserts: an armour insert takes at most
        // 1 - balance.inserts.bullets off a bullet (kevlar, SAPI, ESAPI, XSAPI, DU, ghiorsium; their damage and blast shares
        // are NTM's). balance.vanilla0: the combat sets of balanceS.* have no vanilla armour points and toughness, so
        // vanilla's armour does not come on top of the formula (a set with balanceS 0, the joke sets and the rest keep theirs;
        // counts when a piece is put on again). Off: NTM's.
        a.put("balance.shield", "true");
        a.put("balance.shield.mod", "10");
        a.put("balance.shield.flask", "2");
        a.put("balance.shield.cap", "16");
        a.put("balance.shield.delay", "100");
        a.put("balance.shield.rate", "0.002");
        a.put("balance.medx", "true");
        a.put("balance.superStimpak", "true");

        // The super stimpak's heal over time does not add up: another one put on the same player (their own or a friend's)
        // starts its 25 HP over 12 s again if that is more than is left (else it changes nothing), instead of adding on top (two from two hands
        // healed 50 HP in 12 s and took the HEV player's win over three in steel from 97 % to 77 % of the pairs). true: adds
        // up, as X884.
        a.put("balance.healStack", "false");
        a.put("balance.inserts", "true");
        a.put("balance.inserts.bullets", "0.9");
        a.put("balance.vanilla0", "true");

        // NTM's FBI agent wears Security: with balance.vanilla0 it gets this much vanilla armour of its own (0: none), as it held
        // before the combat sets lost theirs.
        a.put("balance.fbiArmor", "10");

        // Euphemium's invulnerability with a pool (balance.immune.mode = pool; ntm: every hit on the full set called off, as
        // NTM does). Bullets, beams and blasts are still called off, but each takes from a pool of balance.immune.pool what it
        // would do to a DNS-class set (S 186; one hit at most damageCapClick of the health); the pool comes back over
        // balance.immune.recover seconds once nothing took from it for balance.immune.refillPause s. Other damage is called off and takes nothing.
        // The emptied pool opens a window of balance.immune.window seconds: every hit goes through to the set's rating
        // (balanceS.euphemium) and the damage cap, and the set's Regeneration and Resistance 128 are off. Then the pool fills
        // back from 0 over balance.immune.recover seconds, the set invulnerable meanwhile. The tomb's radon gas takes the
        // invulnerability off entirely (with the Regeneration and Resistance) while the wearer is in it: its radiation counts.
        a.put("balance.immune.mode", "pool");
        a.put("balance.immune.pool", "150");
        a.put("balance.immune.window", "15");
        a.put("balance.immune.recover", "60");

        // For euphemium, DNS and FAU alike: a shot, IR beam or blast in the window (not melee) keeps it open at least this many seconds after it, so a
        // gun that does not kill in the window goes on while it keeps hitting (a reload included), instead of the set's whole
        // invulnerability and a new pool (no gun took between 60 and 120 s on DNS; one gun took 45 s on DNS and 134 on FAU).
        // 0: the window is its own length, as X873.
        a.put("balance.immune.hold", "10");

        // For the three sets: seconds after the last hit that took from the pool before it starts to come back. 5: as X875.
        a.put("balance.immune.refillPause", "5");

        // One pool a player for DNS, FAU and euphemium: putting another kit on keeps where the pool is (its share of the full pool, the
        // window, the recovery), instead of a fresh pool of the other kit's (DNS emptied, then FAU on, was a full pool of 45
        // at once). false: a pool a kit, as X884.
        a.put("balance.immune.shared", "true");

        // DNS and FAU, the 240-hour sets, take euphemium's round with their own numbers (balance.immune.dns.* and
        // balance.immune.fau.*). Mode pool: while the pool holds, bullets, beams and blasts on the full charged set are called
        // off, each taking from the pool what it would have done to the set itself (balanceS.dns 186, balanceS.fau 168; one
        // hit at most damageCapClick of the health); the emptied pool opens a window of .window seconds where hits go through
        // to the set's rating, then the pool fills back over .recover seconds, the set invulnerable meanwhile. A pool not
        // emptied comes back at the same rate once nothing took from it for balance.immune.refillPause s. Other damage is NTM's numbers of the set; the
        // tomb's radon gas takes the pool off while the wearer is in it. Mode formula: no invulnerability, the set's rating
        // only (as before the pool); DNS also ntm: every hit but a blast called off, blasts down to 0.001, as NTM does.
        a.put("balance.immune.dns.mode", "pool");
        a.put("balance.immune.dns.pool", "40");
        a.put("balance.immune.dns.window", "15");
        a.put("balance.immune.dns.recover", "60");
        a.put("balance.immune.fau.mode", "pool");
        a.put("balance.immune.fau.pool", "45");
        a.put("balance.immune.fau.window", "15");
        a.put("balance.immune.fau.recover", "60");
        ARMS = Collections.unmodifiableMap(a);

        Map<String, String> n = new LinkedHashMap<>();
        // the balance's armour (formula C and the final scheme with it), the gun table and its receivers, the kit, the
        // pools of euphemium and DNS, the recipes: off, NTM's
        for (String k : List.of("balanceArmor", "balance.final", "balance.final.log", "balanceGuns", "balanceRecipes", "balance.shield", "balance.medx",
                "balance.superStimpak", "balance.inserts", "balance.vanilla0"))
            n.put(k, "false");
        n.put("balance.immune.mode", "ntm");
        n.put("balance.immune.dns.mode", "ntm");
        // FAU has no invulnerability in NTM: its rating only, NTM's with balanceArmor off
        n.put("balance.immune.fau.mode", "formula");
        // the armour abilities: NTM's
        n.put("abilities.mobilityCap", "false");
        n.put("abilities.strengthCap", "0");
        n.put("abilities.bismuth", "false");
        n.put("abilities.esp.players", "all");
        NTM = Collections.unmodifiableMap(n);
    }

    /** a key of the balance: not read from the server's file (but on the stands) */
    public static boolean is(String key) {
        return (key.startsWith("balance") && !key.equals(KEY)) || key.startsWith("abilities.") || key.contains("powerArmorSpread")
                || key.contains("powerArmorRecoil") || CHOKE.matcher(key).find();
    }

    /** the stands: the server's file sets the balance's keys */
    public static boolean dev() {
        return DEV_ON;
    }

    /** the switch's value read from {@code balance}: ntm, else arms (anything else too) */
    static boolean ntm(String value) {
        return value != null && value.trim().equalsIgnoreCase("ntm");
    }

    /** a balance key's value by the switch, or null: the reader's own default */
    static String value(String key, boolean ntm) {
        if (ntm) {
            String v = NTM.get(key);
            if (v != null) return v;
            // no bonus of the power armour, no choke
            if (key.contains("powerArmorSpread") || key.contains("powerArmorRecoil")) return "1";
            if (key.endsWith("Knee")) return "0";
        }
        return ARMS.get(key);
    }
}
