#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
CHECK_DIR=$(mktemp -d "$PWD/build/regal-window-check.XXXXXX")
trap 'rm -rf "$CHECK_DIR"' EXIT
mkdir -p "$CHECK_DIR/src/android/"{content,os,util,view} "$CHECK_DIR/src/me/laumss/mosaic/eink" "$CHECK_DIR/classes"
cat > "$CHECK_DIR/src/android/content/Context.java" <<'JAVA'
package android.content; public class Context {}
JAVA
cat > "$CHECK_DIR/src/android/os/SystemClock.java" <<'JAVA'
package android.os; public class SystemClock { public static long now; public static long uptimeMillis(){return now;} }
JAVA
cat > "$CHECK_DIR/src/android/util/Log.java" <<'JAVA'
package android.util; public class Log { public static int i(String tag,String text){return 0;} }
JAVA
cat > "$CHECK_DIR/src/android/view/View.java" <<'JAVA'
package android.view;
import android.content.Context;
import android.os.SystemClock;
import java.util.*;
public class View {
 public static final int VISIBLE=0;
 public interface OnAttachStateChangeListener {void onViewAttachedToWindow(View v);void onViewDetachedFromWindow(View v);}
 private record Job(Runnable task,long at,long seq){}
 private final PriorityQueue<Job> jobs=new PriorityQueue<>(Comparator.comparingLong(Job::at).thenComparingLong(Job::seq));
 private long seq;
 public boolean isAttachedToWindow(){return true;}
 public boolean isShown(){return true;}
 public boolean hasWindowFocus(){return true;}
 public int getWindowVisibility(){return VISIBLE;}
 public Context getContext(){return new Context();}
 public void addOnAttachStateChangeListener(OnAttachStateChangeListener l){}
 public boolean post(Runnable r){return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long ms){jobs.add(new Job(r,SystemClock.now+ms,seq++));return true;}
 public boolean removeCallbacks(Runnable r){jobs.removeIf(j->j.task()==r);return true;}
 public void advance(long until){while(!jobs.isEmpty()&&jobs.peek().at()<=until){Job j=jobs.remove();SystemClock.now=j.at();j.task().run();}SystemClock.now=until;}
}
JAVA
cat > "$CHECK_DIR/src/me/laumss/mosaic/MosaicEinkRefreshModule.java" <<'JAVA'
package me.laumss.mosaic;
import android.content.Context;
import java.util.*;
public class MosaicEinkRefreshModule {
 public static final Companion Companion=new Companion();
 public static class Companion {
  public final List<Boolean> events=new ArrayList<>();
  public boolean failEnable;
  public boolean requestRegalState(Context c,boolean enabled,String reason){events.add(enabled);return !(enabled&&failEnable);}
 }
}
JAVA
cat > "$CHECK_DIR/src/me/laumss/mosaic/eink/EinkRegionDriver.java" <<'JAVA'
package me.laumss.mosaic.eink;
import java.util.function.IntSupplier;
public class EinkRegionDriver {
 public static final EinkRegionDriver INSTANCE=new EinkRegionDriver();
 public static IntSupplier busy=()->0;
 public int displayBusy(){return busy.getAsInt();}
}
JAVA
STDLIB=$(python3 - <<'PY'
import glob,os
print(glob.glob(os.path.expanduser('~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.0.21/*/*.jar'))[0])
PY
)
COMPILED="$PWD/android/app/build/tmp/kotlin-classes/release"
find "$CHECK_DIR/src" -name '*.java' -print0 | xargs -0 javac -cp "$COMPILED:$STDLIB" -d "$CHECK_DIR/classes" tools/RegalRefreshWindowCheck.java
java -cp "$CHECK_DIR/classes:$COMPILED:$STDLIB" RegalRefreshWindowCheck
