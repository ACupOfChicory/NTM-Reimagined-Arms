package acup.gunfeel;

// port: 1.7.10's Tessellator, from MixinTessellator. A model that threw between startDrawing and draw leaves it
// drawing, and the next startDrawing anywhere in the game throws ("Already tesselating!"): drop drops what it began,
// drawing none of it (ArmLayers, after an armour model that threw on the arm; 1.12.2 4d04c9e: finishDrawing)
public interface TessellatorDrop {
    // true: it was drawing
    boolean gunfeel$drop();
}
