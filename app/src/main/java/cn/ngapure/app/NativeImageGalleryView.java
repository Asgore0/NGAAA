package cn.ngapure.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.function.BooleanSupplier;

/** A bounded native pager. Fit images page horizontally; zoomed images retain their own pan/pinch. */
public final class NativeImageGalleryView extends FrameLayout implements AutoCloseable {
    public interface Host { void changed(ImageGalleryState state, boolean ready); }
    private final NativeImages images;
    private final BooleanSupplier current;
    private final Host host;
    private final FrameLayout pages;
    private final TextView counter, limit, previous, next;
    private final int ink, muted, outline, slop;
    private ImageGalleryState state;
    private Slot shown, neighbor;
    private ValueAnimator settling;
    private VelocityTracker velocity;
    private int animationEpoch, neighborDirection;
    private float downX, downY, downOffset, offset;
    private boolean dragging, multiTouch, closed;

    public NativeImageGalleryView(Context context, NativeImages images, ImageGalleryState state, BooleanSupplier current,
            Host host, int ink, int muted, int outline) {
        super(context);
        if (images == null || state == null || current == null || host == null) throw new IllegalArgumentException("缺少图片集");
        this.images=images;this.state=state;this.current=current;this.host=host;this.ink=ink;this.muted=muted;this.outline=outline;
        slop=ViewConfiguration.get(context).getScaledTouchSlop(); setClipChildren(true);setTag("native-image-gallery");
        pages=new FrameLayout(context);addView(pages,new LayoutParams(-1,-1));
        LinearLayout chrome=new LinearLayout(context);chrome.setOrientation(LinearLayout.VERTICAL);chrome.setGravity(Gravity.CENTER);
        LinearLayout nav=new LinearLayout(context);nav.setGravity(Gravity.CENTER);nav.setMinimumHeight(dp(48));
        GradientDrawable material=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{outline,outline & 0x00ffffff | 0xcc000000});
        material.setCornerRadius(dp(24));nav.setBackground(material);
        previous=control("‹","上一张图片");next=control("›","下一张图片");counter=control("","图片序号");counter.setTextSize(13);
        nav.addView(previous,new LinearLayout.LayoutParams(dp(48),dp(48)));nav.addView(counter,new LinearLayout.LayoutParams(-2,dp(48)));nav.addView(next,new LinearLayout.LayoutParams(dp(48),dp(48)));
        previous.setOnClickListener(v->previous());next.setOnClickListener(v->next());chrome.addView(nav,new LinearLayout.LayoutParams(-2,-2));
        limit=new TextView(context);limit.setTextColor(muted);limit.setTextSize(11);limit.setGravity(Gravity.CENTER);limit.setPadding(dp(8),dp(5),dp(8),0);chrome.addView(limit,new LinearLayout.LayoutParams(-1,-2));
        LayoutParams bar=new LayoutParams(-1,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);bar.setMargins(dp(12),dp(12),dp(12),0);addView(chrome,bar);
        shown=new Slot(state.index,state.viewport());pages.addView(shown,new LayoutParams(-1,-1));updateChrome();post(()->{if(live())shown.load();});
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private boolean live(){return !closed&&current.getAsBoolean();}
    private TextView control(String text,String description){TextView view=new TextView(getContext());view.setText(text);view.setTextColor(ink);view.setTextSize(27);view.setGravity(Gravity.CENTER);view.setContentDescription(description);view.setMinimumWidth(dp(48));view.setPadding(dp(10),0,dp(10),0);return view;}
    private void updateChrome(){counter.setText(state.counterLabel());counter.setContentDescription("第 "+(state.index+1)+" 张，共 "+state.urls.size()+" 张图片");previous.setEnabled(state.index>0);next.setEnabled(state.index<state.urls.size()-1);previous.setAlpha(previous.isEnabled()?1:.3f);next.setAlpha(next.isEnabled()?1:.3f);limit.setText(state.limitLabel());limit.setVisibility(state.truncated?VISIBLE:GONE);}
    public NativePictureView currentPicture(){return shown.picture;}
    public String currentUrl(){return state.currentUrl();}
    public ImageGalleryState snapshot(){return state.withViewport(shown.picture.viewportState());}
    public boolean ready(){return shown.ready;}
    public void previous(){step(-1);}
    public void next(){step(1);}
    private void step(int direction){if(!live())return;int index=state.index+direction;if(index<0||index>=state.urls.size())return;cancelSettling();showIndex(index);}
    private void changed(){if(live())host.changed(snapshot(),shown.ready);}
    private void showIndex(int index){
        Slot incoming=neighbor!=null&&neighbor.index==index?neighbor:null;
        if(neighbor!=null&&neighbor!=incoming){neighbor.close();pages.removeView(neighbor);}neighbor=null;neighborDirection=0;
        shown.close();pages.removeView(shown);state=state.withIndex(index);shown=incoming==null?new Slot(index,null):incoming;
        if(shown.getParent()==null)pages.addView(shown,new LayoutParams(-1,-1));shown.setTranslationX(0);offset=downOffset=0;dragging=false;multiTouch=false;updateChrome();shown.load();changed();
    }
    private boolean fit(){float[] viewport=shown.picture.viewportState();return viewport==null||viewport[0]<=1.005f;}
    @Override public void requestDisallowInterceptTouchEvent(boolean disallow){
        // NativePictureView requests ownership on DOWN; a fitted image still permits paging.
        super.requestDisallowInterceptTouchEvent(disallow&&(multiTouch||!fit()));
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        if(!live())return false;
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){cancelSettling();downX=event.getX();downY=event.getY();downOffset=offset;dragging=false;multiTouch=false;recycleVelocity();velocity=VelocityTracker.obtain();}
        if(event.getPointerCount()>1){multiTouch=true;if(dragging){dragging=false;settle(false,0);}}
        if(velocity!=null)velocity.addMovement(event);
        boolean handled=super.dispatchTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){
            if(offset!=0&&!dragging&&settling==null)settle(false,0);
            recycleVelocity();multiTouch=false;
        }
        return handled;
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event){
        if(!live()||state.urls.size()<2||multiTouch)return false;
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&!dragging&&fit()){
            float dx=event.getX()-downX,dy=event.getY()-downY;
            if(Math.abs(dx)>slop&&Math.abs(dx)>Math.abs(dy)*1.25f){dragging=true;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return true;}
        }
        return dragging;
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(!live())return false;
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_MOVE&&dragging&&!multiTouch)translate(downOffset+event.getX()-downX);
        else if((action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)&&dragging){
            float speed=0;if(velocity!=null){velocity.computeCurrentVelocity(1000);speed=velocity.getXVelocity();}
            boolean advance=action==MotionEvent.ACTION_UP&&(Math.abs(offset)>Math.max(dp(36),getWidth()*.2f)||Math.abs(speed)>dp(650)&&Math.abs(offset)>slop&&Math.signum(speed)==Math.signum(offset));
            dragging=false;settle(advance,speed);
        }
        return true;
    }
    private void translate(float requested){
        int width=getWidth();if(width<=0)return;int direction=requested<0?1:requested>0?-1:neighborDirection;
        boolean allowed=state.index+direction>=0&&state.index+direction<state.urls.size();
        float nextOffset=allowed?Math.max(-width,Math.min(width,requested)):Math.signum(requested)*Math.min(dp(40),(float)Math.sqrt(Math.abs(requested))*3);
        if(allowed&&direction!=0&&(neighbor==null||neighborDirection!=direction)){
            if(neighbor!=null){neighbor.close();pages.removeView(neighbor);}neighbor=new Slot(state.index+direction,null);neighborDirection=direction;pages.addView(neighbor,new LayoutParams(-1,-1));neighbor.load();
        }
        offset=nextOffset;shown.setTranslationX(offset);if(neighbor!=null)neighbor.setTranslationX(offset+neighborDirection*width);
    }
    private void settle(boolean advance,float speed){
        cancelSettling();final int direction=offset<0?1:-1;
        final boolean commit=advance&&state.index+direction>=0&&state.index+direction<state.urls.size();
        final float target=commit?-direction*getWidth():0;final int epoch=animationEpoch;
        settling=ValueAnimator.ofFloat(offset,target);settling.setDuration(Math.max(130,Math.min(260,Math.round(Math.abs(target-offset)/Math.max(1,getWidth())*240))));settling.setInterpolator(new DecelerateInterpolator(1.4f));
        settling.addUpdateListener(value->{if(epoch==animationEpoch&&!closed)translate((Float)value.getAnimatedValue());});
        settling.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator animation){if(epoch!=animationEpoch||closed)return;settling=null;if(!live())return;if(commit)showIndex(state.index+direction);else {offset=0;shown.setTranslationX(0);if(neighbor!=null){neighbor.close();pages.removeView(neighbor);neighbor=null;neighborDirection=0;}}}});settling.start();
    }
    private void cancelSettling(){animationEpoch++;if(settling!=null){ValueAnimator old=settling;settling=null;old.cancel();}}
    private void recycleVelocity(){if(velocity!=null){velocity.recycle();velocity=null;}}
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);if(ow>0&&(w!=ow||h!=oh)){cancelSettling();offset=0;if(shown!=null)shown.setTranslationX(0);if(neighbor!=null){neighbor.close();pages.removeView(neighbor);neighbor=null;neighborDirection=0;}}}
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);if(state.index>0)info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,"上一张图片"));if(state.index<state.urls.size()-1)info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,"下一张图片"));}
    @Override public boolean performAccessibilityAction(int action,android.os.Bundle arguments){if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD&&state.index<state.urls.size()-1){next();return true;}if(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD&&state.index>0){previous();return true;}return super.performAccessibilityAction(action,arguments);}
    @Override protected void onDetachedFromWindow(){close();super.onDetachedFromWindow();}
    @Override public void close(){if(closed)return;closed=true;cancelSettling();recycleVelocity();shown.close();if(neighbor!=null)neighbor.close();}
    private final class Slot extends FrameLayout {
        final int index;final NativePictureView picture;final TextView status;NativeImages.Request request;boolean ready,retired;
        Slot(int index,float[] viewport){super(NativeImageGalleryView.this.getContext());this.index=index;picture=new NativePictureView(getContext());addView(picture,new LayoutParams(-1,-1));if(viewport!=null)picture.restoreViewport(viewport);status=new TextView(getContext());status.setText("正在加载图片…");status.setTextColor(muted);status.setTextSize(14);status.setGravity(Gravity.CENTER);addView(status,new LayoutParams(-1,dp(80),Gravity.CENTER));status.setOnClickListener(v->{if(live()&&!retired){status.setText("正在加载图片…");load();}});}
        void load(){if(retired||ready||request!=null||!live())return;request=images.loadDrawable(state.urls.get(index),true,new NativeImages.DrawableCallback(){public void loaded(Drawable drawable){request=null;if(retired||!live())return;picture.show(drawable);ready=true;status.setVisibility(GONE);if(shown==Slot.this)changed();}public void failed(String message){request=null;if(retired||!live())return;status.setText("图片加载失败，点按重试");if(shown==Slot.this)changed();}});}
        void close(){retired=true;if(request!=null){request.cancel();request=null;}Drawable drawable=picture.getDrawable();if(drawable instanceof Animatable)((Animatable)drawable).stop();picture.setImageDrawable(null);ready=false;}
    }
}
