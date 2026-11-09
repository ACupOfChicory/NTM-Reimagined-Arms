package acup.gunfeel.ai.client;

import java.util.concurrent.CompletableFuture;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.common.NeoForge;

import acup.gunfeel.Config;

// AI mobs' call-outs (AiVoice) as this player wants them: none (aiVoice false), or quieter (aiVoiceVolume).
public final class AiVoiceClient {
    private AiVoiceClient() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(PlaySoundEvent.class, AiVoiceClient::play);
    }

    /** a call-out: ntmgunfeel:ai.voice.* */
    static boolean voice(Identifier id) {
        return id.getNamespace().equals("ntmgunfeel") && id.getPath().startsWith("ai.voice.");
    }

    private static void play(PlaySoundEvent e) {
        SoundInstance s = e.getSound();
        if (s == null || !voice(s.getIdentifier())) return;
        if (!Config.aiVoice || Config.aiVoiceVolume <= 0F) e.setSound(null);
        else if (Config.aiVoiceVolume < 1F) e.setSound(new Quieter(s, Config.aiVoiceVolume));
    }

    /** the sound as it was, its volume times k (the volume is the sound's own only once resolved: asked of it then) */
    record Quieter(SoundInstance s, float k) implements SoundInstance {
        @Override
        public Identifier getIdentifier() {
            return s.getIdentifier();
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager m) {
            return s.resolve(m);
        }

        @Override
        public Sound getSound() {
            return s.getSound();
        }

        @Override
        public SoundSource getSource() {
            return s.getSource();
        }

        @Override
        public boolean isLooping() {
            return s.isLooping();
        }

        @Override
        public boolean isRelative() {
            return s.isRelative();
        }

        @Override
        public int getDelay() {
            return s.getDelay();
        }

        @Override
        public float getVolume() {
            return s.getVolume() * k;
        }

        @Override
        public float getPitch() {
            return s.getPitch();
        }

        @Override
        public double getX() {
            return s.getX();
        }

        @Override
        public double getY() {
            return s.getY();
        }

        @Override
        public double getZ() {
            return s.getZ();
        }

        @Override
        public Attenuation getAttenuation() {
            return s.getAttenuation();
        }

        @Override
        public boolean canStartSilent() {
            return s.canStartSilent();
        }

        @Override
        public boolean canPlaySound() {
            return s.canPlaySound();
        }

        @Override
        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary b, Sound sound, boolean looping) {
            return s.getStream(b, sound, looping);
        }
    }
}
