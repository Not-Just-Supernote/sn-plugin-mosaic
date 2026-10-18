import android.content.Context;
import android.graphics.Typeface;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.MotionEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import me.laumss.mosaic.render.BoardContentView;
import me.laumss.mosaic.render.TransientInkView;

/** Exercises real handoff timers and rearm guards; no window, board file or vendor binder is used. */
public final class InkHandoffTimingCheck {
    private static int checks;
    private static int binderCalls;
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target,value);
    }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return target.getClass().getMethod(name,types).invoke(target,args);
    }
    public static void main(String[] args) throws Exception {
        if (Typeface.DEFAULT == null) throw new AssertionError("font unavailable");
        if (Looper.getMainLooper()==null) Looper.prepareMainLooper();
        Object at=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context context=(Context)at.getClass().getMethod("getSystemContext").invoke(at);
        Handler handler=new Handler(Looper.getMainLooper());
        Class<?> unsafeClass=Class.forName("sun.misc.Unsafe");
        Field uf=unsafeClass.getDeclaredField("theUnsafe"); uf.setAccessible(true);
        Object unsafe=uf.get(null);
        Method allocate=unsafeClass.getMethod("allocateInstance",Class.class);
        Class<?> hostClass=Class.forName("me.laumss.mosaic.MosaicBoardView");
        Object host=allocate.invoke(unsafe,hostClass);
        BoardContentView content=new BoardContentView(context);
        set(host,"mainHandler",handler);
        set(host,"contentView",content);
        set(host,"inkView",new TransientInkView(context));
        Class<?> handoffClass=Class.forName("me.laumss.mosaic.drawpath.InkHandoff");
        Object handoff=handoffClass.getConstructor(hostClass).newInstance(host);
        set(host,"inkHandoff",handoff);
        Object session=allocate.invoke(unsafe,Class.forName("me.laumss.mosaic.drawpath.DrawPathSession"));
        set(session,"view",host); set(session,"configured",true); set(session,"drawPathActive",true);
        set(session,"drawPathBinder",new Binder() {
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                binderCalls++; return true;
            }
        });
        set(host,"drawPath",session);
        Class<?>[] none=new Class<?>[0];
        Function0<Unit> commit=()->Unit.INSTANCE;
        content.setDeferRefresh(true);
        call(handoff,"setAwaitingStrokeCommit",new Class[]{boolean.class},true);
        call(handoff,"commitPlainStroke",new Class[]{Function0.class},commit);
        check((Boolean)call(handoff,"getHoldsSystemInk",none),"plain stroke must hold system ink");
        check(!(Boolean)call(handoff,"getAwaitingStrokeCommit",none),"test must reach the post-commit 3s window");
        call(session,"rearmDrawPath",new Class[]{String.class},"test-delayed-rearm");
        call(session,"onPenHover",new Class[]{int.class},MotionEvent.ACTION_HOVER_ENTER);
        call(session,"scheduleConfigure",new Class[]{String.class},"test-props");
        check(binderCalls==0,"service must not be reconfigured during handoff");
        check((Boolean)call(handoff,"getHoverRearmDeferred",none),"rearm must remain pending");
        long start=SystemClock.uptimeMillis();
        handler.postDelayed(()->{
            try {
                check(content.isDeferringRefresh(),"content released before next stroke");
                call(handoff,"setAwaitingStrokeCommit",new Class[]{boolean.class},true);
                call(handoff,"commitPlainStroke",new Class[]{Function0.class},commit);
            } catch(Exception e) { throw new RuntimeException(e); }
        },1000);
        handler.postDelayed(()->{
            try {
                check((Boolean)call(handoff,"getHoldsSystemInk",none),"second stroke must reset idle deadline");
                check(content.isDeferringRefresh(),"canvas released before 3s after second stroke");
            } catch(Exception e) { throw new RuntimeException(e); }
        },3200);
        handler.postDelayed(()->{
            try {
                check(!(Boolean)call(handoff,"getHoldsSystemInk",none),"idle handoff did not finish");
                check(!content.isDeferringRefresh(),"canvas was not released after idle");
                check(binderCalls==0,"isolated test must not call binder");
                System.out.println("INK_HANDOFF_CHECKS_PASSED="+checks+" elapsedMs="+(SystemClock.uptimeMillis()-start));
                System.exit(0);
            } catch(Exception e) { throw new RuntimeException(e); }
        },4200);
        Looper.loop();
    }
}
