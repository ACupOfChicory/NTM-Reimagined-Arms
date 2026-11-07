package acup.gunfeel.gl54;

import com.hbm.client.model.Meshes;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.anim.HbmAnimations;
import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;
import com.hbm.render.item.weapon.sedna.RestBody;
import com.hbm.render.loader.HFRWavefrontObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;

// The GL-54 in the hand: every part of the model (models/weapons/gl54.hbm_mesh) drawn on its own bus, the bus named as the
// part (HbmAnimations.applyRelevantTransformation: nothing for a part with no animation), so a new model with other parts
// or other moving ones needs no change here. The placing is Congo Lake's with the sights lined up.
public final class ItemRenderGl54 extends ItemRenderWeaponBase {
    public static final Identifier MESH = Identifier.fromNamespaceAndPath("ntmgunfeel", "models/weapons/gl54.obj");
    public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("ntmgunfeel", "textures/models/weapons/gl54.png");
    // the part that gives the muzzle (its far end); without it the whole model's
    public static final String BARREL = "TitaniumBarrel";

    // Where the gun is held at the hip (before NTM's 0.8) and aimed; the stand tries others (Gl54Stand)
    public static float hipX = -1.5F, hipY = -2.2F, hipZ = 1.25F;
    // Aimed: the eye looks along the sight's axis, through the middle of the reflex pane (ReflexDraw.DOT_U/V, 0.5 each) to the
    // middle of the screen, level with the bore, 0.6 blocks of the hand's space (1.2 model units) from the pane. The dot is on that
    // line, so the housing's opening is centred on the screen and the gun's body is down by the opening's lower edge (before this
    // the eye looked through the top of the pane and the body filled most of the glass). Solved by the stand (Gl54ReflexStand).
    public static float aimX = 0.0F, aimY = -1.05F, aimZ = 0.3373F;

    final HFRWavefrontObject mesh;
    final String[] names;
    private final RenderType body = RenderTypes.entityCutout(TEXTURE);
    // the muzzle in the model
    final float muzzleY, muzzleZ;

    public ItemRenderGl54() {
        mesh = Meshes.load(MESH);
        names = new String[mesh.groups.length];
        int[] all = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = mesh.groups[i].name;
            all[i] = i;
        }
        int barrel = -1;
        for (int i = 0; i < names.length; i++) if (names[i].equalsIgnoreCase(BARREL)) barrel = i;
        float[] b = mesh.boundsOfParts(barrel >= 0 ? new int[] {barrel} : all);
        muzzleY = (b[1] + b[4]) / 2F;
        muzzleZ = b[5];
    }

    @Override
    public void setupFirstPerson(ItemStack stack, PoseStack pose) {
        pose.translate(0.0, 0.0, 0.875);
        float offset = 0.8F;
        standardAimingTransform(stack, pose, hipX * offset, hipY * offset, hipZ * offset, aimX, aimY, aimZ);
    }

    @Override
    public void renderFirstPerson(ItemStack stack, PoseStack pose, SubmitNodeCollector collector, int light) {
        ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
        pose.scale(0.5F, 0.5F, 0.5F);
        HbmAnimations.applyRelevantTransformation(pose, "Gun");
        if (acup.gunfeel.Gl54Probe.ON) acup.gunfeel.Gl54Probe.run(pose, mesh, names);
        for (int i = 0; i < names.length; i++) {
            pose.pushPose();
            HbmAnimations.applyRelevantTransformation(pose, names[i]);
            submitPart(collector, pose, body, mesh, i, light);
            pose.popPose();
        }
        ReflexDraw.render(collector, pose, stack);
        Gl54Laser.render(collector, pose, stack);
        double smokeScale = 0.25;
        pose.pushPose();
        pose.translate(0.0, muzzleY, muzzleZ);
        double[] transform = HbmAnimations.getRelevantTransformation("Gun");
        pose.mulPose(Axis.ZN.rotationDegrees((float) transform[5]));
        pose.mulPose(Axis.YN.rotationDegrees((float) transform[4]));
        pose.mulPose(Axis.XN.rotationDegrees((float) transform[3]));
        pose.mulPose(Axis.YP.rotationDegrees(90.0F));
        pose.scale((float) smokeScale, (float) smokeScale, (float) smokeScale);
        renderSmokeNodes(collector, pose, gun.getConfig(stack, 0).smokeNodes, 1.0, light);
        pose.popPose();
        pose.pushPose();
        pose.translate(0.0, muzzleY, muzzleZ);
        pose.mulPose(Axis.YP.rotationDegrees(90.0F));
        pose.mulPose(Axis.XP.rotationDegrees((float) (90.0 * gun.shotRand)));
        pose.scale(0.5F, 0.5F, 0.5F);
        renderMuzzleFlash(collector, pose, gun.lastShot[0], 150, 7.5);
        pose.popPose();
    }

    // the gun in the world (carried, on the ground, in a frame): the same, with the laser of an aimed gun added
    @Override
    public void submitRestBody(ItemStack stack, PoseStack pose, SubmitNodeCollector collector, int light) {
        super.submitRestBody(stack, pose, collector, light);
        if (!ItemGunBaseNT.getIsAiming(stack)) return;
        float offset = 0.8F;
        pose.pushPose();
        pose.mulPose(restSetup(0.875F, hipX * offset, hipY * offset, hipZ * offset).scale(0.5F));
        Gl54Laser.render(collector, pose, stack);
        pose.popPose();
    }

    @Override
    public void restBody(ItemStack stack, RestBody body) {
        float offset = 0.8F;
        Matrix4f m = restSetup(0.875F, hipX * offset, hipY * offset, hipZ * offset).scale(0.5F);
        for (int i = 0; i < names.length; i++) body.part(m, mesh, i, TEXTURE);
    }
}
