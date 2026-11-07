package acup.gunfeel;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

// port only: NTM CE (2.6.1.0) presses a player's key (KeybindPacket$Handler.onMessage -> HbmKeybindsServer.onPressedServer
// -> the gun's handleKeybind: the shot, the gun's NBT) right on the network thread the packet came in on. The server's
// own thread meanwhile copies the same NBT for the equipment packet, and now and then that copy throws
// (ConcurrentModificationException in NBTTagCompound.copy, "Ticking player": the server stops). The press goes to the
// server's thread instead (MixinKeysOnServerThread), after what came before it on that connection: vanilla queues its
// move and turn packets there too, so a shot comes after the turn sent just before it (ClickAim).
// Off by itself once NTM does it: the mixin is put in only while onMessage calls onPressedServer itself and queues
// nothing (needed), and a press already on the server's thread runs at once.
public final class ServerKeys {
    static final String HANDLER = "com.hbm.packet.KeybindPacket$Handler";
    private static final String MESSAGE = "(Lcom/hbm/packet/KeybindPacket;Lnet/minecraftforge/fml/common/network/simpleimpl/MessageContext;)Lnet/minecraftforge/fml/common/network/simpleimpl/IMessage;";
    // whether the mixin went in (LateMixins); MixinCheck expects its handler only then
    public static volatile boolean mixed;
    private static volatile boolean onThreadTold;

    private ServerKeys() {
    }

    public static void press(EntityPlayer player, Runnable press) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server != null && server.isCallingFromMinecraftThread() && !onThreadTold) {
            onThreadTold = true;
            GunFeel.LOGGER.info("NTM's keys come on the server's thread already: they are pressed there at once");
        }
        run(player, press);
    }

    // a player's packet that changes their gun, on the server's thread (at once when it is on it already), in order
    // with the keys pressed there: the mod's own (AmmoSelectPayload, AmmoLockPayload) whether or not NTM's keys move
    public static void run(EntityPlayer player, Runnable task) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || server.isCallingFromMinecraftThread()) {
            task.run();
            return;
        }
        server.addScheduledTask(() -> {
            // a player gone meanwhile does nothing
            if (player instanceof EntityPlayerMP && server.getPlayerList().getPlayerByUUID(player.getUniqueID()) != player) return;
            task.run();
        });
    }

    // NTM's handler as it is in the jar at hand: true while it presses the key itself (onPressedServer in onMessage)
    // and hands nothing on (no task queued, no lambda): then the press is moved. Unreadable: left as NTM has it
    static boolean needed(ClassNode handler) {
        if (handler == null || handler.methods == null) return false;
        boolean presses = false;
        for (MethodNode m : handler.methods) {
            if (!m.name.equals("onMessage") || !m.desc.equals(MESSAGE) || m.instructions == null) continue;
            for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
                if (i instanceof InvokeDynamicInsnNode) return false;
                if (!(i instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) i;
                // IThreadListener.addScheduledTask, by its MCP or SRG name
                if (call.name.equals("addScheduledTask") || call.name.equals("func_152344_a")) return false;
                if (call.owner.equals("com/hbm/handler/HbmKeybindsServer") && call.name.equals("onPressedServer")) presses = true;
            }
        }
        return presses;
    }

    // read as in its jar (NtmCheck.read: not loaded, not transformed); -Dntmgunfeel.serverKeys=false leaves the fix out
    // (the stand's comparison)
    static boolean needed() {
        if ("false".equals(System.getProperty("ntmgunfeel.serverKeys"))) {
            GunFeel.LOGGER.info("MixinKeysOnServerThread left out (-Dntmgunfeel.serverKeys=false)");
            return false;
        }
        ClassNode handler = acup.gunfeel.compat.NtmCheck.read(ServerKeys.class.getClassLoader(), HANDLER.replace('.', '/'));
        if (handler == null) GunFeel.LOGGER.warn("Could not read NTM's {}: its keys stay on the network thread", HANDLER);
        return needed(handler);
    }
}
