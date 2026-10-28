package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShapeStoreTest {
    @TempDir Path dir;
    @Test void backgroundSaveDoesNotBlockAndLatestPendingSnapshotWins() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var calls=new AtomicInteger();
        var packing=new ShapePacking(shape->{
            if(calls.incrementAndGet()==1){entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}
            return PassableClip.pack(shape);
        });
        var worker=Executors.newSingleThreadExecutor();
        var store=new ShapeStore(worker,packing);Path file=dir.resolve("shapes.properties");
        Files.writeString(file,"previous-complete-file");
        long[] one=new long[65];one[0]=1;
        long[] two=one.clone();two[0]=2;
        long[] three=one.clone();three[0]=3;
        try {
            store.request(file,Map.of("old",one));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertEquals("previous-complete-file",Files.readString(file));
            store.request(file,Map.of("skipped",two));
            var newest=new HashMap<String,long[]>();newest.put("latest",three);
            store.request(file,newest);newest.clear();
            release.countDown();worker.shutdown();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));
            var props=new Properties();try(var reader=Files.newBufferedReader(file)){props.load(reader);}
            assertEquals(Set.of("latest"),props.stringPropertyNames());
            assertArrayEquals(three,PassableClip.unpack(props.getProperty("latest")));
            assertEquals(2,calls.get(),"промежуточный снимок не должен сжиматься");
            assertTrue(store.idle());
            try(var files=Files.list(dir)){assertEquals(1,files.count(),"временный файл удалён");}
        } finally {release.countDown();worker.shutdownNow();}
    }
    @Test void unchangedBankDoesNotRecompressAndAliasesAndFacesRoundTrip() throws Exception {
        var calls=new AtomicInteger();
        var packing=new ShapePacking(shape->{calls.incrementAndGet();return PassableClip.pack(shape);});
        long[] cells=new long[65];cells[0]=5;
        var face=new ModelSurface.Face(new double[]{0,0,0,1,0,0,0,1,0},new double[6],1,1,new long[]{1});
        long[] exact=ModelSurface.attach(cells,List.of(face));
        var shapes=new TreeMap<String,long[]>();shapes.put("hbm:door[open=false]",exact);shapes.put("hbm:door[open=true]",exact);
        var first=new StringWriter();ShapeStore.encode(first,shapes,packing);
        assertEquals(1,calls.get());
        var second=new StringWriter();ShapeStore.encode(second,shapes,packing);
        assertEquals(first.toString(),second.toString());assertEquals(1,calls.get());
        var props=new Properties();props.load(new StringReader(second.toString()));
        assertEquals("@0",props.getProperty("hbm:door[open=false]"));
        assertArrayEquals(exact,PassableClip.unpack(props.getProperty("@0")));
        cells=cells.clone();cells[0]=9;shapes.put("changed",cells);
        ShapeStore.encode(new StringWriter(),shapes,packing);assertEquals(2,calls.get());
    }
}
