package cn.ngapure.app;

/** Pure window-coordinate hinge avoidance shared by the activity and floating surfaces. */
public final class FoldGeometry {
    private FoldGeometry() { }
    /** Insets occupy window edges, not every pane edge; internal hinges must not duplicate status/navigation padding. */
    public static int[] paneInsets(int width,int height,int left,int top,int right,int bottom,int insetLeft,int insetTop,int insetRight,int insetBottom) {
        return new int[]{Math.max(0,insetLeft-Math.max(0,left)),Math.max(0,insetTop-Math.max(0,top)),
            Math.max(0,insetRight-Math.max(0,width-right)),Math.max(0,insetBottom-Math.max(0,height-bottom))};
    }
    /** Returns screen x/y/width/height of a bottom sheet wholly contained in the available pane. */
    public static int[] sheet(int left,int top,int right,int bottom,int capWidth,int wantedHeight,int margin) {
        int width=Math.max(1,right-left),height=Math.max(1,bottom-top);int inset=Math.max(0,Math.min(margin,Math.min((width-1)/2,(height-1)/2)));
        int measuredWidth=Math.max(1,Math.min(Math.max(1,capWidth),width-2*inset));
        int measuredHeight=Math.max(1,Math.min(Math.max(1,wantedHeight),height-2*inset));
        return new int[]{left+(width-measuredWidth)/2,bottom-inset-measuredHeight,measuredWidth,measuredHeight};
    }
    /** Returns left, top, right, bottom of the largest unoccluded pane. Zero-width creases still split. */
    public static int[] safePane(int width, int height, int left, int top, int right, int bottom, boolean separating) {
        int[] whole = {0, 0, Math.max(0, width), Math.max(0, height)};
        if (!separating || width <= 0 || height <= 0 || right < left || bottom < top) return whole;
        left = Math.max(0, Math.min(width, left)); right = Math.max(0, Math.min(width, right));
        top = Math.max(0, Math.min(height, top)); bottom = Math.max(0, Math.min(height, bottom));
        if (top == 0 && bottom == height && left > 0 && right < width) {
            return left >= width - right ? new int[]{0, 0, left, height} : new int[]{right, 0, width, height};
        }
        if (left == 0 && right == width && top > 0 && bottom < height) {
            return top >= height - bottom ? new int[]{0, 0, width, top} : new int[]{0, bottom, width, height};
        }
        return whole;
    }
}
