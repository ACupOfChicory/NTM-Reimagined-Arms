package acup.gunfeel.gl54;

import java.util.List;

// One swap of the magazine, worked out on plain lists (tested without the game; GrenadeMagazine applies it to the
// inventory): the fullest magazine of the wanted kind goes into the gun, the barrel's grenade stays (8 + 1), an empty gun
// is fed its first; the old magazine pours into the fullest unfinished one of its kind and what does not fit comes back
// as an item of its own; an empty one comes back empty; a gun that had no magazine in gives nothing back.
public record MagSwap(GunMag gun, int taken, int pourSlot, List<String> pourContents, List<String> back) {
    // null: nothing to swap (no magazine of the wanted kind, or it would not hold more than the one in the gun)
    public static MagSwap of(GunMag gun, List<List<String>> mags, String wanted, boolean locked) {
        int slot = MagLogic.fullest(mags, wanted);
        if (slot < 0 || !MagLogic.improves(gun.kind(), gun.mag().size(), wanted, mags.get(slot).size())) return null;
        List<String> take = mags.get(slot);
        GunMag next = gun.withMagazine(take, locked && !gun.sel().isEmpty() ? gun.sel() : wanted);
        if (!gun.inserted()) return new MagSwap(next, slot, -1, null, null);
        // the taken one is out of the inventory: it is neither a pour target nor there to be counted
        List<List<String>> left = new java.util.ArrayList<>(mags);
        left.set(slot, null);
        List<String> old = gun.mag();
        int target = MagLogic.pourTarget(left, MagLogic.kind(old));
        if (target < 0) return new MagSwap(next, slot, -1, null, old);
        MagLogic.Poured p = MagLogic.pour(old, left.get(target));
        return new MagSwap(next, slot, target, p.target(), p.left().isEmpty() ? null : p.left());
    }
}
