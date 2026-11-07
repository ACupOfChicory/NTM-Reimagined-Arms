package acup.gunfeel.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiConsumer;

import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.mojang.authlib.GameProfile;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The AI check's recoil table: every NTM gun's recoil lambda (its first receiver's), as NTM runs it on the shooting
 * player's client, run here on the dedicated server for a fake player, {@value #SAMPLES} times each from a seed of the
 * gun's own; the means and spreads go in a table. It must be the one the jar ships (AiRecoil.TABLE): when NTM changes
 * its recoil the check fails, and the fresh table is next to the result file to copy in.
 */
final class AiCheckRecoil {
    static final int SAMPLES = 2000;

    private AiCheckRecoil() {
    }

    static void run(AiCheck check, ServerLevel level, Path fresh) {
        Map<String, AiRecoil.Kick> sampled = new TreeMap<>();
        List<String> failed = new ArrayList<>();
        int none = sample(level, sampled, failed);
        List<String> lines = new ArrayList<>();
        lines.add("# Each NTM gun's recoil per shot, NTM's units, sampled from its recoil lambda by the AI check (AiCheckRecoil):");
        lines.add("# gun, up, its spread, aside, its spread, the share of a kick NTM lets linger a tick, the share of the view back a tick");
        sampled.forEach((gun, k) -> lines.add(k.tsv(gun)));
        try {
            Files.createDirectories(fresh.getParent());
            Files.write(fresh, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            check.check("the fresh recoil table is written", false);
        }
        Map<String, AiRecoil.Kick> shipped = AiRecoil.table();
        boolean same = shipped.size() == sampled.size();
        for (var e : sampled.entrySet()) {
            AiRecoil.Kick s = shipped.get(e.getKey());
            same &= s != null && s.tsv(e.getKey()).equals(e.getValue().tsv(e.getKey()));
        }
        StringBuilder some = new StringBuilder();
        for (String gun : List.of("hbm:gun_carbine", "hbm:gun_g3", "hbm:gun_heavy_revolver", "hbm:gun_am180")) {
            AiRecoil.Kick k = sampled.get(gun);
            if (k != null) some.append(String.format(java.util.Locale.ROOT, " %s up %.2f±%.2f aside %.2f±%.2f back %.2f a tick;", gun.substring(4), k.up(),
                    k.upSpread(), k.side(), k.sideSpread(), k.rebound()));
        }
        check.out("     recoil: " + sampled.size() + " NTM guns kick, " + none + " do not" + (failed.isEmpty() ? "" : ", lambdas that failed here: " + failed)
                + ";" + some);
        check.check("the jar's AI recoil table is NTM's recoil now (else copy " + fresh.getFileName() + " into src/main/resources)", same);
    }

    /** every gun's kicks into out; the guns that give none */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static int sample(ServerLevel level, Map<String, AiRecoil.Kick> out, List<String> failed) {
        ServerPlayer p = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,
                new GameProfile(UUID.fromString("6a1b2c3d-0000-4000-8000-b2b2b2b2b2b2"), "armsai_recoil"));
        float v0 = ItemGunBaseNT.recoilVertical, h0 = ItemGunBaseNT.recoilHorizontal, d0 = ItemGunBaseNT.recoilDecay, r0 = ItemGunBaseNT.recoilRebound;
        int none = 0;
        try {
            for (Item item : BuiltInRegistries.ITEM) {
                if (!(item instanceof ItemGunBaseNT gun)) continue;
                String id = BuiltInRegistries.ITEM.getKey(item).toString();
                ItemStack stack = new ItemStack(item);
                try {
                    GunConfig cfg = gun.getConfig(stack, 0);
                    Receiver[] recs = cfg.getReceivers(stack);
                    BiConsumer<ItemStack, ItemGunBaseNT.LambdaContext> recoil = recs.length == 0 ? null : recs[0].getRecoil(stack);
                    if (recoil == null) {
                        none++;
                        continue;
                    }
                    p.setItemSlot(EquipmentSlot.MAINHAND, stack);
                    ItemGunBaseNT.LambdaContext ctx = new ItemGunBaseNT.LambdaContext(cfg, p, p.getInventory(), 0);
                    p.getRandom().setSeed(id.hashCode());
                    double up = 0, upSq = 0, side = 0, sideSq = 0;
                    float decay = 0.75F, rebound = 0.25F;
                    for (int i = 0; i < SAMPLES; i++) {
                        ItemGunBaseNT.recoilVertical = 0F;
                        ItemGunBaseNT.recoilHorizontal = 0F;
                        recoil.accept(stack, ctx);
                        double v = ItemGunBaseNT.recoilVertical, h = ItemGunBaseNT.recoilHorizontal;
                        up += v;
                        upSq += v * v;
                        side += h;
                        sideSq += h * h;
                        decay = ItemGunBaseNT.recoilDecay;
                        rebound = ItemGunBaseNT.recoilRebound;
                    }
                    up /= SAMPLES;
                    side /= SAMPLES;
                    float upSd = (float) Math.sqrt(Math.max(0, upSq / SAMPLES - up * up)), sideSd = (float) Math.sqrt(Math.max(0, sideSq / SAMPLES - side * side));
                    if (up == 0 && side == 0 && upSd == 0 && sideSd == 0) {
                        none++;
                        continue;
                    }
                    out.put(id, new AiRecoil.Kick((float) up, upSd, (float) side, sideSd, decay, rebound));
                } catch (Throwable t) {
                    failed.add(id + " (" + t.getClass().getSimpleName() + ")");
                }
            }
        } finally {
            p.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            ItemGunBaseNT.recoilVertical = v0;
            ItemGunBaseNT.recoilHorizontal = h0;
            ItemGunBaseNT.recoilDecay = d0;
            ItemGunBaseNT.recoilRebound = r0;
        }
        return none;
    }
}
