package acup.gunfeel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Оценки привязаны к ID моба, поэтому порядок реестра можно менять. */
final class MobReviewStore {
    record Decision(String status, String note) {}
    private final Path file;
    private final Map<String, Decision> entries = new LinkedHashMap<>();

    MobReviewStore(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) {
            int line = 0;
            for (String text : Files.readAllLines(file)) {
                line++;
                if (text.isBlank() || text.startsWith("#")) continue;
                String[] fields = text.split("\t", -1);
                if (fields.length != 3 || !fields[0].contains(":") || !java.util.Set.of("ok","bad","skip","note").contains(fields[1]))
                    throw new IOException("Некорректная строка " + line + " в " + file);
                entries.put(fields[0], new Decision(fields[1], fields[2]));
            }
        }
    }

    Decision get(String id) { return entries.get(id); }
    long count(String status) { return entries.values().stream().filter(d -> d.status().equals(status)).count(); }
    boolean reviewed(String id) { var d=get(id); return d!=null && !d.status().equals("note"); }
    void set(String id, Decision decision) throws IOException {
        Map<String, Decision> next = new LinkedHashMap<>(entries);
        if (decision == null) next.remove(id);
        else next.put(id, new Decision(decision.status(), clean(decision.note())));
        Path parent=file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp=Files.createTempFile(parent,"mob-review-",".tmp");
        try {
            StringBuilder out=new StringBuilder("# ID\tоценка (ok/bad/skip/note)\tзаметка\n");
            next.forEach((key,d) -> out.append(key).append('\t').append(d.status()).append('\t').append(d.note()).append('\n'));
            Files.writeString(temp,out);
            Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            entries.clear(); entries.putAll(next);
        } finally { Files.deleteIfExists(temp); }
    }
    private static String clean(String text) { return text.replace('\t',' ').replace('\r',' ').replace('\n',' '); }
}
