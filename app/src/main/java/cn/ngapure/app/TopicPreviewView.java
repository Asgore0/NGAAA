package cn.ngapure.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Visible, idle topic rows discover only their original post's images. Never reads a topic cover. */
public final class TopicPreviewView extends LinearLayout implements AutoCloseable {
    public interface Factory { void attach(LinearLayout row,String tid,String title); }
    public interface GalleryOpener { void open(ImageGalleryState state); }
    private final View row;
    private final String scope,tid,title;
    private final NativeImages images;
    private final TopicPreviewImages previews;
    private final BooleanSupplier current;
    private final Consumer<String> open;
    private final GalleryOpener galleryOpener;
    private final Consumer<VoteMetrics> votesObserver;
    private TextView label;
    private TopicPreviewImages.Result metadata;
    private LinearLayout gallery;
    private final ArrayList<ImageView> pictures=new ArrayList<>();
    private final ArrayList<TextView> statuses=new ArrayList<>();
    private final ArrayList<NativeImages.Request> media=new ArrayList<>();
    private final ArrayList<Boolean> mediaFailed=new ArrayList<>();
    private final ArrayList<Integer> mediaBusyFailures=new ArrayList<>();
    private final ArrayList<Long> mediaRetryAt=new ArrayList<>();
    private final Rect visibleBounds=new Rect();
    private List<String> urls=Collections.emptyList();
    private TopicPreviewImages.Request request;
    private ViewTreeObserver observer;
    private boolean closed,known,scheduled,visible;
    private int mediaEpoch,metadataEpoch;
    private int metadataFailures;
    private long metadataRetryAt,quietUntil;
    private static final long QUIET_MS=280, METADATA_RETRY_MS=30000;
    private static final int MAX_METADATA_FAILURES=2,MAX_MEDIA_BUSY_RETRIES=3;
    private final int muted,outline;
    private final Runnable discover=this::discover;
    private final Runnable visibilityCheck=this::checkVisibility;
    private final ViewTreeObserver.OnScrollChangedListener scrollListener=this::checkVisibility;
    private final ViewTreeObserver.OnWindowFocusChangeListener focusListener=focused->{if(focused)checkVisibility();else pause(false);};
    private final View.OnLayoutChangeListener layoutListener=(v,l,t,r,b,ol,ot,or,ob)->checkVisibility();

    public TopicPreviewView(Context context,View row,String scope,String tid,String title,
            NativeImages images,TopicPreviewImages previews,BooleanSupplier current,Consumer<String> open,
            int muted,int surface,int outline) {
        this(context,row,scope,tid,title,images,previews,current,open,muted,surface,outline,null);
    }
    public TopicPreviewView(Context context,View row,String scope,String tid,String title,
            NativeImages images,TopicPreviewImages previews,BooleanSupplier current,Consumer<String> open,
            int muted,int surface,int outline,GalleryOpener galleryOpener) {
        this(context,row,scope,tid,title,images,previews,current,open,muted,surface,outline,galleryOpener,null);
    }
    public TopicPreviewView(Context context,View row,String scope,String tid,String title,
            NativeImages images,TopicPreviewImages previews,BooleanSupplier current,Consumer<String> open,
            int muted,int surface,int outline,GalleryOpener galleryOpener,Consumer<VoteMetrics> votesObserver) {
        super(context);
        if(row==null||images==null||previews==null||current==null||open==null)throw new IllegalArgumentException("Missing topic preview owner");
        this.row=row;this.scope=scope;this.tid=tid;this.title=title==null?"":title;this.images=images;this.previews=previews;this.current=current;this.open=open;this.galleryOpener=galleryOpener;this.votesObserver=votesObserver;this.muted=muted;this.outline=outline;
        setOrientation(VERTICAL);setVisibility(GONE);setTag("topic-preview:"+tid);setPadding(0,dp(10),0,0);
        TopicPreviewImages.Result cached=previews.peek(scope,tid);if(cached!=null)acceptMetadata(cached);
    }
    /** Empty/unresolved rows allocate no badge or gallery controls on the first list frame. */
    private void ensureGalleryControls(){
        if(gallery!=null)return;
        Context context=getContext();
        label=new TextView(context);label.setTag("topic-preview-count:"+tid);label.setTextColor(0xffffffff);label.setTextSize(11);label.setMaxLines(2);label.setGravity(Gravity.CENTER);label.setPadding(dp(4),dp(2),dp(4),dp(2));label.setIncludeFontPadding(false);label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        GradientDrawable badge=new GradientDrawable();badge.setColor(0xc0232c3b);badge.setCornerRadius(dp(6));label.setBackground(badge);
        gallery=new LinearLayout(context);gallery.setOrientation(HORIZONTAL);addView(gallery,new LayoutParams(-1,-2));
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private boolean inViewport(){return isAttachedToWindow()&&hasWindowFocus()&&getWindowVisibility()==VISIBLE&&row.isShown()&&row.getGlobalVisibleRect(visibleBounds);}
    private void watch(){unwatch();observer=getViewTreeObserver();observer.addOnScrollChangedListener(scrollListener);observer.addOnWindowFocusChangeListener(focusListener);row.addOnLayoutChangeListener(layoutListener);post(visibilityCheck);}
    private void unwatch(){removeCallbacks(visibilityCheck);if(observer!=null&&observer.isAlive()){observer.removeOnScrollChangedListener(scrollListener);observer.removeOnWindowFocusChangeListener(focusListener);}observer=null;row.removeOnLayoutChangeListener(layoutListener);}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(!closed)watch();}
    @Override protected void onDetachedFromWindow(){pause();unwatch();super.onDetachedFromWindow();}
    private void checkVisibility(){
        if(closed)return;
        if(!hasWindowFocus()){if(visible||request!=null||scheduled)pause(false);return;}
        // A confirmed image-free row has neither pixels nor work to retire on scroll.
        if(known&&urls.isEmpty()){if(scheduled)cancelDiscovery();return;}
        // A popup may retain pixels while the underlying list moves. Recheck those
        // references on focus return even though pause(false) cleared visible.
        if(!inViewport()){if(visible||request!=null||scheduled||loadedImageCount()>0)pause();return;}
        visible=true;
        // Move the deadline without removing/inserting one Handler message per scroll event.
        quietUntil=SystemClock.uptimeMillis()+QUIET_MS;
        scheduleDiscovery();
    }
    private void cancelDiscovery(){removeCallbacks(discover);scheduled=false;}
    /** Earliest missing work; pending requests and already displayed pixels need no timer. */
    private long nextWorkAt(){
        long next=!known&&request==null&&metadataFailures<MAX_METADATA_FAILURES?metadataRetryAt:Long.MAX_VALUE;
        for(int i=0;i<urls.size();i++)if(!mediaFailed.get(i)&&media.get(i)==null&&pictures.get(i).getDrawable()==null)next=Math.min(next,mediaRetryAt.get(i));
        return next==Long.MAX_VALUE?next:Math.max(next,quietUntil);
    }
    private void scheduleDiscovery(){
        if(closed||!visible)return;
        long next=nextWorkAt();
        if(next==Long.MAX_VALUE){if(scheduled)cancelDiscovery();return;}
        if(!scheduled){scheduled=true;postDelayed(discover,Math.max(0,next-SystemClock.uptimeMillis()));}
    }
    private void discover(){
        scheduled=false;if(closed||!inViewport())return;
        long now=SystemClock.uptimeMillis(),next=nextWorkAt();
        if(next==Long.MAX_VALUE)return;
        if(next>now){scheduleDiscovery();return;}
        if(!current.getAsBoolean())return;
        if(!known&&request==null&&metadataFailures<MAX_METADATA_FAILURES&&metadataRetryAt<=now){
            TopicPreviewImages.Result cached=previews.peek(scope,tid);
            if(cached!=null)acceptMetadata(cached);
            else {final int owner=metadataEpoch;request=previews.request(scope,tid,result->{if(owner!=metadataEpoch||closed||!current.getAsBoolean())return;request=null;acceptMetadata(result);if(inViewport()){visible=true;scheduleDiscovery();}});}
        }
        loadPictures();
        scheduleDiscovery();
    }
    private void acceptMetadata(TopicPreviewImages.Result result){
        if(!result.failed){known=true;metadataFailures=0;metadataRetryAt=0;show(result);return;}
        // The service caches failures for 30 seconds. Retry once after that TTL, then wait
        // for an explicit page refresh; a failed lookup is never a known image-free post.
        metadataFailures++;metadataRetryAt=SystemClock.uptimeMillis()+METADATA_RETRY_MS;
    }
    private void show(TopicPreviewImages.Result result){
        List<String> found=result.urls;
        if(!closed&&votesObserver!=null&&current.getAsBoolean())votesObserver.accept(result.votes());
        if(closed||!urls.isEmpty()||found==null||found.isEmpty())return;
        metadata=result;
        ensureGalleryControls();
        label.setText(result.sourceTotal+(result.totalKnown?"":"+")+" 张");
        String totalDescription="楼主图片"+(result.totalKnown?"共 ":"至少 ")+result.sourceTotal+" 张";
        label.setContentDescription(totalDescription);
        urls=Collections.unmodifiableList(new ArrayList<>(found.subList(0,Math.min(3,found.size()))));
        for(int i=0;i<urls.size();i++){
            final String url=urls.get(i);final int index=i;
            FrameLayout tile=new FrameLayout(getContext());GradientDrawable bg=new GradientDrawable();bg.setColor(outline);bg.setCornerRadius(dp(12));tile.setBackground(bg);tile.setClipToOutline(true);
            ImageView image=new ImageView(getContext());image.setScaleType(ImageView.ScaleType.CENTER_CROP);tile.addView(image,new FrameLayout.LayoutParams(-1,-1));pictures.add(image);
            TextView status=new TextView(getContext());status.setText("图片");status.setTextColor(muted);status.setTextSize(11);status.setGravity(Gravity.CENTER);tile.addView(status,new FrameLayout.LayoutParams(-1,-1));statuses.add(status);media.add(null);mediaFailed.add(false);mediaBusyFailures.add(0);mediaRetryAt.add(0L);
            if(index==0){FrameLayout.LayoutParams badgeParams=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);badgeParams.setMarginEnd(dp(6));badgeParams.bottomMargin=dp(6);tile.addView(label,badgeParams);}
            tile.setContentDescription("预览楼主第 "+(i+1)+" 张图片："+title+"，"+totalDescription);tile.setTag("topic-preview-image:"+tid+":"+i);tile.setMinimumWidth(dp(48));tile.setMinimumHeight(dp(48));tile.setOnClickListener(v->{if(!closed&&current.getAsBoolean()){
                if(galleryOpener==null)open.accept(url);
                else galleryOpener.open(ImageGalleryState.create(metadata.urls,index,metadata.sourceTotal,metadata.totalKnown,metadata.truncated));
            }});
            LayoutParams cell=new LayoutParams(dp(100),dp(92));if(index>0)cell.leftMargin=dp(6);gallery.addView(tile,cell);
        }
        setVisibility(VISIBLE);requestLayout();
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int count=gallery==null?0:gallery.getChildCount();int width=Math.max(0,MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight());
        if(count>0){int cellWidth=Math.min(dp(count==1?148:112),Math.max(dp(48),(width-dp(6)*(count-1))/count));int cellHeight=Math.min(dp(104),Math.max(dp(64),cellWidth));int badgeWidth=Math.max(0,cellWidth-dp(12));if(label.getMaxWidth()!=badgeWidth)label.setMaxWidth(badgeWidth);
            for(int i=0;i<count;i++){LayoutParams p=(LayoutParams)gallery.getChildAt(i).getLayoutParams();if(p.width!=cellWidth||p.height!=cellHeight){p.width=cellWidth;p.height=cellHeight;gallery.getChildAt(i).setLayoutParams(p);}}
        }
        super.onMeasure(widthSpec,heightSpec);
    }
    private void loadPictures(){
        if(closed||!inViewport())return;final int epoch=mediaEpoch;final long now=SystemClock.uptimeMillis();
        for(int i=0;i<urls.size();i++){
            final int index=i;if(mediaFailed.get(i)||media.get(i)!=null||pictures.get(i).getDrawable()!=null||mediaRetryAt.get(i)>now)continue;
            media.set(i,images.load(urls.get(i),false,new NativeImages.Callback(){
                public void loaded(Bitmap bitmap){if(closed||epoch!=mediaEpoch)return;media.set(index,null);if(!inViewport()||!current.getAsBoolean())return;mediaBusyFailures.set(index,0);mediaRetryAt.set(index,0L);pictures.get(index).setImageBitmap(bitmap);statuses.get(index).setVisibility(GONE);}
                public void failed(String message){
                    if(closed||epoch!=mediaEpoch)return;media.set(index,null);if(!current.getAsBoolean())return;
                    int retries=mediaBusyFailures.get(index);
                    if("图片加载繁忙".equals(message)&&retries<MAX_MEDIA_BUSY_RETRIES){
                        mediaBusyFailures.set(index,retries+1);mediaRetryAt.set(index,SystemClock.uptimeMillis()+(180L<<retries));
                        if(inViewport()){visible=true;scheduleDiscovery();}
                    }else{mediaFailed.set(index,true);statuses.get(index).setText("点按查看");}
                }
            }));
        }
    }
    /** Cancels anonymous media and authenticated metadata work, releasing offscreen pixels. */
    public void pause(){pause(true);}
    private void pause(boolean releasePixels){visible=false;cancelDiscovery();quietUntil=0;metadataEpoch++;if(request!=null){request.cancel();request=null;}mediaEpoch++;for(int i=0;i<media.size();i++){if(media.get(i)!=null)media.get(i).cancel();media.set(i,null);if(releasePixels){pictures.get(i).setImageDrawable(null);statuses.get(i).setVisibility(VISIBLE);}}}
    public int imageCount(){return urls.size();}
    public int loadedImageCount(){int count=0;for(ImageView image:pictures)if(image.getDrawable()!=null)count++;return count;}
    @Override public void close(){if(closed)return;closed=true;pause();unwatch();}
}
