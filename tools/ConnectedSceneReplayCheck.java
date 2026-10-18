import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import me.laumss.mosaic.engine.BoardEngine;
import me.laumss.mosaic.engine.ConnectedCardConstraints;

/** Replay a caller-supplied scene copy in a separate process; never writes board data. */
public final class ConnectedSceneReplayCheck {
    public static void main(String[] args) throws Exception {
        if (Typeface.DEFAULT == null) throw new AssertionError("font unavailable");
        BoardEngine e = BoardEngine.INSTANCE;
        int ops = e.applyOpsBytes(Files.readAllBytes(Paths.get(args[0])));
        if (ops == 0) throw new AssertionError("scene did not load");
        int cards = 0, moves = 0, bridgeStrokes = 0;
        long maxCapture = 0, maxWarmCapture = 0;
        ArrayList<Long> times = new ArrayList<>();
        for (BoardEngine.StrokeRec s : e.getStrokes().values()) if (s.getConnectionId() != null) bridgeStrokes++;
        for (BoardEngine.CardRec card : e.getCards().values()) {
            long before = SystemClock.uptimeMillis();
            ConnectedCardConstraints g = ConnectedCardConstraints.Companion.capture(card);
            long capture = SystemClock.uptimeMillis() - before;
            maxCapture = Math.max(maxCapture, capture);
            if (g.isEmpty()) continue;
            before = SystemClock.uptimeMillis();
            ConnectedCardConstraints.Companion.capture(card);
            maxWarmCapture = Math.max(maxWarmCapture, SystemClock.uptimeMillis()-before);
            cards++;
            RectF start = new RectF(card.getX(),card.getY(),card.getX()+card.getWidth(),card.getY()+card.getHeight());
            if (!g.isValid(start)) throw new AssertionError("initial geometry rejected: "+card.getId());
            for (int direction=0; direction<8; direction++) {
                RectF previous = new RectF(start);
                for (int step=1; step<=12; step++) {
                    float delta = step*12f;
                    RectF target = new RectF(start);
                    switch (direction) {
                        case 0: target.offset(delta,0); break;
                        case 1: target.offset(-delta,0); break;
                        case 2: target.offset(0,delta); break;
                        case 3: target.offset(0,-delta); break;
                        case 4: target.right += delta; break;
                        case 5: target.right = Math.max(target.left+40,target.right-delta); break;
                        case 6: target.bottom += delta; break;
                        case 7: target.bottom = Math.max(target.top+40,target.bottom-delta); break;
                    }
                    before = SystemClock.uptimeMillis();
                    g.constrain(previous,target,previous);
                    times.add(SystemClock.uptimeMillis()-before);
                    if (!g.isValid(previous)) throw new AssertionError("invalid result: "+card.getId());
                    moves++;
                }
            }
        }
        if (cards == 0 || bridgeStrokes == 0) throw new AssertionError("scene must contain linked cards with handwriting");
        Collections.sort(times);
        long maxMove=times.get(times.size()-1), p95=times.get(times.size()*95/100);
        if (maxCapture >= 1000 || maxMove >= 1000) throw new AssertionError("gesture work exceeded 1s: capture="+maxCapture+" move="+maxMove);
        System.out.println("SCENE_REPLAY_PASSED cards="+cards+" bridgeStrokes="+bridgeStrokes+" operations="+moves+
            " maxCaptureMs="+maxCapture+" maxWarmCaptureMs="+maxWarmCapture+" moveP95Ms="+p95+" moveMaxMs="+maxMove);
    }
}
