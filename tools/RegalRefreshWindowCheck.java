import android.os.SystemClock;
import android.view.View;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import me.laumss.mosaic.MosaicEinkRefreshModule;
import me.laumss.mosaic.eink.EinkRegionDriver;
import me.laumss.mosaic.eink.RegalRefreshWindow;

/** JVM scheduler harness for the compiled window: no device or vendor command is used. */
public final class RegalRefreshWindowCheck {
    private static int checks;
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }
    private static View reset() {
        SystemClock.now=0;
        MosaicEinkRefreshModule.Companion.events.clear();
        MosaicEinkRefreshModule.Companion.failEnable=false;
        EinkRegionDriver.busy=()->0;
        return new View();
    }
    private static boolean lastState() {
        var e=MosaicEinkRefreshModule.Companion.events;
        return e.get(e.size()-1);
    }
    public static void main(String[] args) {
        View v=reset();
        RegalRefreshWindow w=new RegalRefreshWindow(v,()->true,Runnable::run);
        v.advance(0);
        check(!lastState(),"non-auto starts with off baseline");
        check(w.begin("pan"),"final-frame window opens");
        v.advance(749);
        check(w.isActive(),"early idle cannot close a never-busy panel before 750ms");
        v.advance(800);
        check(!w.isActive() && !lastState(),"idle without observed busy closes after grace");

        v=reset();
        int[] reads={0};
        EinkRegionDriver.busy=()->++reads[0]==1?1:0;
        w=new RegalRefreshWindow(v,()->true,Runnable::run);
        v.advance(0); w.begin("card"); v.advance(393);
        check(w.isActive(),"busy needs three subsequent idle samples");
        v.advance(394);
        check(!w.isActive() && !lastState(),"observed busy followed by three idle closes");

        v=reset();
        boolean[] auto={false};
        ArrayDeque<Runnable> workers=new ArrayDeque<>();
        w=new RegalRefreshWindow(v,()->!auto[0],workers::add);
        v.advance(0); w.begin("before-auto"); v.advance(250);
        check(workers.size()==1,"completion query is asynchronous");
        auto[0]=true; w.release("full-auto");
        check(lastState(),"auto handoff restores host enabled baseline first");
        int sent=MosaicEinkRefreshModule.Companion.events.size();
        workers.remove().run(); v.advance(4000);
        w.acquireIdle("auto-frame"); w.interrupt("auto-input");
        check(MosaicEinkRefreshModule.Companion.events.size()==sent,"stale query and deadline cannot close auto");
        auto[0]=false; w.acquireIdle("auto-ended");
        check(!lastState(),"non-auto regains off baseline after auto exits");

        v=reset(); workers=new ArrayDeque<>();
        w=new RegalRefreshWindow(v,()->true,workers::add);
        v.advance(0); w.begin("first"); v.advance(250);
        w.interrupt("new-input"); w.begin("second");
        workers.remove().run(); v.advance(250);
        check(w.isActive() && lastState(),"old worker cannot close a newer operation window");
        v.advance(3250);
        check(!w.isActive() && !lastState(),"deadline closes even with a delayed worker");

        v=reset(); EinkRegionDriver.busy=()->-1;
        w=new RegalRefreshWindow(v,()->true,Runnable::run);
        v.advance(0); w.begin("unavailable"); v.advance(749);
        check(w.isActive(),"unavailable panel state still honors minimum grace");
        v.advance(800);
        check(!w.isActive() && !lastState(),"unavailable panel state has bounded shutdown");

        v=reset(); w=new RegalRefreshWindow(v,()->true,Runnable::run);
        v.advance(0); MosaicEinkRefreshModule.Companion.failEnable=true;
        check(!w.begin("failed") && !w.isActive() && !lastState(),"failed enable rolls back off without active window");
        System.out.println("PASS: "+checks+" Regal window timing, failure, and auto ownership checks (simulated panel)");
    }
}
