package acup.gunfeel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

// Отдельный датасет; оценки качества хитбоксов сюда не переносятся.
final class HeadLabelStore {
    record Decision(String sample,String status,Map<String,Integer> labels) {}
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private final Path root;
    private final Map<String,Decision> entries=new LinkedHashMap<>();
    HeadLabelStore(Path root) throws IOException {
        this.root=root; Files.createDirectories(root);
        Path index=root.resolve("index.json");
        if(Files.exists(index)) {
            var object=com.google.gson.JsonParser.parseString(Files.readString(index)).getAsJsonObject();
            for(var entry:object.entrySet()) entries.put(entry.getKey(),JSON.fromJson(entry.getValue(),Decision.class));
        }
    }
    Decision get(String id) { return entries.get(id); }
    boolean done(String id) { var d=get(id); return d!=null && Set.of("done","none").contains(d.status()); }
    long completed() { return entries.keySet().stream().filter(this::done).count(); }
    com.google.gson.JsonObject model(String sample) throws IOException {
        return com.google.gson.JsonParser.parseString(Files.readString(root.resolve("samples").resolve(sample).resolve("model.json"))).getAsJsonObject();
    }
    String snapshot(String id,Object model) throws IOException {
        String sample=id.replace(':','_')+"-"+UUID.randomUUID();
        write(root.resolve("samples").resolve(sample).resolve("model.json"),model);
        return sample;
    }
    void save(String id,String sample,String status,Map<String,Integer> labels) throws IOException {
        if(!Set.of("draft","done","none","skip").contains(status)) throw new IllegalArgumentException("Неизвестная оценка");
        if(labels.values().stream().anyMatch(group->group<1)) throw new IllegalArgumentException("Неверный номер головы");
        if(status.equals("none") && !labels.isEmpty()) throw new IllegalArgumentException("У сущности без головы не должно быть выделения");
        if(status.equals("done") && labels.isEmpty()) throw new IllegalArgumentException("Сначала выдели голову или выбери «Нет головы»");
        var decision=new Decision(sample,status,Map.copyOf(labels));
        write(root.resolve("samples").resolve(sample).resolve("annotation.json"),decision);
        var next=new LinkedHashMap<>(entries); next.put(id,decision);
        write(root.resolve("index.json"),next);
        entries.clear(); entries.putAll(next);
    }
    private static void write(Path path,Object data) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp=Files.createTempFile(path.getParent(),"head-label-",".tmp");
        try {
            Files.writeString(temp,JSON.toJson(data));
            Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
