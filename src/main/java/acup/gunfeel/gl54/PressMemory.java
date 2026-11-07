package acup.gunfeel.gl54;

import java.util.List;

// What the ammo press (BlockEntityMachineAmmoPress, through MixinAmmoPress) remembers: the layouts of the magazines it
// loaded freely (PressLayouts). The list is replaced, never changed in place, so a screen can tell it changed by identity.
public interface PressMemory {
    List<List<String>> gunfeel$layouts();

    void gunfeel$layouts(List<List<String>> layouts);
}
