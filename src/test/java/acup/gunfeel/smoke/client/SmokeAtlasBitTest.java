package acup.gunfeel.smoke.client;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import acup.gunfeel.host.render.RecordingRenderPort;
import acup.gunfeel.smoke.SmokeCloud;
import acup.gunfeel.smoke.SmokeGrid;
import acup.gunfeel.smoke.SmokeKind;
import net.minecraft.core.BlockPos;

/** 2000 тиков: дословный эталон f6a3ae0, тексели и порядок всех чтений света. */
class SmokeAtlasBitTest {
    record Write(int x, int y, int w, int h, byte[] bytes) {}
    record Light(int x, int y, int z) {}
    static Write write(int x, int y, int w, int h, ByteBuffer b) {
        byte[] bytes = new byte[b.remaining()];
        b.duplicate().get(bytes);
        return new Write(x,y,w,h,bytes);
    }
    static final class Lights implements SmokeAtlasSlots.Host {
        final List<Light> calls = new ArrayList<>();
        int tick;
        public float brightness(int x, int y, int z) {
            calls.add(new Light(x,y,z));
            return Math.floorMod(x*17+y*31+z*13+tick/19,256)/255F;
        }
    }
    static final class Capture extends RecordingRenderPort {
        final List<Write> writes = new ArrayList<>();
        @Override public void texels(int texture,int x,int y,int w,int h,ByteBuffer b) {
            writes.add(write(x,y,w,h,b));
        }
    }
    static SmokeCloud cloud(int id, Random r) {
        float wx = id%2==0 ? .35F : -.27F, wz=.19F;
        SmokeGrid grid=new SmokeGrid(id%3==0?SmokeKind.WP:SmokeKind.SMOKE,r.nextLong(),0,1,1,wx,wz);
        SmokeCloud c=new SmokeCloud(id,grid,r.nextInt(60)-30,64,r.nextInt(60)-30,0,wx,wz,1) {};
        grid.scan((x,y,z)->y<SmokeGrid.OY-1);
        grid.place();
        return c;
    }
    @Test @Tag("slow")
    void twoThousandTicksMatchEveryWriteAndEveryLightRead() {
        Capture p=new Capture();
        Lights was=new Lights(), now=new Lights();
        OldAtlas.reset(); OldAtlas.host=was;
        SmokeAtlasSlots.close(); SmokeAtlasSlots.init(p,now);
        Random r=new Random(20261120L);
        List<SmokeCloud> active=new ArrayList<>();
        int id=0, shifts=0, writes=0, calls=0;
        for(int i=0;i<4;i++) active.add(cloud(id++,r));
        try {
            for(int tick=0;tick<2000;tick++) {
                if(tick%80==0) {
                    active.removeFirst();
                    active.add(cloud(id++,r));
                    java.util.Collections.shuffle(active,r);
                }
                if(tick%137==0) active.add(cloud(id++,r));
                if(active.size()>6) active.removeFirst();
                for(SmokeCloud c:active) {
                    int sx=c.grid.shiftX(),sz=c.grid.shiftZ();
                    c.grid.step();
                    c.grid.drift((x,y,z)->y<SmokeGrid.OY-1);
                    if(sx!=c.grid.shiftX()||sz!=c.grid.shiftZ()) shifts++;
                }
                // nearest() версии не отдаёт уже рассеявшиеся облака.
                for (int i=0;i<active.size();i++) if (!active.get(i).grid.occupied())
                    active.set(i,cloud(id++,r));
                was.tick=now.tick=tick;
                was.calls.clear();now.calls.clear();OldAtlas.writes.clear();p.writes.clear();
                OldAtlas.update(active);
                SmokeAtlasSlots.update(active);
                assertEquals(was.calls,now.calls,"brightness: координаты, число, порядок; тик "+tick);
                assertEquals(OldAtlas.writes.size(),p.writes.size(),"число записей; тик "+tick);
                for(int i=0;i<p.writes.size();i++) {
                    Write a=OldAtlas.writes.get(i), b=p.writes.get(i);
                    assertEquals(a.x,b.x);assertEquals(a.y,b.y);assertEquals(a.w,b.w);assertEquals(a.h,b.h);
                    assertEquals(b.w*b.h*4,b.bytes.length);
                    assertArrayEquals(a.bytes,b.bytes,"тексели; тик "+tick+", запись "+i);
                }
                assertEquals(OldAtlas.DRAWN.size(),SmokeAtlasSlots.drawn().size());
                for(int i=0;i<OldAtlas.DRAWN.size();i++) {
                    assertEquals(OldAtlas.DRAWN.get(i).index(),SmokeAtlasSlots.drawn().get(i).index());
                    assertSame(OldAtlas.DRAWN.get(i).cloud(),SmokeAtlasSlots.drawn().get(i).cloud());
                }
                writes+=p.writes.size();calls+=now.calls.size();
            }
        } finally { SmokeAtlasSlots.close(); OldAtlas.reset(); }
        assertTrue(shifts>20,"shift: "+shifts);
        assertTrue(writes>4000,"записи: "+writes);
        assertTrue(calls>10000,"чтения света: "+calls);
        System.out.println("2000 тиков; shift="+shifts+"; записи="+writes+"; brightness="+calls);
    }

    // Извлечено step3-oldatlas.py из f6a3ae0; замены перечислены в скрипте.
    static final class OldAtlas {
        static final int WIDTH = SmokeAtlasSlots.WIDTH, TILE_H = SmokeAtlasSlots.TILE_H, SLOTS = 16;
        static final Slot[] SLOT = new Slot[SLOTS];
        static final Map<Integer, Slot> BY_CLOUD = new HashMap<>();
        static final List<Slot> DRAWN = new ArrayList<>();
        static final ByteBuffer upload = ByteBuffer.allocateDirect(WIDTH*TILE_H*4);
        static final List<Write> writes = new ArrayList<>();
        static SmokeAtlasSlots.Host host;
        static void reset() { BY_CLOUD.clear(); java.util.Arrays.fill(SLOT, null); DRAWN.clear(); writes.clear(); }
        public static final class Slot {
            final int index;
            final byte[] now = new byte[SmokeGrid.CELLS];
            final byte[] light = new byte[SmokeGrid.CELLS];
            int lastAge = -1, lightLayer;
            // the grid's shift the texels are for: a box that moved downwind moves them along
            int shiftX, shiftZ;
            // tile rows (a row a z of the grid, with the border) of the box last tick and the tick before: a row the cloud
            // left is sent twice more, its "now" and then its "a tick ago" emptied
            int rowLo = 0, rowHi = TILE_H - 1, boxLo = 0, boxHi = TILE_H - 1;
            SmokeCloud cloud;
    
            Slot(int index) {
                this.index = index;
            }
    
            public int index() {
                return index;
            }
    
            public SmokeCloud cloud() {
                return cloud;
            }
        }
        static void update(List<SmokeCloud> near) {
            DRAWN.clear();
            // free the slots of clouds no longer drawn, then give the new ones a slot
            java.util.Set<Integer> keep = new java.util.HashSet<>();
            for (SmokeCloud c : near) keep.add(c.id);
            BY_CLOUD.entrySet().removeIf(e -> {
                if (keep.contains(e.getKey())) return false;
                SLOT[e.getValue().index] = null;
                return true;
            });
            for (SmokeCloud c : near) {
                Slot s = BY_CLOUD.get(c.id);
                if (s == null || s.cloud != c) {
                    int free = -1;
                    if (s != null) free = s.index;
                    for (int i = 0; i < SLOTS && free < 0; i++) if (SLOT[i] == null) free = i;
                    if (free < 0) continue;
                    s = new Slot(free);
                    s.cloud = c;
                    SLOT[free] = s;
                    BY_CLOUD.put(c.id, s);
                }
                DRAWN.add(s);
                if (s.lastAge == c.grid.age()) continue;
                boolean first = s.lastAge < 0;
                // the box moved downwind: what was sent and the light go along with the cells, and every row is sent
                int mx = c.grid.shiftX() - s.shiftX, mz = c.grid.shiftZ() - s.shiftZ;
                boolean moved = !first && (mx != 0 || mz != 0);
                if (moved) {
                    SmokeAtlasTexels.move(s.now, mx, mz, (byte) 0);
                    SmokeAtlasTexels.move(s.light, mx, mz, s.light[SmokeGrid.index(SmokeGrid.OX, c.grid.oy(), SmokeGrid.OZ)]);
                }
                s.shiftX = c.grid.shiftX();
                s.shiftZ = c.grid.shiftZ();
                // the light: at first the origin's for every cell, then a layer of the box every 4th tick (light changes
                // slowly, and the client's light lookups are what this costs most)
                if (first) seedLight(s, c);
                if (first || (c.grid.age() + s.index) % 4 == 0) light(s, c, s.lightLayer++);
                // only the rows the cloud is in now or was in last time: the rest stay empty in the texture
                int[] box = c.grid.box();
                boolean whole = first || moved;
                int lo = whole ? 0 : Math.min(s.rowLo, box[2]), hi = whole ? TILE_H - 1 : Math.max(s.rowHi, Math.min(TILE_H - 1, box[5] + 2));
                fill(s, c, first, lo, hi);
                s.lastAge = c.grid.age();
                int bl = box[2], bh = Math.min(TILE_H - 1, box[5] + 2);
                s.rowLo = Math.min(bl, s.boxLo);
                s.rowHi = Math.max(bh, s.boxHi);
                s.boxLo = bl;
                s.boxHi = bh;
                upload.position(lo * WIDTH * 4).limit((hi + 1) * WIDTH * 4);
                writes.add(write(0, s.index * TILE_H + lo, WIDTH, hi - lo + 1, upload));
            }
        }
        private static void seedLight(Slot s, SmokeCloud c) {
            byte v = (byte) Math.round(host.brightness(c.originX, c.originY, c.originZ) * 255F);
            java.util.Arrays.fill(s.light, v);
        }
        private static void light(Slot s, SmokeCloud c, int layer) {
            int[] b = c.grid.box();
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            int y0 = Math.max(0, b[1] - 1), y1 = Math.min(SmokeGrid.SY - 1, b[4] + 1);
            int z0 = Math.max(0, b[2] - 1), z1 = Math.min(SmokeGrid.SZ - 1, b[5] + 1);
            int x0 = Math.max(0, b[0] - 1), x1 = Math.min(SmokeGrid.SX - 1, b[3] + 1);
            y0 = y1 = y0 + Math.floorMod(layer, y1 - y0 + 1);
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++)
                    for (int x = x0; x <= x1; x++) {
                        p.set(c.baseX() + x, c.baseY() + y, c.baseZ() + z);
                        s.light[SmokeGrid.index(x, y, z)] = (byte) Math.round(host.brightness(p.getX(), p.getY(), p.getZ()) * 255F);
                    }
        }
        private static void fill(Slot s, SmokeCloud c, boolean first, int lo, int hi) {
            SmokeAtlasTexels.fill(c.grid, s.now, s.light, first, lo, hi, upload);
        }
    }

}
