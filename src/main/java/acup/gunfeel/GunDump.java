package acup.gunfeel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

// Every NTM gun with every ammo its receivers take, as the game has them, written next to the block dump (guns.tsv) in
// the same dev run (BlockDump). The tests work the ammo menu numbers (AmmoStats) out of the copy in src/test/gundata/,
// and the spread columns are what the spread settings multiply.
public final class GunDump {
    static final List<String> COLUMNS = List.of("gun", "config", "receiver", "ammo", "baseDamage", "split", "damageMult",
            "projectilesMin", "projectilesMax", "headshotMult", "thresholdNegation", "piercing", "spreadInnate", "spreadAmmoMult",
            "ammoSpread", "spreadHipfire", "spreadDurability", "velocity", "delayAfterFire", "roundsPerCycle", "auto", "durability",
            "type");

    private GunDump() {
    }

    static void write(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (!(item instanceof ItemGunBaseNT gun)) continue;
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            ItemStack stack = new ItemStack(item);
            for (int c = 0; c < gun.getConfigCount(); c++) {
                GunConfig cfg = gun.getConfig(stack, c);
                Receiver[] recs = cfg.getReceivers(stack);
                for (int r = 0; r < recs.length; r++) {
                    Receiver rec = recs[r];
                    for (BulletConfig b : AmmoLock.accepted(rec.getMagazine(stack))) {
                        List<String> f = new ArrayList<>();
                        f.add(id);
                        f.add(Integer.toString(c));
                        f.add(Integer.toString(r));
                        f.add(BuiltInRegistries.ITEM.getKey(b.ammoStack().getItem()).toString());
                        f.add(num(rec.getBaseDamage(stack)));
                        f.add(num(rec.getSplitProjectiles(stack)));
                        f.add(num(b.damageMult));
                        f.add(Integer.toString(b.projectilesMin));
                        f.add(Integer.toString(b.projectilesMax));
                        f.add(num(b.headshotMult));
                        f.add(num(b.armorThresholdNegation));
                        f.add(num(b.armorPiercingPercent));
                        f.add(num(rec.getInnateSpread(stack)));
                        f.add(num(rec.getAmmoSpread(stack)));
                        f.add(num(b.spread));
                        f.add(num(rec.getHipfireSpread(stack)));
                        f.add(num(rec.getDurabilitySpread(stack)));
                        f.add(num(b.velocity));
                        f.add(Integer.toString(rec.getDelayAfterFire(stack)));
                        f.add(Integer.toString(rec.getRoundsPerCycle(stack)));
                        f.add(Boolean.toString(rec.getRefireOnHold(stack)));
                        f.add(num(cfg.getDurability(stack)));
                        f.add(b.pType.name());
                        lines.add(String.join("\t", f));
                    }
                }
            }
        }
        lines.sort(null);
        List<String> out = new ArrayList<>();
        out.add(String.join("\t", COLUMNS));
        out.addAll(lines);
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.write(file, out, StandardCharsets.UTF_8);
    }

    private static String num(float v) {
        return BlockFacts.num(v);
    }
}
