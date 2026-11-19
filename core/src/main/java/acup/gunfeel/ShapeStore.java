package acup.gunfeel;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Один писатель, один последний ожидающий снимок, атомарная замена готового файла. */
final class ShapeStore {
    private record Request(Path path,Map<String,long[]> shapes) {}
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("ntm-gunfeel");
    private final Executor executor;
    private final ShapePacking packing;
    private Request pending;
    private boolean running;
    static ShapeStore background() {
        return new ShapeStore(Executors.newSingleThreadExecutor(task->{
            var thread=new Thread(task,"gunfeel-shape-save");thread.setDaemon(true);thread.setPriority(Thread.MIN_PRIORITY);return thread;
        }),ShapePacking.SHARED);
    }
    ShapeStore(Executor executor,ShapePacking packing){this.executor=executor;this.packing=packing;}
    synchronized void request(Path path,Map<String,long[]> shapes) {
        pending=new Request(path,new TreeMap<>(shapes));
        if(!running){running=true;executor.execute(this::drain);}
    }
    synchronized boolean idle(){return !running;}
    private void drain() {
        while(true) {
            Request request;
            synchronized(this){request=pending;pending=null;if(request==null){running=false;return;}}
            long start=System.nanoTime();
            try {
                write(request.path(),request.shapes());
                LOG.info("Saved {} bullet shapes in background in {} ms",request.shapes().size(),(System.nanoTime()-start)/1_000_000);
            } catch(Exception e){LOG.warn("Could not save bullet shapes to {}",request.path(),e);}
        }
    }
    private void write(Path path,Map<String,long[]> shapes) throws IOException {
        Path temp=Files.createTempFile(path.toAbsolutePath().getParent(),".gunfeel-shapes-",".tmp");
        try {
            try(Writer writer=Files.newBufferedWriter(temp,StandardCharsets.UTF_8)) { encode(writer,shapes,packing); }
            try{Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temp);}
    }
    static void encode(Writer writer,Map<String,long[]> shapes,ShapePacking packing) throws IOException {
        writer.write("# made by NTM Gun Feel from this client's block models and big NTM models: 16x16x16 cells a bullet meets,\n");
        writer.write("# bit x+16*(y+16*z), optional exact faces after the cells, deflated base64 after z; shared shapes point to @n\n");
        Map<String,String> packed=new TreeMap<>();
        Map<String,Integer> uses=new TreeMap<>();
        for(var entry:shapes.entrySet()) {
            String value=packing.pack(entry.getValue());packed.put(entry.getKey(),value);uses.merge(value,1,Integer::sum);
        }
        Map<String,String> ids=new LinkedHashMap<>();
        for(var entry:uses.entrySet())if(entry.getValue()>1)ids.put(entry.getKey(),"@"+ids.size());
        for(var entry:ids.entrySet())writer.write(entry.getValue()+" = "+entry.getKey()+"\n");
        for(var entry:packed.entrySet())writer.write(entry.getKey().replace(":","\\:").replace("=","\\=")+" = "+ids.getOrDefault(entry.getValue(),entry.getValue())+"\n");
    }
}
