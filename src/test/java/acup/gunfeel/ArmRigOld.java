package acup.gunfeel;

import java.util.*;

// The arm parsers as Arms and ArmPoseEdits had them at 524f0ea, copied word for word (generated from git): ArmRigTest
// holds ArmRig's results against these on every gun's data.
final class ArmRigOld {
    record PathKey(float ms, String part, int nth, float[] at, float[] side, float[] shoulder, int contact, boolean followShoulder, float roll) {
    }

    record Carry(String part, String[] buses, int grab, float[][] ats, float[] dir, int[] active) {
    }

    static final Carry NO_CARRY = new Carry("", new String[] {""}, 0, new float[][] {new float[3]}, new float[] {0F, 0F, -1F}, new int[1]);

    static float pathMs(String e, Map<String, Float> times) {
        Float whole = times.get(e.toLowerCase(java.util.Locale.ROOT));
        if (whole != null) return whole;
        int op = Math.max(e.lastIndexOf('+'), e.lastIndexOf('-'));
        if (op <= 0 || Character.isDigit(e.charAt(0))) return Float.parseFloat(e);
        Float base = times.get(e.substring(0, op).trim().toLowerCase(java.util.Locale.ROOT));
        if (base == null) throw new IllegalArgumentException(e);
        return base + Float.parseFloat(e.substring(op));
    }

    static Map<String, List<PathKey>> parsePath(String src, String timeSrc) {
        Map<String, Float> times = new HashMap<>();
        for (String t : timeSrc.split(",")) {
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            try {
                times.put(t.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT), Float.parseFloat(t.substring(eq + 1).trim()));
            } catch (NumberFormatException e) {
                // skip
            }
        }
        Map<String, List<PathKey>> out = new HashMap<>();
        for (String anim : src.split(";")) {
            String[] w = anim.trim().split("\\s+");
            if (w.length < 2) continue;
            List<PathKey> list = new ArrayList<>();
            for (int j = 1; j < w.length; j++) {
                try {
                    String e = w[j];
                    int colon = e.indexOf(':');
                    float ms = pathMs(e.substring(0, colon), times);
                    String t = e.substring(colon + 1);
                    PathKey k;
                    if (t.equalsIgnoreCase("grip")) {
                        k = new PathKey(ms, null, 0, null, null, null, 0, false, 0F);
                    } else {
                        int at = t.indexOf('@');
                        String part = t.substring(0, at), pt = t.substring(at + 1);
                        int marker=part.indexOf('!');
                        int contact=marker<0?0:ArmContact.parse(part.substring(marker+1));
                        if(marker>=0)part=part.substring(0,marker);
                        int nth = 0;
                        int hash = part.indexOf('#');
                        if (hash >= 0) {
                            String n = part.substring(hash + 1);
                            nth = n.equalsIgnoreCase("last") ? -1 : Integer.parseInt(n);
                            part = part.substring(0, hash);
                        }
                        int wrist = pt.indexOf('^');
                        float roll = wrist < 0 ? Float.NaN : Float.parseFloat(pt.substring(wrist + 1));
                        if (wrist >= 0) pt = pt.substring(0, wrist);
                        int gt = pt.indexOf('>');
                        boolean followShoulder = gt >= 0 && pt.substring(gt + 1).startsWith("~");
                        float[] shoulder = gt < 0 ? new float[3] : xyz(pt.substring(gt + (followShoulder ? 2 : 1)));
                        if (gt >= 0) pt = pt.substring(0, gt);
                        int sl = pt.indexOf('/');
                        float[] side = sl < 0 ? NO_CARRY.dir : xyz(pt.substring(sl + 1));
                        k = new PathKey(ms, part, nth, xyz(sl < 0 ? pt : pt.substring(0, sl)), side, shoulder, contact, followShoulder, roll);
                    }
                    list.add(k);
                } catch (RuntimeException e) {
                    // skip a malformed entry
                }
            }
            if (list.isEmpty()) continue;
            // in time order (a named time may put a key anywhere); of two keys at one time the first stays
            list.sort(java.util.Comparator.comparingDouble(PathKey::ms));
            for (int j = list.size() - 1; j > 0; j--) if (list.get(j).ms() <= list.get(j - 1).ms()) list.remove(j);
            int slash = w[0].indexOf('/');
            String name = slash < 0 ? w[0].toUpperCase(java.util.Locale.ROOT)
                    : w[0].substring(0, slash).toUpperCase(java.util.Locale.ROOT) + w[0].substring(slash);
            out.put(name, list);
        }
        return out;
    }

    static Map<String, float[]> parseKeys(String src) {
        Map<String, float[]> out = new HashMap<>();
        for (String anim : src.split(";")) {
            String[] w = anim.trim().split("\\s+");
            if (w.length < 2) continue;
            List<Float> v = new ArrayList<>();
            for (int j = 1; j < w.length; j++) {
                String[] tv = w[j].split(":");
                if (tv.length != 2) continue;
                try {
                    float time = Float.parseFloat(tv[0]), angle = Float.parseFloat(tv[1]);
                    if (!v.isEmpty() && time <= v.get(v.size() - 2)) continue;
                    v.add(time);
                    v.add(angle);
                } catch (NumberFormatException e) {
                    // skip
                }
            }
            if (v.isEmpty()) continue;
            float[] a = new float[v.size()];
            for (int j = 0; j < a.length; j++) a[j] = v.get(j);
            out.put(w[0].toUpperCase(java.util.Locale.ROOT), a);
        }
        return out;
    }

    static Map<String, List<float[]>> parseCarryKeys(String src) {
        Map<String, List<float[]>> out = new HashMap<>();
        for (String anim : src.split(";")) {
            String[] w = anim.trim().split("\\s+");
            if (w.length < 2) continue;
            List<float[]> list = new ArrayList<>();
            for (int j = 1; j < w.length; j++) {
                try {
                    String e = w[j];
                    float[] k = new float[7];
                    int colon = e.indexOf(':');
                    k[0] = Float.parseFloat(e.substring(0, colon));
                    String rest = e.substring(colon + 1);
                    int at = rest.indexOf('@'), sl = rest.indexOf('/');
                    if (at >= 0) {
                        float[] pt = xyz(rest.substring(at + 1));
                        k[2] = 2F;
                        k[4] = pt[0];
                        k[5] = pt[1];
                        k[6] = pt[2];
                        rest = rest.substring(0, at);
                    } else if (sl >= 0) {
                        k[2] = 1F;
                        k[3] = Float.parseFloat(rest.substring(sl + 1));
                        rest = rest.substring(0, sl);
                    }
                    k[1] = Float.parseFloat(rest);
                    if (!list.isEmpty() && k[0] <= list.get(list.size() - 1)[0]) continue;
                    list.add(k);
                } catch (RuntimeException e) {
                    // skip a malformed entry
                }
            }
            if (list.isEmpty()) continue;
            int slash = w[0].indexOf('/');
            String name = slash < 0 ? w[0].toUpperCase(java.util.Locale.ROOT)
                    : w[0].substring(0, slash).toUpperCase(java.util.Locale.ROOT) + w[0].substring(slash);
            out.put(name, list);
        }
        return out;
    }

    static float[] xyz(String v) {
        String[] t = v.split(",");
        return new float[] {Float.parseFloat(t[0]), Float.parseFloat(t[1]), Float.parseFloat(t[2])};
    }

    static Map<String, float[]> hide(String src) {
            Map<String, float[]> out = new HashMap<>();
            for (String anim : src.split(";")) {
                String[] w = anim.trim().split("\\s+");
                if (w.length < 2) continue;
                List<Float> v = new ArrayList<>();
                for (int j = 1; j < w.length; j++) {
                    String[] ab = w[j].split("-");
                    if (ab.length != 2) continue;
                    try {
                        float a = Float.parseFloat(ab[0]), b = Float.parseFloat(ab[1]);
                        v.add(a);
                        v.add(b);
                    } catch (NumberFormatException e) {
                        // skip
                    }
                }
                float[] a = new float[v.size()];
                for (int j = 0; j < a.length; j++) a[j] = v.get(j);
                out.put(w[0].toUpperCase(java.util.Locale.ROOT), a);
            }
            return out;
    }

    static Carry carry(String src) {
        Carry[] carry = {NO_CARRY};
        int hand = 0;
            String[] w = src.split("\\s+");
            if (w.length == 4 || w.length == 5) {
                try {
                    String[] pts = w[3].split("\\|");
                    float[][] at = new float[pts.length][];
                    for (int j = 0; j < pts.length; j++) at[j] = xyz(pts[j]);
                    float[] dir = w.length == 5 ? xyz(w[4]) : NO_CARRY.dir;
                    carry[hand] = new Carry(w[0], w[1].split("\\|"), Integer.parseInt(w[2]), at, dir, new int[1]);
                } catch (RuntimeException e) {
                    // malformed: no carrying
                }
            }
        return carry[hand];
    }

    static Map<String,TreeMap<Float,float[]>> read(String source) {
        var result=new LinkedHashMap<String,TreeMap<Float,float[]>>();
        for(var segment:source.split(";")) {
            var words=segment.trim().split("\\s+"); if(words.length<2) continue;
            var values=new TreeMap<Float,float[]>();
            for(int i=1;i<words.length;i++) {
                int colon=words[i].indexOf(':'); float time=Float.parseFloat(words[i].substring(0,colon));
                var numbers=words[i].substring(colon+1).split(","); if(numbers.length!=6) throw new IllegalArgumentException();
                var pose=new float[6]; for(int j=0;j<6;j++){pose[j]=Float.parseFloat(numbers[j]);if(!Float.isFinite(pose[j]))throw new IllegalArgumentException();}
                if(!Float.isFinite(time))throw new IllegalArgumentException(); values.put(time,pose);
            }
            result.put(words[0],values);
        }
        return result;
    }
}
