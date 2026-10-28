package acup.gunfeel;

import java.util.*;
import net.minecraft.world.phys.Vec3;

/** Убирает выбранные отрезки из общей обводки, включая частичное перекрытие длинного ребра. */
final class SelectionContours {
    private SelectionContours() {}
    static List<Vec3[]> remaining(List<Vec3[]> all,List<Vec3[]> selected) {
        var result=new ArrayList<Vec3[]>();
        for(var strip:all) for(int i=0;i<strip.length;i+=2) {
            var a=strip[i];var b=strip[i+1];var d=b.subtract(a);double length=d.lengthSqr();
            if(length<1e-16) continue;
            var intervals=new ArrayList<double[]>();
            for(var chosen:selected) for(int k=0;k<chosen.length;k+=2) {
                var p=chosen[k].subtract(a);var q=chosen[k+1].subtract(a);
                if(p.cross(d).lengthSqr()>length*1e-12 || q.cross(d).lengthSqr()>length*1e-12) continue;
                double x=p.dot(d)/length,y=q.dot(d)/length;
                double lo=Math.max(0,Math.min(x,y)),hi=Math.min(1,Math.max(x,y));
                if(hi>lo) intervals.add(new double[]{lo,hi});
            }
            intervals.sort(Comparator.comparingDouble(t->t[0]));double from=0;
            for(var cut:intervals) {
                if(cut[0]>from+1e-7) result.add(new Vec3[]{a.add(d.scale(from)),a.add(d.scale(cut[0]))});
                from=Math.max(from,cut[1]);
            }
            if(from<1-1e-7) result.add(new Vec3[]{a.add(d.scale(from)),b});
        }
        return List.copyOf(result);
    }
}
