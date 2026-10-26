package acup.gunfeel;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.sheep.Sheep;

// Серверная стрижка выключает уже принятую шерсть сразу, не ожидая
// следующего пакета позы клиента. Тело остаётся точным.
public final class ModelWool {
    public static final String SUFFIX="/wool";
    private ModelWool() {}
    public static boolean isWool(ModelBox box) { return box.part().endsWith(SUFFIX); }
    public static boolean visible(Entity entity,ModelBox box) {
        return !isWool(box) || !(entity instanceof Sheep sheep) || !sheep.isSheared();
    }
}
