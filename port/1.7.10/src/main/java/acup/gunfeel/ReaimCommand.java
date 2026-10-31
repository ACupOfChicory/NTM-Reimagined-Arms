package acup.gunfeel;

import java.util.List;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

// port: copied from 26.2 (ReaimCommand.java, e284339) as a 1.7.10 CommandBase instead of brigadier; the gun's id is in
// 26.2's form (Spread.id), the settings' keys.
// /gunaim                       how early the held aim comes back on the gun in hand
// /gunaim reload|inspect <sec>  set it (seconds before the reload / inspect ends)
public final class ReaimCommand extends CommandBase {
    @Override
    public String getCommandName() {
        return "gunaim";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/gunaim [reload|inspect <0..5>]";
    }

    // 26.2: like brigadier's default (level 2)
    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List addTabCompletionOptions(ICommandSender sender, String[] args) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, "reload", "inspect") : null;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String gun = gun(sender);
        if (gun == null) return;
        if (args.length == 0) {
            sender.addChatMessage(new ChatComponentText(gun + ": прицел возвращается за " + ReaimTimes.seconds(gun, "reload")
                    + " с до конца перезарядки, за " + ReaimTimes.seconds(gun, "inspect") + " с до конца осмотра"));
            return;
        }
        if (args.length != 2 || !(args[0].equals("reload") || args[0].equals("inspect"))) {
            fail(sender, getCommandUsage(sender));
            return;
        }
        float seconds;
        try {
            seconds = Float.parseFloat(args[1]);
        } catch (NumberFormatException e) {
            fail(sender, getCommandUsage(sender));
            return;
        }
        if (!(seconds >= 0F && seconds <= 5F)) {
            fail(sender, getCommandUsage(sender));
            return;
        }
        String kind = args[0];
        ReaimTimes.set(gun, kind, seconds);
        sender.addChatMessage(new ChatComponentText(gun + ": " + (kind.equals("reload") ? "перезарядка" : "осмотр") + " — прицел за " + seconds + " с до конца"));
    }

    private static String gun(ICommandSender sender) {
        ItemStack stack = sender instanceof EntityPlayerMP ? ((EntityPlayerMP) sender).getHeldItem() : null;
        if (stack == null || !(stack.getItem() instanceof ItemGunBaseNT)) {
            fail(sender, "Возьми пушку NTM в руку");
            return null;
        }
        return Spread.id(stack.getItem());
    }

    private static void fail(ICommandSender sender, String text) {
        sender.addChatMessage(new ChatComponentText(text).setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED)));
    }
}
