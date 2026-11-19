package acup.gunfeel.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import acup.gunfeel.CoreServices;

/**
 * What every version's root of ports ({@link Ports}) must do; a version's test extends this with its own root. Run
 * from the repository's root (core's sources are read to find the ports core asks for).
 */
public abstract class PortsContract {
    /** The version's root, made as its loader makes it. */
    protected abstract Ports ports();

    /** The ports core asks for: every {@code CoreServices.load(X.class, ...)} in core's sources. */
    protected static Set<Class<?>> portsCoreAsksFor() throws IOException, ClassNotFoundException {
        Pattern load = Pattern.compile("CoreServices\\.load\\(([\\w.]+)\\.class");
        Pattern pkg = Pattern.compile("(?m)^package ([\\w.]+);");
        Set<Class<?>> out = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(Path.of("core/src/main/java"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).sorted().toList()) {
                String src = Files.readString(f);
                Matcher pm = pkg.matcher(src);
                String p = pm.find() ? pm.group(1) + "." : "";
                String outer = f.getFileName().toString().replace(".java", "");
                Matcher m = load.matcher(src);
                while (m.find()) out.add(resolve(p, outer, m.group(1), f));
            }
        }
        return out;
    }

    // the name as written: nested in the file's class, or a class of the package (or a full name)
    private static Class<?> resolve(String pkg, String outer, String name, Path f) throws ClassNotFoundException {
        ClassLoader l = PortsContract.class.getClassLoader();
        for (String c : List.of(pkg + outer + "$" + name.replace('.', '$'), pkg + name.replace('.', '$'), name)) {
            try {
                return Class.forName(c, false, l);
            } catch (ClassNotFoundException e) {
                // the next reading of the name
            }
        }
        throw new ClassNotFoundException(name + " in " + f);
    }

    @Test
    void coreFindsThisRoot() {
        Ports found = CoreServices.root();
        assertNotNull(found, "no root of ports found: META-INF/services/acup.gunfeel.host.Ports, or CoreServices.install");
        assertEquals(ports().getClass(), found.getClass());
    }

    @Test
    void everyPortCoreAsksForIsGiven() throws IOException, ClassNotFoundException {
        Ports root = ports();
        List<String> missing = new ArrayList<>();
        for (Class<?> type : portsCoreAsksFor()) {
            Object p = root.port(type);
            if (p == null || !type.isInstance(p)) missing.add(type.getName());
        }
        assertEquals(List.of(), missing, "the root gives none (or a wrong one) for these ports; core would run on their fallbacks");
    }

    @Test
    void oneImplementationEachTime() throws IOException, ClassNotFoundException {
        Ports root = ports();
        for (Class<?> type : portsCoreAsksFor()) assertSame(root.port(type), root.port(type), type.getName());
    }

    interface NotAPort {}

    @Test
    void aPortNoOneAsksForIsNone() {
        assertNull(ports().port(NotAPort.class));
    }

    @Test
    void theNtmSaysWhatItCanDo() {
        assertNotNull(ports().ntm());
    }
}
