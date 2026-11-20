package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import acup.gunfeel.EuphImmunityBase.Kit;

/** The old switches against core: all modes, one balance read first, no mode read when off. */
class EuphOnTest {
    @Test
    void onMatchesOldAndReadsBalanceFirst() throws Throwable {
        isolated(false);
    }

    @Test
    void dnsFormulaMatchesOldAndReadsBalanceFirst() throws Throwable {
        isolated(true);
    }

    // BalanceKeys freezes its dev switch: use a fresh core, leaving the worker's core unchanged.
    private static void isolated(boolean dns) throws Throwable {
        String was = System.getProperty(BalanceKeys.DEV);
        URL[] path = {EuphOnTest.class.getProtectionDomain().getCodeSource().getLocation(),
                EuphImmunityBase.class.getProtectionDomain().getCodeSource().getLocation()};
        try (URLClassLoader loader = new URLClassLoader(path, EuphOnTest.class.getClassLoader()) {
            @Override
            protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("acup.gunfeel.")) return super.loadClass(name, resolve);
                Class<?> found = findLoadedClass(name);
                if (found == null) found = findClass(name);
                if (resolve) resolveClass(found);
                return found;
            }
        }) {
            System.setProperty(BalanceKeys.DEV, "true");
            var method = loader.loadClass(EuphOnTest.class.getName()).getDeclaredMethod("cases", boolean.class);
            method.setAccessible(true);
            try {
                method.invoke(null, dns);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        } finally {
            if (was == null) System.clearProperty(BalanceKeys.DEV);
            else System.setProperty(BalanceKeys.DEV, was);
        }
    }

    private static final class Settings extends AbstractMap<String, String> {
        final Map<String, String> values;
        final List<String> calls = new ArrayList<>();

        Settings(String euph, String dns, String fau) {
            values = Map.of(Kit.EUPHEMIUM.key + ".mode", euph, Kit.DNS.key + ".mode", dns, Kit.FAU.key + ".mode", fau);
        }

        @Override
        public String get(Object key) {
            calls.add("mode " + key);
            return values.get(key);
        }

        @Override
        public Set<Entry<String, String>> entrySet() {
            return values.entrySet();
        }
    }

    private static final class Balance implements BooleanSupplier {
        final Settings settings;
        final boolean on;
        int reads;

        Balance(Settings settings, boolean on) {
            this.settings = settings;
            this.on = on;
        }

        @Override
        public boolean getAsBoolean() {
            reads++;
            settings.calls.add("balance");
            return on;
        }
    }

    // a5096a9's on(Kit) and dnsFormula, with only the feature read supplied.
    private static boolean oldOn(Kit kit, BooleanSupplier balanceOn) {
        return balanceOn.getAsBoolean() && kit.mode().equals("pool");
    }

    private static boolean oldDnsFormula(BooleanSupplier balanceOn) {
        return balanceOn.getAsBoolean() && !Kit.DNS.mode().equals("ntm");
    }

    private static List<Object> kits() {
        List<Object> out = new ArrayList<>();
        for (Kit kit : Kit.values()) out.add(List.of(kit, kit.key, kit.set, kit.message, kit.size, kit.mode()));
        return out;
    }

    private static void cases(boolean dnsFormula) {
        ServerSettings.Snapshot was = ServerSettings.remote();
        String[] modes = {"pool", "formula", "ntm"};
        try {
            for (String euph : modes) for (String dns : modes) for (String fau : modes) {
                Settings settings = new Settings(euph, dns, fau);
                var snapshot = new ServerSettings.Snapshot("", settings, Map.of(), "");
                ServerSettings.useRemote(snapshot);
                assertEquals(euph, Kit.EUPHEMIUM.mode());
                assertEquals(dns, Kit.DNS.mode());
                assertEquals(fau.equals("pool") ? "pool" : "formula", Kit.FAU.mode());
                List<Object> before = kits();
                for (boolean on : new boolean[] {false, true}) {
                    for (Kit kit : dnsFormula ? new Kit[] {Kit.DNS} : Kit.values()) {
                        String at = euph + "/" + dns + "/" + fau + " " + kit + " " + on;
                        settings.calls.clear();
                        Balance old = new Balance(settings, on);
                        boolean want = dnsFormula ? oldDnsFormula(old) : oldOn(kit, old);
                        List<String> calls = List.copyOf(settings.calls);
                        assertEquals(1, old.reads, at);
                        settings.calls.clear();
                        Balance balance = new Balance(settings, on);
                        boolean got = dnsFormula ? EuphImmunityBase.dnsFormulaOf(balance) : EuphImmunityBase.onOf(kit, balance);
                        assertEquals(want, got, at);
                        assertEquals(1, balance.reads, at);
                        assertEquals(on ? List.of("balance", "mode " + kit.key + ".mode") : List.of("balance"), settings.calls, at);
                        assertEquals(calls, settings.calls, at);
                        assertSame(snapshot, ServerSettings.remote(), at);
                        assertSame(settings, snapshot.server(), at);
                        assertEquals(settings.values, settings, at);
                        assertEquals(before, kits(), at);
                    }
                }
            }
        } finally {
            ServerSettings.useRemote(was);
        }
    }
}
