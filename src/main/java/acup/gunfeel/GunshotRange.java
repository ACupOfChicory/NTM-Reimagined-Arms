package acup.gunfeel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.sounds.SoundEvent;

// How far a gunshot carries, blocks, by the gun's fire sound. NTM plays every shot at volume 1: 16 blocks for a player
// and for an AI mob alike (SoundEvent.getRange), a pistol as far as a sniper rifle, no shot past AiBots.LOUD. A sound
// over volume 1 is no louder close by, only heard further (16 blocks a unit, linearly down to nothing), and the server
// sends it to the players that far: the shot's volume made range / 16 (Receiver.getFireVolume, MixinGunshotRange) is
// heard that far by players and AI mobs both. The server's gunshotRange times every range over 16 (0: NTM's 16 for all).
public final class GunshotRange extends GunshotRangeBase {
    private GunshotRange() {
    }

    private static final Map<SoundEvent, Float> BY_SOUND = new ConcurrentHashMap<>();

    /** the range of this fire sound, blocks, cached (a shot asks every time) */
    public static float of(SoundEvent sound) {
        return BY_SOUND.computeIfAbsent(sound, s -> of(s.location().getPath()));
    }

    /**
     * A shot's volume for its range: NTM's volume times range / 16, the part over 16 times the server's gunshotRange.
     * A volume over 1 from NTM itself (its joke shot at 100) is left as it is; so is a sound not listed.
     */
    public static float volume(SoundEvent sound, float volume, float scale) {
        float range = of(sound);
        if (volume > 1F || range <= BASE) return volume;
        float r = BASE + (range - BASE) * Math.max(0F, scale);
        return volume * r / BASE;
    }

    /** the server's gunshotRange: 1 the table, 0 NTM's 16 for all */
    public static float scale() {
        return Math.max(0F, ReaimTimes.number("gunshotRange", 1F));
    }
}
