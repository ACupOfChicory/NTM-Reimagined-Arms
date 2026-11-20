package acup.gunfeel.ai;

import acup.gunfeel.ReaimTimes;

/**
 * What an AI mob is to its squad, by the gun in its hands (the owner, 2026-11-11: no role of its own for
 * suppression, it is the machine gunner's work, as in the manuals: the base of fire keeps them down while the others
 * go round). {@link #BASE}: a gun that fires on while the trigger is held, fast (a shot every {@link AiRoleBase#FAST} ticks at
 * most), real bullets (no beam, nothing slow: a flamer, a grenade), with a belt or a magazine of aiBaseMagazine
 * rounds or more: a machine gun, a minigun, an automatic with a big magazine. {@link #GRENADIER}: any other gun with
 * 40 mm grenades in it (AiGrenadier.shell). {@link #MARKSMAN}: any other gun with a scope (NTM's scope texture).
 * Anyone else is a {@link #RIFLE}. Worked out again when the gun in its hands is another.
 */
public enum AiRole {
    RIFLE, BASE,
    /** hands out magazines and grenades to its squad (AiSupply); by word or by the spawn, never by the gun */
    SUPPLIER,
    /** fights from afar from perches (AiMarksman): a scoped gun that is no base of fire's, or by word */
    MARKSMAN,
    /** lobs 40 mm grenades over cover (AiGrenadier): a gun that fires them, or by word */
    GRENADIER,
    /** treats the hurt of its squad and its side's players (AiMedic); by word or by the spawn, never by the gun */
    MEDIC;

    /** the server setting: roles by the gun (a base of fire's magazine: AiRoleBase.MAG_KEY) */
    public static final String KEY = "aiRoles";
    /** off only for the stands' "before" */
    static boolean on = true;

    public static boolean on() {
        return on && ReaimTimes.flag(KEY, true);
    }

    /** where a role given by word is kept on the mob, so it lasts over a reload of the chunk */
    static final String ROLE_KEY = "armsaiRole";
    /** as typed after "role": the role is by the gun again */
    static final String AUTO = "auto";

    /** its name as typed (role base) and as shown */
    String word() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** the role of this name as typed, or null */
    static @org.jspecify.annotations.Nullable AiRole named(String word) {
        for (AiRole r : values()) if (r.word().equals(word.toLowerCase(java.util.Locale.ROOT))) return r;
        return null;
    }

    /** the names a role may be typed by, auto last */
    static java.util.List<String> words() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (AiRole r : values()) out.add(r.word());
        out.add(AUTO);
        return out;
    }

    /** roles are dealt among mobs spawned by number from this many up (one alone is what its gun makes it) */
    static final int DEAL_FROM = 2;
}
