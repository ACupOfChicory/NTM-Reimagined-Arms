package acup.gunfeel;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.ISoundEventListener;
import net.minecraft.client.audio.SoundEventAccessor;
import net.minecraft.entity.Entity;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

// Dev (runClient -PsoundRecord): every sound the game starts, to logs/sounds.tsv, for the sound stand (SoundStand on the
// dev server, which the client joins). A row per sound as it starts: the event and its file, where it is, its volume
// and pitch, the listener's ears and look, the distance, how far 1.12.2 fades it (16 blocks, times the volume above 1),
// the loudness that leaves (the volume up to 1 times 1 - distance / fade, as the sound system's linear fade) and its
// side (pan: +1 right, -1 left; front; up). A row as it stops: how many ticks it played (the sound system asked every
// tick), so a sound cut short by another taking its channel shows. The stand's chat lines are marks in the same file;
// "trigger 1|0" holds or lets go of the trigger, as the player's own mouse does (ShootScenario's path), "done" closes.
public final class SoundRecord implements ISoundEventListener {
    public static final String PROPERTY = "ntmgunfeel.soundRecord";
    private static final String MARK = "[soundstand] ";

    private static final class Row {
        final int id, tick;
        final ISound sound;
        String channel;
        int ticks;

        Row(int id, int tick, ISound sound) {
            this.id = id;
            this.tick = tick;
            this.sound = sound;
        }
    }

    private final PrintWriter out;
    private final List<Row> open = new ArrayList<Row>();
    private int next, tick;
    private boolean listening;
    private java.lang.reflect.Field system;

    private SoundRecord(PrintWriter out) {
        this.out = out;
    }

    static void registerIfAsked() {
        String file = System.getProperty(PROPERTY);
        if (file == null) return;
        try {
            File f = new File(file);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            PrintWriter w = new PrintWriter(f, StandardCharsets.UTF_8.name());
            w.println("kind\tid\ttick\twtime\tevent\tfile\tcategory\tvolume\tpitch\tx\ty\tz\tdist\tfade\tgain\tpan\tfront\tup\tear\tyaw\tplayed");
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new SoundRecord(w));
            GunFeel.LOGGER.info("Sound record to {}", f.getAbsolutePath());
        } catch (java.io.IOException e) {
            GunFeel.LOGGER.error("Sound record: {}", file, e);
        }
    }

    private static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private long wtime() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.world == null ? -1 : mc.world.getTotalWorldTime();
    }

    @Override
    public void soundPlay(ISound s, SoundEventAccessor accessor) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity ear = mc.getRenderViewEntity();
        double ex = 0, ey = 0, ez = 0, yaw = 0;
        if (ear != null) {
            ex = ear.posX;
            ey = ear.posY + ear.getEyeHeight();
            ez = ear.posZ;
            yaw = ear.rotationYaw;
        }
        double dx = s.getXPosF() - ex, dy = s.getYPosF() - ey, dz = s.getZPosF() - ez;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float vol = s.getVolume();
        double fade = 16.0 * Math.max(1F, vol);
        boolean att = s.getAttenuationType() == ISound.AttenuationType.LINEAR;
        double gain = Math.min(1F, Math.max(0F, vol * mc.gameSettings.getSoundLevel(s.getCategory())))
                * (att ? Math.max(0.0, 1.0 - d / fade) : 1.0);
        double r = Math.toRadians(yaw);
        double pan = 0, front = 0, up = 0;
        if (d > 1e-6) {
            // the look along yaw is (-sin, 0, cos), its right hand (-cos, 0, -sin)
            pan = (dx * -Math.cos(r) + dz * -Math.sin(r)) / d;
            front = (dx * -Math.sin(r) + dz * Math.cos(r)) / d;
            up = dy / d;
        }
        Row row = new Row(next++, tick, s);
        open.add(row);
        out.println("play\t" + row.id + "\t" + tick + "\t" + wtime() + "\t" + s.getSoundLocation() + "\t"
                + (s.getSound() == null ? "?" : s.getSound().getSoundLocation()) + "\t" + s.getCategory().getName() + "\t" + f(vol)
                + "\t" + f(s.getPitch()) + "\t" + f(s.getXPosF()) + "\t" + f(s.getYPosF()) + "\t" + f(s.getZPosF()) + "\t" + f(d) + "\t"
                + (att ? f(fade) : "none") + "\t" + f(gain) + "\t" + f(pan) + "\t" + f(front) + "\t" + f(up) + "\t"
                + f(ex) + "," + f(ey) + "," + f(ez) + "\t" + f(yaw) + "\t");
    }

    private paulscode.sound.SoundSystem system(Minecraft mc) {
        try {
            if (system == null) system = net.minecraftforge.fml.relauncher.ReflectionHelper.findField(net.minecraft.client.audio.SoundManager.class, "sndSystem", "field_148620_e");
            return (paulscode.sound.SoundSystem) system.get(mc.getSoundHandler().sndManager);
        } catch (Exception e) {
            return null;
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!listening && mc.getSoundHandler() != null) {
            listening = true;
            mc.getSoundHandler().addListener(this);
            out.println("mark\t-1\t" + tick + "\t-1\tchannels " + paulscode.sound.SoundSystemConfig.getNumberNormalChannels() + " normal, "
                    + paulscode.sound.SoundSystemConfig.getNumberStreamingChannels() + " streaming");
        }
        tick++;
        paulscode.sound.SoundSystem sys = system(mc);
        for (Iterator<Row> it = open.iterator(); it.hasNext();) {
            Row row = it.next();
            if (row.channel == null) row.channel = mc.getSoundHandler().sndManager.invPlayingSounds.get(row.sound);
            boolean playing = row.channel != null && sys != null && sys.playing(row.channel);
            if (playing) row.ticks++;
            if (!playing && tick - row.tick > 2 || tick - row.tick > 400) {
                out.println("end\t" + row.id + "\t" + tick + "\t" + wtime() + "\t" + row.sound.getSoundLocation() + "\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t\t" + row.ticks
                        + (row.channel == null ? " nochannel" : ""));
                it.remove();
            }
        }
        if (mc.world != null && mc.world.getTotalWorldTime() % 100 == 0) out.flush();
    }

    // the stand's server gone (done or failed): the record ends and the client closes, not left on the lost-connection screen
    @SubscribeEvent
    public void gone(net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            out.println("mark\t-1\t" + tick + "\t-1\tdisconnected");
            out.close();
            mc.shutdown();
        });
    }

    @SubscribeEvent
    public void chat(ClientChatReceivedEvent e) {
        String text = e.getMessage().getUnformattedText();
        if (!text.startsWith(MARK)) return;
        String what = text.substring(MARK.length());
        Minecraft mc = Minecraft.getMinecraft();
        out.println("mark\t-1\t" + tick + "\t" + wtime() + "\t" + what.replace('\t', ' ') + "\t\t\t\t\t\t\t\t\t\t\t\t\t\t"
                + (mc.player == null ? "" : f(mc.player.posX) + "," + f(mc.player.posY + mc.player.getEyeHeight()) + "," + f(mc.player.posZ)) + "\t"
                + (mc.player == null ? "" : f(mc.player.rotationYaw) + "/" + f(mc.player.rotationPitch)));
        out.flush();
        if (what.equals("trigger 1")) ShootScenario.trigger(true, mc);
        else if (what.equals("trigger 0")) ShootScenario.trigger(false, mc);
        else if (what.equals("done")) {
            out.flush();
            out.close();
            mc.addScheduledTask(mc::shutdown);
        }
    }
}
