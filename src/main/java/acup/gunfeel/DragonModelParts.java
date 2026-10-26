package acup.gunfeel;

import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.phys.Vec3;

// Сохраняем ванильного получателя урона: голова, шея, тело, три участка
// хвоста и два крыла. Меняется поверхность пересечения, а не правила босса.
public final class DragonModelParts {
    private DragonModelParts() {}
    static boolean belongs(EnderDragon dragon,EnderDragonPart part,ModelBox box,Vec3 center) {
        var parts=dragon.getSubEntities();
        String name=box.part(); int index=2;
        if(name.startsWith("root/head")) index=0;
        else if(name.startsWith("root/neck")) index=1;
        else if(name.startsWith("root/tail")) {
            String number=name.substring(9).split("/",2)[0];
            try { index=3+Math.min(2,Integer.parseInt(number)/4); } catch(NumberFormatException ignored) {}
        } else if(name.contains("wing")) index=center.distanceToSqr(parts[6].position())<=center.distanceToSqr(parts[7].position())?6:7;
        return part==parts[index];
    }
}
