import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import me.laumss.mosaic.engine.BoardEngine;
import me.laumss.mosaic.engine.BoardGeometry;
import me.laumss.mosaic.engine.CardSizeConstraints;
import me.laumss.mosaic.engine.ConnectionBindings;

/** Headless app_process regression check: touches no existing board or plugin data. */
public final class ConnectedCardResizeCheck {
    private static int checks;
    private static final BoardEngine.CardRec CARD = card("");
    private static final RectF INK = new RectF(40, 20, 240, 180);

    private static BoardEngine.CardRec card(String text) {
        return new BoardEngine.CardRec("a", 200, 100, 300, 250, 0,
                "text", text, "", "", "", "", "", 1f);
    }

    private static void rect(String label, RectF input, BoardGeometry.Handle handle,
                             boolean proportional, RectF ink, float l, float t, float r, float b) {
        CardSizeConstraints.INSTANCE.constrainRect(CARD, input, handle, proportional, ink);
        if (Math.abs(input.left-l)>0.001f || Math.abs(input.top-t)>0.001f ||
                Math.abs(input.right-r)>0.001f || Math.abs(input.bottom-b)>0.001f) {
            throw new AssertionError(label + ": " + input);
        }
        checks++;
    }

    public static void main(String[] args) {
        // app_process does not perform Zygote's font preload for a headless check.
        if (Typeface.DEFAULT == null) throw new AssertionError("default font unavailable");
        rect("right stops at ink", new RectF(200,100,260,350), BoardGeometry.Handle.RIGHT,
                false, INK, 200,100,440,350);
        rect("left preserves right anchor and local ink", new RectF(460,100,500,350), BoardGeometry.Handle.LEFT,
                false, INK, 260,100,500,350);
        rect("bottom stops at ink", new RectF(200,100,500,140), BoardGeometry.Handle.BOTTOM,
                false, INK, 200,100,500,280);
        rect("top preserves bottom anchor and local ink", new RectF(200,300,500,350), BoardGeometry.Handle.TOP,
                false, INK, 200,170,500,350);
        rect("empty card may shrink", new RectF(200,100,210,110), BoardGeometry.Handle.BOTTOM_RIGHT,
                false, null, 200,100,280,180);
        rect("proportional size still covers ink", new RectF(200,100,290,175), BoardGeometry.Handle.BOTTOM_RIGHT,
                true, INK, 200,100,440,300);
        StringBuilder text = new StringBuilder();
        for (int i=0; i<40; i++) text.append("content\n");
        RectF textRect = new RectF(200,100,280,180);
        CardSizeConstraints.INSTANCE.constrainRect(card(text.toString()), textRect,
                BoardGeometry.Handle.BOTTOM_RIGHT, false, null);
        if (textRect.height() <= 250) throw new AssertionError("text was clipped: " + textRect);
        checks++;
        List<String> group = ConnectionBindings.INSTANCE.expand(Arrays.asList("a"),
                new HashSet<>(Arrays.asList("a","b","c")),
                Arrays.asList(new BoardEngine.ConnectionRec("ab","a","b",true)));
        if (!group.equals(Arrays.asList("a","b"))) throw new AssertionError("binding changed: " + group);
        checks++;
        System.out.println("CONNECTED_RESIZE_CHECKS_PASSED=" + checks);
    }
}
