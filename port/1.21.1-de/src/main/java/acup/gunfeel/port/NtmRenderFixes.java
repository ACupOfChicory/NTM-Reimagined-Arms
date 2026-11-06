package acup.gunfeel.port;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.Config;
import acup.gunfeel.GunFeel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.fml.loading.FMLPaths;

// The port's own (Config.ntmRenderFixes): NTM DE's reinforced laminate and its pane drawn translucent, block and item.
// Their textures are half clear (alpha 103-255, none 0), but DE's block model asks for "minecraft:cutout" (copied from
// NTM 26.2, which takes the layer from the texture's alpha and never reads render_type), so the block is drawn opaque;
// the pane's item (item/generated) gets NeoForge's fallback, the cutout sheet, opaque too. Every model of theirs is
// wrapped, after DE's connected textures and item models (LOWEST), in one that asks for the translucent layer.
public final class NtmRenderFixes {
    private static final String[] IDS = {"reinforced_laminate", "reinforced_laminate_pane"};

    private NtmRenderFixes() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(EventPriority.LOWEST, ModelEvent.ModifyBakingResult.class, NtmRenderFixes::wrap);
    }

    private static void wrap(ModelEvent.ModifyBakingResult event) {
        if (!on()) {
            GunFeel.LOGGER.info("NTM render fixes off (ntmRenderFixes=false): the laminate drawn as NTM DE draws it");
            return;
        }
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        int wrapped = 0;
        for (String id : IDS) {
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("hbm", id);
            Block block = BuiltInRegistries.BLOCK.get(rl);
            if (block == Blocks.AIR) continue;
            List<ModelResourceLocation> keys = new ArrayList<>();
            for (BlockState state : block.getStateDefinition().getPossibleStates()) keys.add(BlockModelShaper.stateToModelLocation(state));
            keys.add(ModelResourceLocation.inventory(rl));
            keys.add(ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath("hbm", "item/" + id)));
            for (ModelResourceLocation key : keys) {
                BakedModel model = models.get(key);
                if (model == null || model instanceof Translucent) continue;
                models.put(key, new Translucent(model));
                wrapped++;
            }
        }
        GunFeel.LOGGER.info("NTM render fixes: {} laminate models drawn translucent", wrapped);
    }

    // the key as the file has it now: the models are first baked before the first frame reads the config (Config.poll)
    private static boolean on() {
        Path file = FMLPaths.CONFIGDIR.get().resolve("ntm-gunfeel.properties");
        if (!Files.exists(file)) return Config.ntmRenderFixes;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) {
            p.load(r);
        } catch (IOException | RuntimeException e) {
            return Config.ntmRenderFixes;
        }
        String v = p.getProperty("ntmRenderFixes");
        return v == null ? Config.ntmRenderFixes : Boolean.parseBoolean(v.trim());
    }

    static final class Translucent extends BakedModelWrapper<BakedModel> {
        private static final ChunkRenderTypeSet TRANSLUCENT = ChunkRenderTypeSet.of(RenderType.translucent());

        Translucent(BakedModel original) {
            super(original);
        }

        @Override
        public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
            return TRANSLUCENT;
        }

        // every quad of the original, whatever layer it asked for, in the translucent one
        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand, ModelData data, @Nullable RenderType renderType) {
            if (renderType == null) return super.getQuads(state, side, rand, data, null);
            if (renderType != RenderType.translucent()) return List.of();
            List<BakedQuad> out = new ArrayList<>();
            for (RenderType type : originalModel.getRenderTypes(state, rand, data))
                out.addAll(originalModel.getQuads(state, side, rand, data, type));
            return out;
        }

        @Override
        public List<RenderType> getRenderTypes(ItemStack stack, boolean fabulous) {
            return List.of(RenderTypeHelper.getEntityRenderType(RenderType.translucent(), fabulous));
        }

        @Override
        public List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) {
            List<BakedModel> passes = originalModel.getRenderPasses(stack, fabulous);
            List<BakedModel> out = new ArrayList<>(passes.size());
            for (BakedModel pass : passes) out.add(pass == originalModel || pass instanceof Translucent ? pass == originalModel ? this : pass : new Translucent(pass));
            return out;
        }

        @Override
        public BakedModel applyTransform(ItemDisplayContext context, PoseStack pose, boolean leftHand) {
            BakedModel moved = originalModel.applyTransform(context, pose, leftHand);
            return moved == originalModel ? this : moved instanceof Translucent ? moved : new Translucent(moved);
        }
    }
}
