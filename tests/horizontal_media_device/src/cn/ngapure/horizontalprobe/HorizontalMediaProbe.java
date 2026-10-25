package cn.ngapure.horizontalprobe;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import cn.ngapure.app.NativeImages;
import cn.ngapure.app.NativeRichText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

/** No INTERNET permission, no main app data: exact product classes and synthetic images. */
public final class HorizontalMediaProbe extends Instrumentation {
    private final JSONObject report=new JSONObject();
    private final Map<String,AtomicInteger> fetches=new ConcurrentHashMap<>();
    private ProbeHostActivity activity;private NativeImages images;private byte[] gif;private int assertions;
    private boolean expectedFixed,blockAttachments;private String mode="all";
    @Override public void onCreate(Bundle args){super.onCreate(args);expectedFixed=args!=null&&"true".equals(args.getString("expectFixed"));blockAttachments=args!=null&&"true".equals(args.getString("blockAttachments"));if(args!=null)mode=args.getString("mode","all");start();}
    @Override public void onStart(){boolean passed=false,clean=true;String failure="NONE";try{
        require(getTargetContext().checkSelfPermission(Manifest.permission.INTERNET)==PackageManager.PERMISSION_DENIED,"NoInternetPermission");
        require(getTargetContext().getApplicationInfo().uid!=getTargetContext().getPackageManager().getApplicationInfo("cn.ngapure.app",0).uid,"SeparateUid");
        if(blockAttachments){require(expectedFixed,"BlockAttachmentContractRequiresFixedMode");boolean rtlSupported=(getTargetContext().getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL)!=0;report.put("helperSupportsRtl",rtlSupported);require(rtlSupported,"HelperRtlSupportMatchesProductionManifest");}
        gif=asset("synthetic-two-frame.gif");report.put("binding",new JSONObject(new String(asset("binding.json"),java.nio.charset.StandardCharsets.UTF_8)));installTransport();
        activity=(ProbeHostActivity)startActivitySync(new Intent().setClassName("cn.ngapure.horizontalprobe","cn.ngapure.horizontalprobe.ProbeHostActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        images=new NativeImages();if(mode.equals("wrapped")){wrappedColspanImageVisibility(false);wrappedColspanImageVisibility(true);}else {StringBuilder raw=new StringBuilder("[table][tr]");for(int i=0;i<8;i++){String url="https://img4.nga.cn/attachments/clip-"+i+".gif";fetches.put(url,new AtomicInteger());raw.append("[td]第").append(i+1).append("列[img]").append(url).append("[/img][/td]");}raw.append("[/tr][/table]");
        NativeRichText rich=onMain(()->{NativeRichText view=new NativeRichText(activity,images,raw.toString(),false,16,null);activity.content.addView(view,new ViewGroup.LayoutParams(-1,-2));return view;});
        await(()->onMain(()->rich.getHeight()>0&&spans(rich).size()==8),"EightNativeTableImages");SystemClock.sleep(600);waitForIdleSync();
        JSONObject first=onMain(()->snapshot(rich));report.put("initial",first);require(first.getInt("hiddenOwners")>=4,"AtLeastFourColumnsFullyClipped");
        if(expectedFixed){require(first.getInt("hiddenRunning")==0,"ClippedColumnsDoNotAnimate");require(first.getInt("hiddenShouldRun")==0,"ClippedColumnsExcludedFromVisiblePredicate");require(first.getInt("farHiddenOwners")>0,"AtLeastOneColumnOutsideHorizontalPrefetch");require(first.getInt("farHiddenWithFetch")==0,"FarClippedColumnsDoNotFetch");}
        else {require(first.getInt("hiddenRunning")>=4,"BaselineClippedColumnsStillAnimate");require(first.getInt("hiddenShouldRun")>=4,"BaselineClippedColumnsIncorrectlyVisible");require(first.getInt("hiddenWithFetch")>=4,"BaselineClippedColumnsAlreadyFetched");}
        HorizontalScrollView horizontal=onMain(()->find(rich));require(horizontal!=null,"NativeHorizontalTableContainer");
        onMain(()->{horizontal.scrollTo(horizontal.getChildAt(0).getWidth(),0);return null;});SystemClock.sleep(600);waitForIdleSync();JSONObject right=onMain(()->snapshot(rich));report.put("scrolledRight",right);
        if(expectedFixed){require(right.getInt("visibleRunning")>0,"RevealedColumnsLoadAndAnimate");require(right.getInt("hiddenRunning")==0,"FormerVisibleColumnsStopAnimation");require(right.getInt("hiddenShouldRun")==0,"FormerVisibleColumnsNoLongerVisible");}
        tableMeasureCurrentConstraints();if(blockAttachments){blockColspanImageVisibility(false);blockColspanImageVisibility(true);}else{colspanImageVisibility(false);colspanImageVisibility(true);}wrappedColspanImageVisibility(false);wrappedColspanImageVisibility(true);verticalTablePrefetch();}
        passed=true;
    }catch(Exception|AssertionError issue){failure=issue.getClass().getSimpleName()+":"+issue.getMessage();}
    finally {try{onMain(()->{if(images!=null)images.close();if(activity!=null){activity.content.removeAllViews();activity.finish();}return null;});}catch(Exception issue){clean=false;}try{report.put("passed",passed&&clean);report.put("cleanupPassed",clean);report.put("assertionCount",assertions);report.put("failure",failure);report.put("expectedFixed",expectedFixed);report.put("mode",mode);report.put("blockAttachments",blockAttachments);report.put("wrappedBoundaryAsset",blockAttachments?"OFFICIAL_EMOJI_URL_WITH_LOCAL_SYNTHETIC_GIF":"ORDINARY_ATTACHMENT_LEGACY_INLINE");report.put("realNetworkRequests",0);report.put("communityWrites",0);report.put("mainClientDataRead",false);}catch(Exception ignored){}}
    Bundle result=new Bundle();result.putString("safe_horizontal_media_report",report.toString());finish(Activity.RESULT_OK,result);}
    private void tableMeasureCurrentConstraints()throws Exception{onMain(()->{NativeRichText rich=new NativeRichText(activity,images,"[table][tr][td]甲[/td][td]乙[/td][/tr][/table]",false,16,null);int[] widths={1400,800,1400,800};JSONArray samples=new JSONArray();boolean all=true;for(int width:widths){rich.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));rich.layout(0,0,width,rich.getMeasuredHeight());HorizontalScrollView horizontal=find(rich);int gridWidth=horizontal.getChildAt(0).getMeasuredWidth();JSONObject item=new JSONObject();item.put("currentWidthConstraint",width);item.put("tableViewportWidth",horizontal.getMeasuredWidth());item.put("gridMeasuredWidth",gridWidth);samples.put(item);all&=Math.abs(gridWidth-width)<=1;}report.put("tableResizeSamples",samples);report.put("tableMeasuredFromCurrentConstraint",all);require(expectedFixed?all:!all,expectedFixed?"TableFollowsCurrentMeasureConstraint":"BaselineTableUsesPreviousLaidOutWidth");return null;});}
    private void colspanImageVisibility(boolean rtl)throws Exception{String url="https://img4.nga.cn/attachments/colspan-"+(rtl?"rtl":"ltr")+".gif";fetches.put(url,new AtomicInteger());NativeRichText rich=onMain(()->{activity.content.removeAllViews();String text=rtl?"ب [img]"+url+"[/img] ب":"首[img]"+url+"[/img]尾";NativeRichText value=new NativeRichText(activity,images,"[table][tr][td colspan8]"+text+"[/td][/tr][/table]",false,16,null);activity.content.addView(value,new ViewGroup.LayoutParams(-1,-2));return value;});await(()->onMain(()->spans(rich).size()==1&&field(spans(rich).get(0),"actual")!=null),"ColspanImageDecoded"+rtl);SystemClock.sleep(250);HorizontalScrollView horizontal=onMain(()->find(rich));JSONObject before=onMain(()->colspanSnapshot(rich,horizontal));onMain(()->{horizontal.scrollTo(horizontal.getChildAt(0).getWidth(),0);return null;});SystemClock.sleep(300);JSONObject right=onMain(()->colspanSnapshot(rich,horizontal));require(right.getBoolean("ownerGloballyVisible"),"WideColspanOwnerRemainsVisible"+rtl);require(right.getInt("scrollX")>right.getInt("imageWidth")+200,"ColspanActuallyScrolledBeyondShortImage"+rtl);if(expectedFixed){require(before.getBoolean("running")==!rtl,"ColspanInitialAnimationMatchesImageNotOwner"+rtl);require(right.getBoolean("running")==rtl,"ColspanScrolledAnimationMatchesImageNotOwner"+rtl);}else{require(before.getBoolean("running")&&right.getBoolean("running"),"BaselineColspanInvisibleImageStillAnimates"+rtl);}onMain(()->{horizontal.scrollTo(0,0);return null;});SystemClock.sleep(250);JSONObject returned=onMain(()->colspanSnapshot(rich,horizontal));if(expectedFixed)require(returned.getBoolean("running")==!rtl,"ColspanReverseScrollUpdatesAnimation"+rtl);require(fetches.get(url).get()==1,"ColspanScrollDoesNotRefetch"+rtl);JSONObject result=new JSONObject();result.put("rtl",rtl);result.put("before",before);result.put("scrolledRight",right);result.put("returnedLeft",returned);report.put(rtl?"rtlColspan":"ltrColspan",result);}
    private JSONObject colspanSnapshot(NativeRichText rich,HorizontalScrollView horizontal)throws Exception{Object span=spans(rich).get(0);View owner=(View)field(span,"owner");Drawable actual=(Drawable)field(span,"actual");JSONObject result=new JSONObject();result.put("ownerGloballyVisible",owner.getGlobalVisibleRect(new Rect()));result.put("ownerWidth",owner.getWidth());result.put("imageWidth",((Drawable)span).getBounds().width());result.put("scrollX",horizontal.getScrollX());result.put("running",actual instanceof AnimatedImageDrawable&&((AnimatedImageDrawable)actual).isRunning());result.put("paragraphDirection",((android.widget.TextView)owner).getLayout().getParagraphDirection(0));return result;}
    /** New product contract: ordinary attachments are paragraphs; inline emoji still exercise wrapped edges. */
    private void blockColspanImageVisibility(boolean rtl)throws Exception {
        String url="https://img4.nga.cn/attachments/block-colspan-"+(rtl?"rtl":"ltr")+".gif";fetches.put(url,new AtomicInteger());
        NativeRichText rich=onMain(()->{
            activity.content.removeAllViews();String text=rtl?"ب[img]"+url+"[/img]ب":"首[img]"+url+"[/img]尾";
            NativeRichText value=new NativeRichText(activity,images,"[table][tr][td colspan8]"+text+"[/td][/tr][/table]",false,16,null);
            // Explicitly exercise each layout direction: an image-only paragraph has no
            // strong text. The helper manifest must enable the same RTL support as the product.
            // Set only this synthetic TextView's public direction; do not alter its spans or product classes.
            android.widget.TextView owner=(android.widget.TextView)field(spans(value).get(0),"owner");
            owner.setTextDirection(rtl?View.TEXT_DIRECTION_RTL:View.TEXT_DIRECTION_LTR);
            report.put(rtl?"rtlBlockDirectionBeforeAttach":"ltrBlockDirectionBeforeAttach",new JSONObject().put("ownerIdentity",System.identityHashCode(owner)).put("textDirection",owner.getTextDirection()));
            activity.content.addView(value,new ViewGroup.LayoutParams(-1,-2));return value;
        });
        await(()->onMain(()->spans(rich).size()==1&&field(spans(rich).get(0),"actual")!=null),"BlockColspanImageDecoded"+rtl);SystemClock.sleep(300);
        // Decoding posts an ordinary setText-based span relayout. Apply this fixture-only
        // direction after that asynchronous initialization, then require the real layout
        // to use it. Keep the initial owner identity/direction to diagnose any reset.
        onMain(()->{
            android.widget.TextView owner=(android.widget.TextView)field(spans(rich).get(0),"owner");int requested=rtl?View.TEXT_DIRECTION_RTL:View.TEXT_DIRECTION_LTR;
            JSONObject setup=new JSONObject().put("ownerIdentity",System.identityHashCode(owner)).put("beforeTextDirection",owner.getTextDirection()).put("requestedTextDirection",requested);
            owner.setTextDirection(requested);owner.requestLayout();setup.put("immediateTextDirection",owner.getTextDirection());
            report.put(rtl?"rtlBlockDirectionAfterDecode":"ltrBlockDirectionAfterDecode",setup);
            require(owner.getTextDirection()==requested,"BlockDirectionAppliedToFinalOwner"+rtl);return null;
        });waitForIdleSync();SystemClock.sleep(150);waitForIdleSync();
        HorizontalScrollView horizontal=onMain(()->find(rich));JSONObject before=onMain(()->blockSnapshot(rich,horizontal));
        report.put(rtl?"rtlBlockColspanInitial":"ltrBlockColspanInitial",before);
        require(before.getBoolean("ordinaryImageOwnLine"),"BlockAttachmentOccupiesOwnNativeLine"+rtl);
        require(before.getInt("paragraphDirection")== (rtl?-1:1),"BlockImageActualParagraphDirection"+rtl);
        require(before.getBoolean("running")==!rtl,"BlockInitialAnimationMatchesImageNotOwner"+rtl);
        require(before.getBoolean("predicateVisible")==before.getBoolean("imageBoundsIntersectViewport"),"BlockInitialPredicateMatchesActualImageBounds"+rtl);
        onMain(()->{horizontal.scrollTo(horizontal.getChildAt(0).getWidth(),0);return null;});
        await(()->onMain(()->blockSnapshot(rich,horizontal).getBoolean("running")==rtl),"BlockScrolledAnimationMatchesActualImage"+rtl);
        JSONObject right=onMain(()->blockSnapshot(rich,horizontal));
        require(right.getBoolean("ownerGloballyVisible"),"BlockWideColspanOwnerRemainsVisible"+rtl);
        require(right.getInt("scrollX")>right.getInt("imageWidth")+200,"BlockColspanActuallyScrolledBeyondShortImage"+rtl);
        require(right.getBoolean("predicateVisible")==right.getBoolean("imageBoundsIntersectViewport"),"BlockScrolledPredicateMatchesActualImageBounds"+rtl);
        onMain(()->{horizontal.scrollTo(0,0);return null;});
        await(()->onMain(()->blockSnapshot(rich,horizontal).getBoolean("running")==!rtl),"BlockReverseScrollUpdatesAnimation"+rtl);
        require(fetches.get(url).get()==1,"BlockColspanScrollDoesNotRefetch"+rtl);
        report.put(rtl?"rtlBlockColspan":"ltrBlockColspan",new JSONObject().put("rtl",rtl).put("fixtureDirectionOverride",rtl?"RTL":"LTR").put("before",before).put("scrolledRight",right).put("returnedLeft",onMain(()->blockSnapshot(rich,horizontal))));
    }
    @SuppressWarnings("deprecation")
    private JSONObject blockSnapshot(NativeRichText rich,HorizontalScrollView horizontal)throws Exception {
        Object span=spans(rich).get(0);android.widget.TextView owner=(android.widget.TextView)field(span,"owner");int offset=(Integer)field(span,"textOffset");
        android.text.Layout layout=owner.getLayout();int line=layout.getLineForOffset(offset),start=layout.getLineStart(line),end=layout.getLineEnd(line);
        String row=owner.getText().subSequence(start,end).toString();boolean alone=row.trim().equals("\ufffc")&&line>layout.getLineForOffset(0)&&layout.getLineForOffset(owner.length()-1)>line;
        android.graphics.Path selection=new android.graphics.Path();android.graphics.RectF bounds=new android.graphics.RectF();layout.getSelectionPath(offset,offset+1,selection);selection.computeBounds(bounds,true);
        int[] ownerAt=new int[2],viewportAt=new int[2];owner.getLocationInWindow(ownerAt);horizontal.getLocationInWindow(viewportAt);
        float left=ownerAt[0]-viewportAt[0]+owner.getTotalPaddingLeft()-owner.getScrollX()+bounds.left,right=ownerAt[0]-viewportAt[0]+owner.getTotalPaddingLeft()-owner.getScrollX()+bounds.right;
        int clipLeft=horizontal.getClipToPadding()?horizontal.getPaddingLeft():0,clipRight=horizontal.getWidth()-(horizontal.getClipToPadding()?horizontal.getPaddingRight():0);
        Method predicate=span.getClass().getDeclaredMethod("shouldRun");predicate.setAccessible(true);
        return colspanSnapshot(rich,horizontal).put("ordinaryImageOwnLine",alone).put("imageLine",line).put("paragraphDirection",layout.getParagraphDirection(line)).put("ownerIdentity",System.identityHashCode(owner)).put("ownerTextDirection",owner.getTextDirection()).put("ownerLayoutDirection",owner.getLayoutDirection()).put("firstStrongRtlOnImageRow",android.text.TextDirectionHeuristics.FIRSTSTRONG_RTL.isRtl(row,0,row.length())).put("forcedRtlOnImageRow",android.text.TextDirectionHeuristics.RTL.isRtl(row,0,row.length())).put("objectReplacementUnicodeDirectionality",Character.getDirectionality('\ufffc')).put("imageLeftInViewport",left).put("imageRightInViewport",right).put("imageBoundsIntersectViewport",right>clipLeft&&left<clipRight).put("predicateVisible",(Boolean)predicate.invoke(span));
    }
    @SuppressWarnings("deprecation") // Path.computeBounds overload supports the API28 helper baseline.
    private void wrappedColspanImageVisibility(boolean rtl)throws Exception {
        String url=blockAttachments?"https://img4.nga.cn/ngabbs/post/smile/"+(rtl?"ac39.png":"ac0.png"):"https://img4.nga.cn/attachments/colspan-wrapped-"+(rtl?"rtl":"ltr")+".gif";
        fetches.put(url,new AtomicInteger());
        NativeRichText rich=onMain(()->{
            activity.content.removeAllViews();android.widget.TextView measure=new android.widget.TextView(activity);measure.setTextSize(16);
            float density=activity.getResources().getDisplayMetrics().density;
            int width=Math.round(112*density)*8-2*Math.round(10*density);
            int inlineWidth=blockAttachments?Math.round(32*density):16;
            StringBuilder text=new StringBuilder(rtl?"ب":"");
            if(rtl) {
                while(text.length()<10000&&measure.getPaint().measureText(text.toString()+"ـ")+inlineWidth<=width)text.append('ـ');
            } else {
                int count=(int)Math.floor((width-inlineWidth)/measure.getPaint().measureText("一"));
                for(int i=0;i<count;i++)text.append('一');
            }
            if(blockAttachments)text.append(rtl?"[s:ac:茶]":"[s:ac:blink]");else text.append("[img]").append(url).append("[/img]");text.append(rtl?"ب":"一");
            NativeRichText value=new NativeRichText(activity,images,"[table][tr][td colspan8]"+text+"[/td][/tr][/table]",false,16,null);
            activity.content.addView(value,new ViewGroup.LayoutParams(-1,-2));return value;
        });
        if(blockAttachments) {
            await(()->onMain(()->spans(rich).size()==1&&((android.widget.TextView)field(spans(rich).get(0),"owner")).getLayout()!=null),"WrappedEmojiNativeLayoutReady"+rtl);
            if(!rtl) {
                // A narrow emoji never has the old attachment's wide placeholder.
                // Reveal it once to decode, then restore the initial clipped position
                // before the unchanged edge/animation assertions below.
                require(fetches.get(url).get()==0,"WrappedFarInlineEmojiDoesNotPrefetchBeforeReveal");
                onMain(()->{HorizontalScrollView horizontal=find(rich);horizontal.scrollTo(horizontal.getChildAt(0).getWidth(),0);return null;});
            }
            report.put(rtl?"wrappedRtlDecodeWarmup":"wrappedLtrDecodeWarmup",onMain(()->new JSONObject().put("scrollX",find(rich).getScrollX()).put("fetchCount",fetches.get(url).get()).put("initialNarrowEmojiRequiresReveal",!rtl)));
        }
        await(()->onMain(()->spans(rich).size()==1&&field(spans(rich).get(0),"actual")!=null),"WrappedColspanImageDecoded"+rtl);
        if(blockAttachments)require(onMain(()->(Boolean)field(spans(rich).get(0),"emoticon")),"WrappedBoundaryUsesInlineEmojiUnderBlockContract"+rtl);
        if(blockAttachments)onMain(()->{find(rich).scrollTo(0,0);return null;});
        SystemClock.sleep(300);
        JSONObject before=onMain(()->{
            Object span=spans(rich).get(0);android.widget.TextView owner=(android.widget.TextView)field(span,"owner");
            int offset=(Integer)field(span,"textOffset");JSONObject value=colspanSnapshot(rich,find(rich));
            android.text.Layout layout=owner.getLayout();int line=layout.getLineForOffset(offset);
            value.put("imageLine",line);value.put("afterImageLine",layout.getLineForOffset(offset+1));value.put("offset",offset);
            value.put("lineStart",layout.getLineStart(line));value.put("lineEnd",layout.getLineEnd(line));
            value.put("lineLeft",layout.getLineLeft(line));value.put("lineRight",layout.getLineRight(line));
            value.put("primaryHorizontal",layout.getPrimaryHorizontal(offset));value.put("secondaryHorizontal",layout.getSecondaryHorizontal(offset));
            value.put("rtlChar",layout.isRtlCharAt(offset));
            try {value.put("cachedHorizontalLeft",field(span,"horizontalLeft"));value.put("cachedHorizontalRight",field(span,"horizontalRight"));}
            catch(NoSuchFieldException legacyBaseline){value.put("cachedHorizontalBoundsAbsentInLegacyBaseline",true);}
            android.graphics.Path selection=new android.graphics.Path();android.graphics.RectF bounds=new android.graphics.RectF();
            layout.getSelectionPath(offset,offset+1,selection);selection.computeBounds(bounds,true);
            value.put("selectionPathBounds",bounds.toShortString());
            android.graphics.Region pixels=new android.graphics.Region();pixels.setPath(selection,new android.graphics.Region(0,0,layout.getWidth(),layout.getHeight()));
            value.put("selectionFilledRegionBounds",pixels.getBounds().toShortString());return value;
        });
        report.put(rtl?"wrappedRtlColspanInitial":"wrappedColspanInitial",before);
        require(before.getInt("afterImageLine")>before.getInt("imageLine"),"WrappedFixtureEndsImageAtLineBoundary"+rtl);
        require(before.getInt("paragraphDirection")== (rtl?-1:1),"WrappedFixtureParagraphDirection"+rtl);
        require(before.getBoolean("running")==(!expectedFixed||rtl),expectedFixed?"WrappedInitialAnimationMatchesImagePosition"+rtl:"BaselineWrappedInitialImageKeepsAnimating"+rtl);
        HorizontalScrollView horizontal=onMain(()->find(rich));
        onMain(()->{horizontal.scrollTo(horizontal.getChildAt(0).getWidth(),0);return null;});
        await(()->onMain(()->colspanSnapshot(rich,horizontal).getBoolean("running")==(!expectedFixed||!rtl)),"WrappedScrolledAnimationMatchesImagePosition"+rtl);
        require(fetches.get(url).get()==1,"WrappedImageScrollDoesNotRefetch"+rtl);
        report.put(rtl?"wrappedRtlColspanScrolled":"wrappedColspanRevealed",onMain(()->colspanSnapshot(rich,horizontal)));
    }
    private void verticalTablePrefetch()throws Exception{String first="https://img4.nga.cn/attachments/vertical-prefetch-a.gif",second="https://img4.nga.cn/attachments/vertical-prefetch-b.gif";fetches.put(first,new AtomicInteger());fetches.put(second,new AtomicInteger());NativeRichText rich=onMain(()->{android.widget.ScrollView scroll=(android.widget.ScrollView)activity.content.getParent();scroll.scrollTo(0,0);activity.content.removeAllViews();View spacer=new View(activity);activity.content.addView(spacer,new ViewGroup.LayoutParams(-1,scroll.getHeight()+Math.round(80*activity.getResources().getDisplayMetrics().density)));NativeRichText value=new NativeRichText(activity,images,"[table][tr][td][img]"+first+"[/img][/td][td][img]"+second+"[/img][/td][/tr][/table]",false,16,null);activity.content.addView(value,new ViewGroup.LayoutParams(-1,-2));return value;});await(()->onMain(()->spans(rich).size()==2&&field(spans(rich).get(0),"actual")!=null&&field(spans(rich).get(1),"actual")!=null),"VerticalTableImagesPrefetchBelowScreen");JSONObject before=onMain(()->snapshot(rich));report.put("verticalPrefetchBeforeScroll",before);require(before.getInt("hiddenRunning")==0,"VerticallyPrefetchedAnimationsStayStopped");onMain(()->{android.widget.ScrollView scroll=(android.widget.ScrollView)activity.content.getParent();scroll.scrollTo(0,activity.content.getHeight());return null;});await(()->onMain(()->snapshot(rich).getInt("visibleRunning")==2),"VerticalPrefetchAnimationsStartWhenRevealed");require(fetches.get(first).get()==1&&fetches.get(second).get()==1,"VerticalPrefetchRevealDoesNotRefetch");report.put("verticalPrefetchAfterScroll",onMain(()->snapshot(rich)));}
    private JSONObject snapshot(NativeRichText rich)throws Exception{JSONObject result=new JSONObject();JSONArray cells=new JSONArray();int hidden=0,hiddenRunning=0,hiddenShould=0,hiddenFetched=0,visibleRunning=0,farHidden=0,farFetched=0;HorizontalScrollView horizontal=find(rich);int[] viewport=new int[2];horizontal.getLocationInWindow(viewport);int margin=Math.round(320*rich.getResources().getDisplayMetrics().density);for(Object span:spans(rich)){View owner=(View)field(span,"owner");Rect visible=new Rect();boolean shown=owner.getGlobalVisibleRect(visible);int[] location=new int[2];owner.getLocationInWindow(location);boolean far=location[0]+owner.getWidth()<viewport[0]-margin||location[0]>viewport[0]+horizontal.getWidth()+margin;Drawable actual=(Drawable)field(span,"actual");Method predicate=span.getClass().getDeclaredMethod("shouldRun");predicate.setAccessible(true);boolean should=(Boolean)predicate.invoke(span);boolean running=actual instanceof AnimatedImageDrawable&&((AnimatedImageDrawable)actual).isRunning();String url=(String)field(span,"url");int count=fetches.get(url).get();if(!shown){hidden++;if(running)hiddenRunning++;if(should)hiddenShould++;if(count>0)hiddenFetched++;}else if(running)visibleRunning++;if(far){farHidden++;if(count>0)farFetched++;}JSONObject cell=new JSONObject();cell.put("index",cells.length());cell.put("ownerGloballyVisible",shown);cell.put("ownerVisibleWidth",visible.width());cell.put("outside320dpHorizontalPrefetch",far);cell.put("predicateVisible",should);cell.put("running",running);cell.put("fetches",count);cells.put(cell);}result.put("hiddenOwners",hidden);result.put("hiddenRunning",hiddenRunning);result.put("hiddenShouldRun",hiddenShould);result.put("hiddenWithFetch",hiddenFetched);result.put("visibleRunning",visibleRunning);result.put("farHiddenOwners",farHidden);result.put("farHiddenWithFetch",farFetched);result.put("cells",cells);return result;}
    private void installTransport(){URL.setURLStreamHandlerFactory(protocol->!"https".equals(protocol)?null:new URLStreamHandler(){@Override protected URLConnection openConnection(URL url)throws IOException{AtomicInteger count=fetches.get(url.toString());if(count==null)throw new IOException("Fixture only");count.incrementAndGet();return new HttpURLConnection(url){@Override public int getResponseCode(){return 200;}@Override public long getContentLengthLong(){return gif.length;}@Override public InputStream getInputStream(){return new ByteArrayInputStream(gif);}@Override public void disconnect(){}@Override public void connect(){}@Override public boolean usingProxy(){return false;}};}});}
    private static Object field(Object target,String name)throws Exception{Class<?> type=target.getClass();while(type!=null){try{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(NoSuchFieldException absent){type=type.getSuperclass();}}throw new NoSuchFieldException(name);}
    @SuppressWarnings("unchecked") private static List<Object> spans(NativeRichText rich)throws Exception{return (List<Object>)field(rich,"inlineImages");}
    private static HorizontalScrollView find(View v){if(v instanceof HorizontalScrollView)return (HorizontalScrollView)v;if(v instanceof ViewGroup){ViewGroup p=(ViewGroup)v;for(int i=0;i<p.getChildCount();i++){HorizontalScrollView hit=find(p.getChildAt(i));if(hit!=null)return hit;}}return null;}
    private byte[] asset(String name)throws IOException{try(InputStream input=getTargetContext().getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}}
    private <T>T onMain(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get();}
    private void await(Callable<Boolean> condition,String name)throws Exception{long until=SystemClock.elapsedRealtime()+8000;while(!condition.call()&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(20);require(condition.call(),name);}
    private void require(boolean condition,String name){assertions++;if(!condition)throw new AssertionError(name);}
}
