package cn.ngapure.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** Native, draggable/pinchable square avatar crop with a circular preview guide. */
public final class NativeAvatarCropView extends View {
    private Bitmap image;private final Paint paint=new Paint(3);private final ScaleGestureDetector scaling;
    private float zoom=1,offsetX,offsetY,lastX,lastY;private int pointer=-1;
    private float[] pendingTransform;
    public NativeAvatarCropView(Context context,Bitmap bitmap){super(context);if(bitmap==null)throw new IllegalArgumentException("缺少图片");image=bitmap;setContentDescription("头像裁剪预览，可拖动和双指缩放");setFocusable(true);scaling=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector detector){if(image==null)return false;zoom=Math.max(1,Math.min(6,zoom*detector.getScaleFactor()));clamp();invalidate();return true;}});}
    private float side(){return Math.max(1,Math.min(getWidth(),getHeight())-24*getResources().getDisplayMetrics().density);}
    private float scale(){return side()/Math.min(image.getWidth(),image.getHeight())*zoom;}
    private void clamp(){if(image==null)return;applyPendingTransform();float s=scale(),half=side()/2;offsetX=Math.max(half-image.getWidth()*s/2,Math.min(image.getWidth()*s/2-half,offsetX));offsetY=Math.max(half-image.getHeight()*s/2,Math.min(image.getHeight()*s/2-half,offsetY));}
    /** Fractions of the scaled image, so resizing never reuses offsets from a different density or pane. */
    public float[] saveTransform(){if(pendingTransform!=null)return pendingTransform.clone();if(image==null)return new float[]{1,0,0};clamp();float s=scale();return new float[]{zoom,offsetX/(image.getWidth()*s),offsetY/(image.getHeight()*s)};}
    public void restoreTransform(float[] value){
        if(value==null||value.length!=3||!finite(value[0])||!finite(value[1])||!finite(value[2])||value[0]<1||value[0]>6||Math.abs(value[1])>.5f||Math.abs(value[2])>.5f)return;
        pendingTransform=value.clone();applyPendingTransform();invalidate();
    }
    private static boolean finite(float value){return !Float.isNaN(value)&&!Float.isInfinite(value);}
    private void applyPendingTransform(){if(image==null||pendingTransform==null||getWidth()<1||getHeight()<1)return;float[] value=pendingTransform;pendingTransform=null;zoom=value[0];float s=scale();offsetX=value[1]*image.getWidth()*s;offsetY=value[2]*image.getHeight()*s;}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        super.onSizeChanged(w,h,oldw,oldh);if(image==null)return;
        if(pendingTransform==null&&oldw>0&&oldh>0){float oldSide=Math.max(1,Math.min(oldw,oldh)-24*getResources().getDisplayMetrics().density),s=oldSide/Math.min(image.getWidth(),image.getHeight())*zoom;pendingTransform=new float[]{zoom,offsetX/(image.getWidth()*s),offsetY/(image.getHeight()*s)};}
        clamp();
    }
    /** Called only after the crop window is removed. Late draw/touch callbacks become harmless. */
    public void releaseImage(){Bitmap previous=image;image=null;pendingTransform=null;if(previous!=null&&!previous.isRecycled())previous.recycle();invalidate();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);canvas.drawColor(0xff14202d);if(image==null)return;clamp();float cx=getWidth()/2f,cy=getHeight()/2f,s=scale(),half=side()/2;int save=canvas.save();canvas.clipRect(cx-half,cy-half,cx+half,cy+half);canvas.translate(cx+offsetX,cy+offsetY);canvas.scale(s,s);canvas.drawBitmap(image,-image.getWidth()/2f,-image.getHeight()/2f,paint);canvas.restoreToCount(save);Path shade=new Path();shade.setFillType(Path.FillType.EVEN_ODD);shade.addRect(cx-half,cy-half,cx+half,cy+half,Path.Direction.CW);shade.addCircle(cx,cy,half,Path.Direction.CW);paint.setColor(0x77000000);canvas.drawPath(shade,paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2*getResources().getDisplayMetrics().density);paint.setColor(Color.WHITE);canvas.drawCircle(cx,cy,half-2,paint);paint.setStyle(Paint.Style.FILL);}
    @Override public boolean onTouchEvent(MotionEvent event){if(image==null)return false;scaling.onTouchEvent(event);switch(event.getActionMasked()){case MotionEvent.ACTION_DOWN:pointer=event.getPointerId(0);lastX=event.getX();lastY=event.getY();if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return true;case MotionEvent.ACTION_MOVE:int at=event.findPointerIndex(pointer);if(at>=0&&!scaling.isInProgress()){offsetX+=event.getX(at)-lastX;offsetY+=event.getY(at)-lastY;clamp();invalidate();}if(at>=0){lastX=event.getX(at);lastY=event.getY(at);}return true;case MotionEvent.ACTION_POINTER_UP:if(event.getPointerId(event.getActionIndex())==pointer){int next=event.getActionIndex()==0?1:0;pointer=event.getPointerId(next);lastX=event.getX(next);lastY=event.getY(next);}return true;case MotionEvent.ACTION_UP:performClick();pointer=-1;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);return true;case MotionEvent.ACTION_CANCEL:pointer=-1;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);return true;default:return true;}}
    @Override public boolean performClick(){super.performClick();return true;}
    public Bitmap crop(){if(image==null)throw new IllegalStateException("头像预览已关闭，请重新选择");clamp();float outputScale=180f/side(),s=scale()*outputScale;Bitmap result=Bitmap.createBitmap(180,180,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(result);canvas.drawColor(Color.WHITE);canvas.translate(90+offsetX*outputScale,90+offsetY*outputScale);canvas.scale(s,s);canvas.drawBitmap(image,-image.getWidth()/2f,-image.getHeight()/2f,paint);return result;}
}
