package acup.gunfeel;

import org.jspecify.annotations.Nullable;

/**
 * Minecraft's {@code Identifier.tryParse} for core's code, on strings: the same rules in the same order as 26.2's
 * {@code Identifier.tryBySeparator(s, ':')} and its character checks, so a string is a valid id here exactly when the
 * game takes it, and as the same "namespace:path" (no ':': "minecraft"; ':' first: "minecraft" too; ".." no namespace).
 * ResourceIdsTest on 26.2 holds it to Identifier itself. A method is added here only with its line in that test, from
 * Identifier's bytecode ({@code javap -c -p net.minecraft.resources.Identifier}).
 */
public final class ResourceIds {
    private ResourceIds() {
    }

    /** the id as the game reads it ("namespace:path"), null where the game's tryParse gives none */
    public static @Nullable String tryParse(String s) {
        int c = s.indexOf(':');
        if (c >= 0) {
            String path = s.substring(c + 1);
            if (!isValidPath(path)) return null;
            if (c != 0) {
                String namespace = s.substring(0, c);
                return isValidNamespace(namespace) ? namespace + ":" + path : null;
            }
            return "minecraft:" + path;
        }
        return isValidPath(s) ? "minecraft:" + s : null;
    }

    public static boolean isValidPath(String path) {
        for (int i = 0; i < path.length(); i++) if (!validPathChar(path.charAt(i))) return false;
        return true;
    }

    public static boolean isValidNamespace(String namespace) {
        if (namespace.equals("..")) return false;
        for (int i = 0; i < namespace.length(); i++) if (!validNamespaceChar(namespace.charAt(i))) return false;
        return true;
    }

    public static boolean validPathChar(char c) {
        return c == '_' || c == '-' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '/' || c == '.';
    }

    public static boolean validNamespaceChar(char c) {
        return c == '_' || c == '-' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.';
    }
}
