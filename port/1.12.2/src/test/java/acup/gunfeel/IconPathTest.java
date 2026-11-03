package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// an atlas icon's name as its texture's file (MachineCapture.path): 1.12.2's names carry the path under textures/
class IconPathTest {
    @Test
    void blockIcon() {
        assertEquals("hbm:textures/blocks/rbmk_blank.png", MachineCapture.path("hbm:blocks/rbmk_blank"));
        assertEquals("minecraft:textures/blocks/stone.png", MachineCapture.path("minecraft:blocks/stone"));
    }

    @Test
    void ceMachineTextureOnTheAtlas() {
        assertEquals("hbm:textures/models/machines/electrolyser.png", MachineCapture.path("hbm:models/machines/electrolyser"));
    }

    @Test
    void nameWithoutPath() {
        assertEquals("hbm:textures/blocks/rbmk_blank.png", MachineCapture.path("hbm:rbmk_blank"));
        assertEquals("minecraft:textures/blocks/stone.png", MachineCapture.path("stone"));
    }
}
