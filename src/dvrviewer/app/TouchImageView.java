package dvrviewer.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * An ImageView that supports pinch-to-zoom, pan, double-tap zoom, a single-tap
 * callback (used to exit the single-camera view) and a horizontal-fling
 * callback (used to switch channels when not zoomed in). Zoom/pan state is
 * preserved across snapshot refreshes of the same resolution.
 */
public class TouchImageView extends ImageView {

    public interface Listener {
        void onSwipe(int dir);   // -1 = previous, +1 = next
        void onTap();
    }

    private Listener listener;
    private boolean allowSwipe = true;
    private final Matrix matrix = new Matrix();
    private final Matrix base = new Matrix();
    private final float[] vals = new float[9];
    private final float minScale = 1f, maxScale = 6f;
    private float curScale = 1f;
    private ScaleGestureDetector sgd;
    private GestureDetector gd;
    private int vw, vh, curDw, curDh;
    private boolean baseSet = false;

    public TouchImageView(Context c) {
        super(c);
        super.setScaleType(ScaleType.MATRIX);
        setClickable(true);

        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float f = d.getScaleFactor();
                float target = curScale * f;
                if (target < minScale) f = minScale / curScale;
                if (target > maxScale) f = maxScale / curScale;
                curScale *= f;
                matrix.postScale(f, f, d.getFocusX(), d.getFocusY());
                fixTrans();
                setImageMatrix(matrix);
                return true;
            }
        });

        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener != null) listener.onTap();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (curScale > minScale * 1.1f) {
                    resetZoom();
                } else {
                    curScale = 2.5f;
                    matrix.set(base);
                    matrix.postScale(2.5f, 2.5f, e.getX(), e.getY());
                    fixTrans();
                    setImageMatrix(matrix);
                }
                return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (curScale > minScale * 1.05f) {
                    matrix.postTranslate(-dx, -dy);
                    fixTrans();
                    setImageMatrix(matrix);
                    return true;
                }
                return false;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (allowSwipe && curScale <= minScale * 1.05f
                        && Math.abs(vx) > Math.abs(vy) * 1.3f && Math.abs(vx) > 700f) {
                    if (listener != null) listener.onSwipe(vx < 0 ? +1 : -1);
                    return true;
                }
                return false;
            }
        });
    }

    public void setListener(Listener l) { listener = l; }

    public void setSwipeEnabled(boolean b) { allowSwipe = b; }

    @Override public boolean onTouchEvent(MotionEvent ev) {
        sgd.onTouchEvent(ev);
        gd.onTouchEvent(ev);
        // Cooperate with a scrolling parent (e.g. the portrait list): only hold on to
        // the gesture while actually zoomed in or pinching, so a plain vertical drag
        // still scrolls the list.
        android.view.ViewParent p = getParent();
        if (p != null) {
            boolean keep = curScale > minScale * 1.05f || sgd.isInProgress() || ev.getPointerCount() > 1;
            p.requestDisallowInterceptTouchEvent(keep);
        }
        return true;
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        vw = w; vh = h;
        baseSet = false;
        curScale = 1f;
        fitBase();
    }

    @Override public void setImageBitmap(Bitmap bm) {
        int pdw = curDw, pdh = curDh;
        super.setImageBitmap(bm);
        Drawable d = getDrawable();
        curDw = d != null ? d.getIntrinsicWidth() : 0;
        curDh = d != null ? d.getIntrinsicHeight() : 0;
        // Only re-fit when the frame size changed (or first frame); otherwise
        // keep the user's current zoom/pan across refreshes.
        if (!baseSet || curDw != pdw || curDh != pdh) fitBase();
        else setImageMatrix(matrix);
    }

    public void resetZoom() {
        curScale = 1f;
        matrix.set(base);
        setImageMatrix(matrix);
    }

    private void fitBase() {
        Drawable d = getDrawable();
        if (d == null || vw == 0 || vh == 0) return;
        int dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        float scale = Math.min((float) vw / dw, (float) vh / dh);
        base.reset();
        base.postScale(scale, scale);
        base.postTranslate((vw - dw * scale) / 2f, (vh - dh * scale) / 2f);
        matrix.set(base);
        if (curScale != 1f) matrix.postScale(curScale, curScale, vw / 2f, vh / 2f);
        fixTrans();
        setImageMatrix(matrix);
        baseSet = true;
    }

    private void fixTrans() {
        Drawable d = getDrawable();
        if (d == null) return;
        matrix.getValues(vals);
        float transX = vals[Matrix.MTRANS_X], transY = vals[Matrix.MTRANS_Y];
        float w = d.getIntrinsicWidth() * vals[Matrix.MSCALE_X];
        float h = d.getIntrinsicHeight() * vals[Matrix.MSCALE_Y];
        matrix.postTranslate(fix(transX, vw, w), fix(transY, vh, h));
    }

    private float fix(float trans, float viewSize, float contentSize) {
        float minTrans, maxTrans;
        if (contentSize <= viewSize) { minTrans = maxTrans = (viewSize - contentSize) / 2f; }
        else { minTrans = viewSize - contentSize; maxTrans = 0; }
        if (trans < minTrans) return minTrans - trans;
        if (trans > maxTrans) return maxTrans - trans;
        return 0;
    }
}
