package acup.gunfeel.host;

import java.util.List;
import java.util.Map;
import java.util.Set;

// the contract on a fake table: zombie 0, pig 1; minecraft:undead takes the zombie
class FakeEntityTypesTest extends EntityTypeTableContract {
    static final class Fake implements EntityTypeTable {
        final List<String> names = List.of(new String("minecraft:zombie"), new String("minecraft:pig"));
        final Map<String, Set<Integer>> tags = Map.of("minecraft:undead", Set.of(0), "minecraft:skeletons", Set.of());

        @Override
        public String name(int type) {
            if (type < 0 || type >= names.size()) throw new IllegalArgumentException("no entity type has number " + type);
            return names.get(type);
        }

        @Override
        public boolean tagged(int type, String tag) {
            return tags.getOrDefault(tag, Set.of()).contains(type);
        }
    }

    private final Fake fake = new Fake();

    @Override
    protected EntityTypeTable table() {
        return fake;
    }

    @Override
    protected int zombie() {
        return 0;
    }

    @Override
    protected int none() {
        return 9;
    }
}
