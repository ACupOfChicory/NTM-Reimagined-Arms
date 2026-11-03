package acup.gunfeel;

// port: copy of 26.2 src/main/java/acup/gunfeel/DragonModelParts.java at 0762086; changes marked "port:"

import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.EntityDragon;
import acup.gunfeel.phys.Vec3;

// Сохраняем ванильного получателя урона: голова, шея, тело, три участка
// хвоста и два крыла. Меняется поверхность пересечения, а не правила босса.
// port: 1.7.10's dragon has seven parts, no neck: head, body, tail x3, wing x2 (EntityDragon.dragonPartArray). The neck
// goes to the body, which takes the same reduced damage a 26.2 neck does (attackEntityFromPart: all but the head).
// The part names come from ModelNames (ModelDragon): root/head..., root/neck<i>, root/body, root/tail<i>, *wing*.
public final class DragonModelParts {
    private DragonModelParts() {}
    static boolean belongs(EntityDragon dragon,Entity part,ModelBox box,Vec3 center) {
        var parts=dragon.dragonPartArray;
        String name=box.part(); int index=1;
        if(name.startsWith("root/head")) index=0;
        else if(name.startsWith("root/neck")) index=1;
        else if(name.startsWith("root/tail")) {
            String number=name.substring(9).split("/",2)[0];
            try { index=2+Math.min(2,Integer.parseInt(number)/4); } catch(NumberFormatException ignored) {}
        } else if(name.contains("wing")) index=distance(center,parts[5])<=distance(center,parts[6])?5:6;
        return part==parts[index];
    }
    private static double distance(Vec3 p,Entity e) {
        double dx=p.x-e.posX,dy=p.y-e.posY,dz=p.z-e.posZ;return dx*dx+dy*dy+dz*dz;
    }
}
