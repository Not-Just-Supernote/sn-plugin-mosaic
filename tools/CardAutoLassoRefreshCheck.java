import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import kotlin.LazyKt;
import me.laumss.mosaic.eink.BoardPresentation;
import me.laumss.mosaic.input.ToolArbiter;
import me.laumss.mosaic.render.BoardContentView;

/** Runs against the built APK in a separate app_process, without a plugin window or board files. */
public final class CardAutoLassoRefreshCheck {
    private static int checks;
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
    private static void mode(BoardPresentation p, Integer expected, String label) {
        Integer actual = p.getState().getEinkMode();
        check(expected == null ? actual == null : expected.equals(actual), label + ": " + actual);
    }
    public static void main(String[] args) throws Exception {
        if (Typeface.DEFAULT == null) throw new AssertionError("font unavailable");
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        Object thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
        // No React module instance exists here: mode requests cannot touch vendor services.
        Field instance = Class.forName("me.laumss.mosaic.MosaicEinkRefreshModule").getDeclaredField("instance");
        instance.setAccessible(true);
        check(instance.get(null) == null, "isolated process has no native refresh module");
        BoardPresentation p = new BoardPresentation(new Handler(Looper.getMainLooper()),
                new BoardContentView(context), () -> true, "test-gesture", 600L);
        Method settle = BoardPresentation.class.getDeclaredMethod("settle");
        settle.setAccessible(true);
        BoardPresentation.Reason lasso = BoardPresentation.Reason.LASSO;
        BoardPresentation.Reason adjust = BoardPresentation.Reason.CARD_ADJUST;
        BoardPresentation.Reason move = BoardPresentation.Reason.CARD_MOVE;
        mode(p, null, "initial");
        p.acquire(lasso); mode(p, 4, "long-press/selection holds A2");
        p.acquire(adjust); mode(p, 4, "holding card before movement remains A2");
        p.acquire(move); mode(p, 7, "card movement overrides held A2 with AUTO");
        p.release(adjust, false); mode(p, 7, "AUTO remains during drag");
        p.release(move, false); mode(p, 4, "release returns to A2, not host");
        check(!p.getState().getMovingCards(), "release restores static appearance");
        settle.invoke(p); mode(p, 4, "settle cannot release held A2");
        p.acquire(adjust); mode(p, 4, "resize uses A2");
        p.release(adjust, false); mode(p, 4, "resize completion retains A2");
        p.acquire(move); p.release(move, false); mode(p, 4, "cancel drag retains A2");
        p.acquire(BoardPresentation.Reason.PAN_ZOOM); mode(p, 4, "viewport pan stays A2");
        p.release(BoardPresentation.Reason.PAN_ZOOM, true); settle.invoke(p);
        mode(p, 4, "viewport settle retains A2");

        // Exercise the actual controller's explicit-tool policy, including unchanged base tool.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field field = unsafeClass.getDeclaredField("theUnsafe"); field.setAccessible(true);
        Object unsafe = field.get(null);
        Class<?> controllerClass = Class.forName("me.laumss.mosaic.board.BoardInteractionController");
        Object controller = unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, controllerClass);
        Field delegate = controllerClass.getDeclaredField("presentation$delegate"); delegate.setAccessible(true);
        delegate.set(controller, LazyKt.lazyOf(p));
        Method manual = controllerClass.getDeclaredMethod("updateManualToolRefresh", ToolArbiter.State.class, String.class);
        manual.setAccessible(true);
        ToolArbiter arbiter = new ToolArbiter();
        ToolArbiter.Transition unchanged = arbiter.clearBase();
        check(!unchanged.getChanged(), "long-press may leave arbiter base unchanged");
        manual.invoke(controller, unchanged.getAfter(), "pen-up"); mode(p, 4, "automatic pen-up retains A2");
        manual.invoke(controller, unchanged.getAfter(), "toolbar-write"); mode(p, null, "explicit pen choice releases held A2");
        ToolArbiter.Transition select = arbiter.toggleBase(ToolArbiter.Tool.LASSO);
        manual.invoke(controller, select.getAfter(), "toolbar-lasso"); mode(p, 4, "manual lasso holds A2");
        p.acquire(BoardPresentation.Reason.TOOL);
        ToolArbiter.Transition erase = arbiter.toggleBase(ToolArbiter.Tool.ERASER);
        manual.invoke(controller, erase.getAfter(), "toolbar-eraser"); mode(p, 7, "manual eraser resumes its own mode");
        p.release(BoardPresentation.Reason.TOOL, false); mode(p, null, "other tool release restores host");
        p.acquire(lasso); p.reset("detach"); mode(p, null, "plugin teardown releases A2");
        System.out.println("CARD_AUTO_LASSO_REFRESH_CHECKS_PASSED=" + checks);
        System.exit(0);
    }
}
