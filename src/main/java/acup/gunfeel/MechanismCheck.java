package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.hbm.inventory.material.MaterialShapes;
import com.hbm.inventory.material.Mats;
import com.hbm.inventory.material.NTMMaterial;
import com.hbm.inventory.recipes.CrucibleRecipes;
import com.hbm.items.CreativeContents;
import com.hbm.items.special.Autogen;
import com.hbm.items.special.MaterialShapeItem;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// The new gun mechanisms (GunMechanisms) on a dedicated server: ./gradlew runCheckMechanisms -PnoAssets. The five items
// are NTM's own kind under NTM's names, in their tags, cast in the mechanism mold (the casting list JEI shows), melted
// back to 288 units of their material, and in NTM's parts tab after saturnite's. Then the recipes, after a /reload for
// each pair of gunMechanismRecipes and balanceRecipes: of each recipe's files exactly the one of the settings is
// loaded, it takes the new mechanism (on) or NTM's (off), and the gun has as many recipes as with the first pair.
// One line per check goes to the file, and the server stops (exit 1 if a check failed).
public final class MechanismCheck {
    public static final String PROPERTY = "ntmgunfeel.checkMechanisms";
    private static final List<String> LINES = new ArrayList<>();
    private static final List<Runnable> STEPS = new ArrayList<>();
    private static boolean ok = true;
    private static MinecraftServer server;
    private static java.util.concurrent.CompletableFuture<Void> reload;
    private static int skipTo;
    private static final Map<String, Long> MAKING = new HashMap<>();

    private MechanismCheck() {
    }

    public static void registerIfAsked() {
        String file = System.getProperty(PROPERTY);
        if (file == null || file.isBlank()) return;
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, started -> {
            server = started.getServer();
            try {
                items();
                for (boolean[] p : new boolean[][] {{true, true}, {false, true}, {false, false}, {true, false}, {true, true}}) plan(p[0], p[1]);
            } catch (Throwable t) {
                fail(t);
                BulletCheck.finish(file, LINES, 1);
            }
            int[] at = {0};
            NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> {
                if (at[0] < 0) return;
                try {
                    if (skipTo > at[0]) at[0] = Math.min(skipTo, STEPS.size());
                    if (at[0] < STEPS.size()) {
                        STEPS.get(at[0]++).run();
                        return;
                    }
                    at[0] = -1;
                    keys(null, null);
                    LINES.add(ok ? "ALL OK" : "SOME FAILED");
                    BulletCheck.finish(file, LINES, ok ? 0 : 1);
                } catch (Throwable t) {
                    at[0] = -1;
                    fail(t);
                    keys(null, null);
                    BulletCheck.finish(file, LINES, 1);
                }
            });
        });
    }

    private static void items() {
        List<Identifier> parts = new ArrayList<>();
        CreativeContents.accept(CreativeContents.Tab.PARTS, s -> parts.add(BuiltInRegistries.ITEM.getKey(s.getItem())));
        int anchor = parts.indexOf(id("hbm", "part_mechanism_saturnite"));
        var casts = CrucibleRecipes.moldRecipes();
        Item mold = BuiltInRegistries.ITEM.getValue(id("hbm", "mold_mechanism"));
        int i = 0;
        for (NTMMaterial mat : GunMechanisms.materials()) {
            Identifier name = id("hbm", "part_mechanism_" + mat.tagPath);
            Item item = BuiltInRegistries.ITEM.getValue(name);
            check(item != Items.AIR && item instanceof MaterialShapeItem && Autogen.find(MaterialShapes.MECHANISM, mat) == item, name + ": предмет NTM (MaterialShapeItem, Autogen)");
            ItemStack stack = new ItemStack(item);
            check(stack.is(mat.tag(MaterialShapes.MECHANISM)), name + ": в теге " + mat.tag(MaterialShapes.MECHANISM).location());
            long cast = casts.stream().filter(c -> c.result().is(item) && c.mold().is(mold)).count();
            check(cast == 1, name + ": отливка в форме механизма, рецептов " + cast);
            var melt = Mats.getMaterialsFromItem(stack);
            check(melt.size() == 1 && melt.get(0).material == mat && melt.get(0).amount == 288, name + ": переплавка " + describe(melt));
            int at = parts.indexOf(name);
            check(anchor >= 0 && at == anchor + 1 + i, name + ": во вкладке частей на месте " + at + " (механизм сатурнита " + anchor + ")");
            i++;
        }
        for (String n : List.of("gun_metal", "weapon_steel", "saturnite"))
            check(BuiltInRegistries.ITEM.getValue(id("hbm", "part_mechanism_" + n)) != Items.AIR, "hbm:part_mechanism_" + n + " NTM на месте");
    }

    private static String describe(List<Mats.MaterialStack> melt) {
        StringBuilder s = new StringBuilder();
        for (var m : melt) s.append(m.material.tagPath).append(' ').append(m.amount).append(' ');
        return s.toString().trim();
    }

    private static void plan(boolean mech, boolean balance) {
        STEPS.add(() -> {
            keys(mech, balance);
            ReaimTimes.inFile("gunMechanismRecipes"); // the file read now, before the data packs ask
        });
        for (int i = 0; i < 30; i++) STEPS.add(() -> {
        });
        STEPS.add(() -> reload = server.reloadResources(server.getPackRepository().getSelectedIds()));
        int[] after = {0};
        for (int i = 0; i < 1200; i++) STEPS.add(() -> {
            if (reload != null && reload.isDone()) skipTo = Math.max(skipTo, after[0]);
        });
        after[0] = STEPS.size();
        STEPS.add(() -> recipes(mech, balance));
    }

    private static void recipes(boolean mech, boolean balance) {
        String pair = "gunMechanismRecipes = " + mech + ", balanceRecipes = " + balance;
        check(reload != null && reload.isDone() && !reload.isCompletedExceptionally(), "/reload, " + pair);
        check(GunMechanismRecipes.on() == mech && BalanceRecipes.on() == balance, "настройки прочитаны: " + pair);
        var manager = server.getRecipeManager();
        int right = 0;
        for (var e : GunMechanismRecipes.RECIPES.entrySet()) {
            String name = e.getKey();
            var swap = e.getValue();
            List<Identifier> files = new ArrayList<>(List.of(id("hbm", name), id("ntmgunfeel", "ntm/" + name)));
            Identifier want = mech ? files.get(0) : files.get(1);
            String toName = swap.to();
            if (GunMechanismRecipes.BALANCE.containsKey(name)) {
                if (balance) toName = GunMechanismRecipes.BALANCE.get(name);
                files.add(id("ntmgunfeel", "balance/" + name));
                files.add(id("ntmgunfeel", "mechanisms/" + name));
                want = balance ? (mech ? files.get(0) : files.get(2)) : (mech ? files.get(3) : files.get(1));
            }
            List<Identifier> loaded = new ArrayList<>();
            Recipe<?> held = null;
            for (Identifier f : files) {
                var r = manager.byKey(ResourceKey.create(Registries.RECIPE, f));
                if (r.isPresent()) {
                    loaded.add(f);
                    if (f.equals(want)) held = r.get().value();
                }
            }
            Item to = BuiltInRegistries.ITEM.getValue(id("hbm", "part_mechanism_" + toName));
            Item from = BuiltInRegistries.ITEM.getValue(id("hbm", "part_mechanism_" + swap.from()));
            boolean takesTo = held != null && takes(held, to), takesFrom = held != null && takes(held, from);
            Item result = held == null ? Items.AIR : result(held);
            long making = manager.getRecipes().stream().filter(h -> makes(h.value(), result)).count();
            long before = MAKING.computeIfAbsent(name, k -> making);
            boolean pass = loaded.equals(List.of(want)) && (mech ? takesTo && !takesFrom : takesFrom && !takesTo) && result != Items.AIR && making == before;
            if (pass) right++;
            else check(false, name + " (" + pair + "): загружены " + loaded + ", нужен " + want + ", берёт " + toName + " " + takesTo + ", " + swap.from() + " "
                    + takesFrom + ", рецептов предмета " + making + " (было " + before + ")");
        }
        check(right == GunMechanismRecipes.RECIPES.size(), "рецепты при " + pair + ": верны " + right + " из " + GunMechanismRecipes.RECIPES.size());
    }

    private static boolean takes(Recipe<?> recipe, Item item) {
        for (var ing : recipe.placementInfo().ingredients()) if (ing.items().anyMatch(h -> h.value() == item)) return true;
        return false;
    }

    private static Item result(Recipe<?> recipe) {
        for (var d : recipe.display()) {
            var stacks = d.result().resolveForStacks(net.minecraft.world.item.crafting.display.SlotDisplayContext.fromLevel(server.overworld()));
            if (!stacks.isEmpty()) return stacks.getFirst().getItem();
        }
        return Items.AIR;
    }

    private static boolean makes(Recipe<?> recipe, Item item) {
        try {
            return result(recipe) == item;
        } catch (RuntimeException e) {
            // a recipe that cannot show itself without a level of its own: not one of ours
            return false;
        }
    }

    private static Identifier id(String ns, String path) {
        return Identifier.fromNamespaceAndPath(ns, path);
    }

    // the server's settings file with these two keys (null, null: removed)
    private static void keys(Boolean mech, Boolean balance) {
        Path f = FMLPaths.CONFIGDIR.get().resolve(ReaimTimes.FILE);
        try {
            if (mech == null) Files.deleteIfExists(f);
            else Files.writeString(f, "gunMechanismRecipes = " + mech + "\nbalanceRecipes = " + balance + "\n");
            if (mech != null) Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void check(boolean pass, String what) {
        if (!pass) ok = false;
        LINES.add((pass ? "OK" : "FAIL") + "\t" + what);
    }

    private static void fail(Throwable t) {
        ok = false;
        LINES.add("FAIL\tcrashed: " + t);
        for (StackTraceElement s : t.getStackTrace()) LINES.add("    at " + s);
    }
}
