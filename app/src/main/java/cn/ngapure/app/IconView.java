package cn.ngapure.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Density-independent 24-unit line icons; independent of installed text fonts. */
public final class IconView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private String glyph;
    private int color = 0xFFE2D2AF;
    private float stroke = 1.8f;

    public IconView(Context context, String glyph) {
        super(context);
        this.glyph = glyph;
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    public void setGlyph(String value) { glyph = value; invalidate(); }
    public void setColor(int value) { color = value; invalidate(); }
    /** Width in the icon's 24 dp design coordinate space. */
    public void setStrokeWidthDp(float value) { stroke = Math.max(.75f, value); invalidate(); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int size = (int) (48 * getResources().getDisplayMetrics().density + .5f);
        setMeasuredDimension(resolveSize(size, widthSpec), resolveSize(size, heightSpec));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float size = Math.min(24 * density,
                Math.min(getWidth() - getPaddingLeft() - getPaddingRight(), getHeight() - getPaddingTop() - getPaddingBottom()));
        if (size <= 0) return;
        canvas.save();
        canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        canvas.scale(size / 24f, size / 24f);
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        path.reset();
        switch (glyph == null ? "" : glyph) {
            case "menu":
                line(4, 6, 20, 6); line(4, 12, 20, 12); line(4, 18, 20, 18); break;
            case "grid":
                paint.setStrokeWidth(Math.min(stroke, 1.4f));
                for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
                    float x = 3.5f + col * 6.5f, y = 3.5f + row * 6.5f;
                    canvas.drawRoundRect(x, y, x + 3.5f, y + 3.5f, .55f, .55f, paint);
                }
                break;
            case "search":
                canvas.drawCircle(10.7f, 10.7f, 6.6f, paint); line(15.7f, 15.7f, 21, 21); break;
            case "more":
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(12, 5, 1.65f, paint); canvas.drawCircle(12, 12, 1.65f, paint); canvas.drawCircle(12, 19, 1.65f, paint); break;
            case "back":
                line(15, 5, 8, 12); line(8, 12, 15, 19); break;
            case "close":
                line(6, 6, 18, 18); line(18, 6, 6, 18); break;
            case "check":
                line(4.5f, 12.5f, 9.5f, 17.5f); line(9.5f, 17.5f, 20, 7); break;
            case "bell":
                path.moveTo(5, 16.5f); path.cubicTo(7, 14.8f, 6.4f, 11.8f, 6.6f, 8.5f);
                path.cubicTo(7, 2.8f, 17, 2.8f, 17.4f, 8.5f);
                path.cubicTo(17.6f, 11.8f, 17, 14.8f, 19, 16.5f); path.close();
                oval.set(9.5f, 17, 14.5f, 21.5f); canvas.drawArc(oval, 15, 150, false, paint);
                line(12, 2.8f, 12, 4); break;
            case "edit":
                path.moveTo(13.5f, 4); path.lineTo(5, 4); path.lineTo(5, 20);
                path.lineTo(20, 20); path.lineTo(20, 12.5f);
                path.moveTo(9.5f, 14.5f); path.lineTo(10.5f, 10.5f); path.lineTo(18.7f, 2.3f);
                path.lineTo(21.7f, 5.3f); path.lineTo(13.5f, 13.5f); path.close();
                line(16.8f, 4.2f, 19.8f, 7.2f); break;
            case "calendar":
                canvas.drawRoundRect(3.5f, 5, 20.5f, 21, 1.8f, 1.8f, paint);
                line(3.5f, 9.5f, 20.5f, 9.5f); line(8, 2.5f, 8, 6.7f); line(16, 2.5f, 16, 6.7f);
                line(8, 15, 11, 18); line(11, 18, 16.5f, 12.5f); break;
            case "star":
                for (int i = 0; i < 10; i++) {
                    double a = -Math.PI / 2 + i * Math.PI / 5;
                    float r = i % 2 == 0 ? 9.6f : 4.4f;
                    float x = 12 + (float) Math.cos(a) * r, y = 12 + (float) Math.sin(a) * r;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close(); break;
            case "community":
                for (int row = 0; row < 2; row++) for (int col = 0; col < 2; col++) {
                    float x = 3.5f + col * 10, y = 3.5f + row * 10;
                    canvas.drawRoundRect(x, y, x + 7, y + 7, 1.2f, 1.2f, paint);
                }
                break;
            case "qr":
                paint.setStrokeWidth(Math.min(stroke, 1.5f));
                canvas.drawRect(3.5f, 3.5f, 9.5f, 9.5f, paint);
                canvas.drawRect(14.5f, 3.5f, 20.5f, 9.5f, paint);
                canvas.drawRect(3.5f, 14.5f, 9.5f, 20.5f, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRect(5.5f, 5.5f, 7.5f, 7.5f, paint);
                canvas.drawRect(16.5f, 5.5f, 18.5f, 7.5f, paint);
                canvas.drawRect(5.5f, 16.5f, 7.5f, 18.5f, paint);
                canvas.drawRect(14, 14, 17, 17, paint); canvas.drawRect(19, 13, 21, 16, paint);
                canvas.drawRect(14, 19, 16, 21, paint); canvas.drawRect(18, 18, 21, 21, paint);
                paint.setStyle(Paint.Style.STROKE); break;
            case "arrowRight":
                line(9, 5, 16, 12); line(16, 12, 9, 19); break;
            case "home":
                path.moveTo(3, 10.5f); path.lineTo(12, 3); path.lineTo(21, 10.5f);
                path.moveTo(5.5f, 9); path.lineTo(5.5f, 21); path.lineTo(10, 21); path.lineTo(10, 14);
                path.lineTo(14, 14); path.lineTo(14, 21); path.lineTo(18.5f, 21); path.lineTo(18.5f, 9); break;
            case "user":
                canvas.drawCircle(12, 7.5f, 4, paint); oval.set(4, 13, 20, 26); canvas.drawArc(oval, 180, 180, false, paint); break;
            case "heart":
                path.moveTo(12, 20); path.cubicTo(9, 17.5f, 3, 13.5f, 3, 8.5f);
                path.cubicTo(3, 3.3f, 9.6f, 2.2f, 12, 7); path.cubicTo(14.4f, 2.2f, 21, 3.3f, 21, 8.5f);
                path.cubicTo(21, 13.5f, 15, 17.5f, 12, 20); path.close(); break;
            case "settings":
                canvas.drawCircle(12, 12, 3.2f, paint);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    line(12 + (float) Math.cos(a) * 7.1f, 12 + (float) Math.sin(a) * 7.1f,
                            12 + (float) Math.cos(a) * 10, 12 + (float) Math.sin(a) * 10);
                }
                canvas.drawCircle(12, 12, 7.1f, paint); break;
            case "refresh":
                oval.set(4, 4, 20, 20); canvas.drawArc(oval, -55, 285, false, paint);
                line(20, 4, 20, 10); line(20, 10, 14, 10); break;
            default:
                canvas.drawCircle(12, 12, 8, paint); break;
        }
        canvas.drawPath(path, paint); canvas.restore();
    }

    private void line(float x, float y, float x2, float y2) { path.moveTo(x, y); path.lineTo(x2, y2); }
}
