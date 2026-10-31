package acup.gunfeel;

// port: copy of 26.2 src/main/java/acup/gunfeel/ModelWool.java at 0762086; changes marked "port:"

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntitySheep;

// Серверная стрижка выключает уже принятую шерсть сразу, не ожидая
// следующего пакета позы клиента. Тело остаётся точным.
public final class ModelWool {
    public static final String SUFFIX="/wool";
    private ModelWool() {}
    public static boolean isWool(ModelBox box) { return box.part().endsWith(SUFFIX); }
    public static boolean visible(Entity entity,ModelBox box) {
        return !isWool(box) || !(entity instanceof EntitySheep sheep) || !sheep.getSheared() /* port */;
    }
}
