package acup.gunfeel;

import java.util.*;

/** Читаемый формат ключей редактора и игровых траекторий общий. */
final class ArmTimeline {
    static final class Key {
        float time, roll;
        String part="grip";
        float[] point={0,0,0}, side={0,-1,0}, shoulder={0,0,0};
        boolean corner, follow;
        Key copy() {
            var k=new Key(); k.time=time; k.roll=roll; k.part=part;
            k.point=point.clone(); k.side=side.clone(); k.shoulder=shoulder.clone(); k.corner=corner; k.follow=follow; return k;
        }
        String token() {
            if (part.equals("grip")) return number(time)+":grip";
            return number(time)+":"+part+(corner?"!":"")+"@"+vector(point)+"/"+vector(side)+">"+(follow?"~":"")+vector(shoulder)+"^"+number(roll);
        }
    }
    static String number(float value) { return Float.toString(value); }
    static String vector(float[] v) { return number(v[0])+","+number(v[1])+","+number(v[2]); }
    static float[] vector(String text) {
        var values=text.split(","); if(values.length!=3) throw new IllegalArgumentException("Нужны три координаты");
        var result=new float[3]; for(int i=0;i<3;i++) { result[i]=Float.parseFloat(values[i]); if(!Float.isFinite(result[i])) throw new IllegalArgumentException("Нужно конечное число"); }
        return result;
    }
    static float time(String text, String aliases) {
        var names=new HashMap<String,Float>();
        for(var entry:aliases.split(",")) { var kv=entry.trim().split("="); if(kv.length==2) names.put(kv[0].toLowerCase(Locale.ROOT),Float.parseFloat(kv[1])); }
        text=text.toLowerCase(Locale.ROOT);
        if(names.containsKey(text)) return names.get(text);
        int op=Math.max(text.lastIndexOf('+'),text.lastIndexOf('-'));
        if(op>0 && names.containsKey(text.substring(0,op))) return names.get(text.substring(0,op))+Float.parseFloat(text.substring(op));
        return Float.parseFloat(text);
    }
    static List<Key> read(String source,String cue,String aliases,float defaultRoll) {
        var keys=new ArrayList<Key>();
        for(var segment:source.split(";")) {
            var words=segment.trim().split("\\s+"); if(!words[0].equals(cue)) continue;
            for(int i=1;i<words.length;i++) {
                var k=new Key(); k.roll=defaultRoll;
                int colon=words[i].indexOf(':'); k.time=time(words[i].substring(0,colon),aliases);
                String target=words[i].substring(colon+1);
                if(!target.equals("grip")) {
                    int at=target.indexOf('@'); k.part=target.substring(0,at);
                    k.corner=k.part.endsWith("!"); if(k.corner) k.part=k.part.substring(0,k.part.length()-1);
                    target=target.substring(at+1);
                    int wrist=target.indexOf('^'); if(wrist>=0) { k.roll=Float.parseFloat(target.substring(wrist+1)); target=target.substring(0,wrist); }
                    int shoulder=target.indexOf('>'); if(shoulder>=0) {
                        String v=target.substring(shoulder+1); k.follow=v.startsWith("~"); k.shoulder=vector(k.follow?v.substring(1):v); target=target.substring(0,shoulder);
                    }
                    int slash=target.indexOf('/'); if(slash>=0) { k.side=vector(target.substring(slash+1)); target=target.substring(0,slash); }
                    k.point=vector(target);
                }
                keys.add(k);
            }
        }
        keys.sort(Comparator.comparingDouble(k->k.time)); return keys;
    }
    static String write(String source,String cue,List<Key> keys) {
        var segments=new ArrayList<String>();
        for(var segment:source.split(";")) if(!segment.isBlank() && !segment.trim().split("\\s+")[0].equals(cue)) segments.add(segment.trim());
        if(!keys.isEmpty()) {
            var sorted=new ArrayList<>(keys); sorted.sort(Comparator.comparingDouble(k->k.time));
            var line=new StringBuilder(cue); for(var key:sorted) line.append(' ').append(key.token()); segments.add(line.toString());
        }
        return String.join("; ",segments);
    }
}
