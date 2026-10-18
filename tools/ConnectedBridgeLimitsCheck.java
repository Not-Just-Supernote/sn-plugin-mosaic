import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.util.Arrays;
import java.util.Collections;
import me.laumss.mosaic.engine.ConnectedCardConstraints;
import me.laumss.mosaic.engine.NeckGeometry;

/** Headless checks of the real bridge geometry, without opening a user board. */
public final class ConnectedBridgeLimitsCheck {
    private static int checks;
    private static final RectF START = new RectF(0,0,200,200);
    private static final RectF RIGHT = new RectF(240,0,440,200);
    private static ConnectedCardConstraints.Link link(String id, RectF other, boolean from, Path ink) {
        return new ConnectedCardConstraints.Link(id, other, from, false, ink, Collections.emptyList());
    }
    private static ConnectedCardConstraints guard(Path ink) {
        return new ConnectedCardConstraints(START, Arrays.asList(link("ab", RIGHT, true, ink)));
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static boolean containsInk(RectF card, Path ink) {
        Path bridge = NeckGeometry.INSTANCE.build(card, RIGHT, 160);
        if (bridge == null) return false;
        Path cards = new Path();
        cards.addRect(card, Path.Direction.CW); cards.addRect(RIGHT, Path.Direction.CW);
        Path outside = new Path(ink);
        return bridge.op(cards, Path.Op.DIFFERENCE) &&
                outside.op(bridge, Path.Op.DIFFERENCE) && outside.isEmpty();
    }
    public static void main(String[] args) {
        if (Typeface.DEFAULT == null) throw new AssertionError("font unavailable");
        ConnectedCardConstraints emptyBridge = guard(new Path());
        RectF out = new RectF();
        check(emptyBridge.isValid(START), "initial empty bridge rejected");
        emptyBridge.constrain(START, new RectF(0,0,40,200), out);
        float gap = NeckGeometry.INSTANCE.signedEdgeDistance(out, RIGHT);
        check(emptyBridge.isValid(out) && gap > 159 && gap < 160, "resize must stop before break: " + out);
        emptyBridge.constrain(START, new RectF(-500,0,-300,200), out);
        gap = NeckGeometry.INSTANCE.signedEdgeDistance(out, RIGHT);
        check(emptyBridge.isValid(out) && gap > 159 && gap < 160, "move must stop before break: " + out);
        Path ink = new Path(); ink.addRect(214,94,226,106,Path.Direction.CW);
        ConnectedCardConstraints written = guard(ink);
        check(containsInk(START, ink), "test ink must start fully inside the bridge");
        check(written.isValid(START), "initial written bridge rejected");
        written.constrain(START, new RectF(0,0,230,200), out);
        check(written.isValid(out) && out.right > 200 && out.right <= 214.1f, "resize covered bridge ink: " + out);
        RectF limit = new RectF(out);
        written.constrain(limit, new RectF(0,0,205,200), out);
        check(written.isValid(out) && Math.abs(out.right-205)<0.001f, "could not move back from limit: " + out);
        // The middle of the bridge can remain broad even when this card becomes short.
        // Put ink near its upper attachment to exercise actual clipping by the curve.
        Path upperInk = new Path(); upperInk.addRect(204,40,212,52,Path.Direction.CW);
        ConnectedCardConstraints upper = guard(upperInk);
        RectF narrow = new RectF(0,185,200,200);
        check(containsInk(START, upperInk) && !containsInk(narrow, upperInk), "narrowing fixture must clip visible ink");
        upper.constrain(START, narrow, out);
        check(upper.isValid(out) && containsInk(out, upperInk) && out.top < 185, "narrowing clipped bridge ink: " + out);
        RectF down = new RectF(0,350,200,550);
        check(!containsInk(down, upperInk), "vertical movement fixture must clip visible ink");
        upper.constrain(START, down, out);
        check(upper.isValid(out) && containsInk(out, upperInk) && out.top < 350, "independent vertical movement clipped ink: " + out);
        written.constrain(START, new RectF(800,0,1000,200), out);
        check(written.isValid(out) && out.left <= 14.1f, "large pointer jump crossed ink: " + out);
        ConnectedCardConstraints two = new ConnectedCardConstraints(START, Arrays.asList(
                link("left",new RectF(-240,0,-40,200),false,new Path()),
                link("right",RIGHT,true,new Path())));
        two.constrain(START,new RectF(300,0,500,200),out);
        check(two.isValid(out) && out.left > 119 && out.left < 120, "not all incident bridges constrained: " + out);
        ConnectedCardConstraints free = new ConnectedCardConstraints(START,Collections.emptyList());
        RectF target = new RectF(1000,900,1200,1100);
        check(!free.constrain(START,target,out) && out.equals(target), "unconnected card constrained: " + out);
        check(RIGHT.equals(new RectF(240,0,440,200)), "neighbor geometry was mutated");
        System.out.println("BRIDGE_LIMIT_CHECKS_PASSED=" + checks);
    }
}
