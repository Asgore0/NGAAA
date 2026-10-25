package cn.ngapure.verify;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.AlignmentSpan;
import android.text.style.ClickableSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;
import cn.ngapure.app.NativeImages;
import cn.ngapure.app.NativeRichText;
import cn.ngapure.app.NgaStore;
import org.json.JSONObject;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Local synthetic typography, restored after checks. No credentials, forum text or URLs are reported. */
public final class TypographyProbe extends Instrumentation {
    private final JSONObject result=new JSONObject();
    private static final String CODE="    kept indent\n\tvalue = \"abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz0123456789\";\n";
    private static final String SOURCE="正文第一行\n\n正文第二段。"
            +"[quote][uid=123]被引用用户[/uid]\n引用内容[quote]内层引用[/quote]引用尾[/quote]"
            +"[b]样式前[quote]样式引用[/quote]样式后[/b]"
            +"[align=center]居中文字[/align][code]"+CODE+"[/code]"
            +"[collapse=测试折叠]折叠原文[/collapse]";

    @Override public void onCreate(Bundle args) {super.onCreate(args);start();}
    @Override public void onStart() {
        try {runTypography();put("passed",true);put("failure","NONE");}
        catch(Exception|AssertionError failure) {put("passed",false);put("failure","TYPOGRAPHY_CHECK_FAILED");}
        Bundle output=new Bundle();output.putString("typography_probe",result.toString());finish(0,output);
    }
    private void runTypography() throws Exception {
        NgaStore store=new NgaStore(getTargetContext());
        if(store.autoCheckIn()&&(!store.loggedIn()||!NgaStore.today().equals(store.lastCheckInDay())))throw new IllegalStateException();
        require(true,"onOpenSignInGuardPassed");
        Activity activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName("cn.ngapure.app","cn.ngapure.app.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        NativeImages loader=new NativeImages();loader.close();
        ViewGroup content=onMain(()->(ViewGroup)activity.findViewById(android.R.id.content));
        View original=onMain(()->content.getChildAt(0));
        ViewGroup.LayoutParams originalParams=onMain(original::getLayoutParams);
        ScrollView scroll=onMain(()->new ScrollView(activity));
        try {
            NativeRichText rich=onMain(()->new NativeRichText(activity,loader,SOURCE,false,16,url->{}));
            onMain(()->{content.removeAllViews();content.addView(scroll,new ViewGroup.LayoutParams(dp(activity,280),-1));scroll.addView(rich);return null;});
            settle();
            TextView code=onMain(()->find(rich,CODE));
            require(code!=null,"codeWhitespaceRetained");
            HorizontalScrollView horizontal=onMain(()->(HorizontalScrollView)code.getParent());
            require(onMain(()->code.getWidth()>horizontal.getWidth()),"longCodeHasHorizontalRange");
            require(onMain(()->{horizontal.scrollTo(code.getWidth(),0);return horizontal.getScrollX()>0;}),"longCodeCanScroll");
            require(onMain(()->find(rich,"被引用用户")!=null&&find(rich,"内层引用")!=null&&find(rich,"引用尾")!=null),"nestedQuoteContentPresent");
            require(onMain(()->linksUnderlined(rich)),"nativeLinksRemainUnderlined");
            require(onMain(()->bold(find(rich,"样式前"))&&bold(find(rich,"样式引用"))&&bold(find(rich,"样式后"))),"quoteSplittingRetainsOuterBold");
            require(onMain(()->centered(find(rich,"居中文字"))),"nativeCenterAlignmentApplied");
            require(onMain(()->find(rich,"折叠原文")==null),"collapseRemainsLazy");
            onMain(()->{find(rich,"测试折叠").performClick();return null;});settle();
            require(onMain(()->find(rich,"折叠原文")!=null),"collapseExpandsExactContent");
            onMain(()->{find(rich,"测试折叠").performClick();return null;});settle();
            require(onMain(()->!find(rich,"折叠原文").isShown()),"collapseHidesContent");
            require(onMain(()->visibleWidthsFit(rich,dp(activity,280))),"coverLayoutFits");
            onMain(()->{ViewGroup.LayoutParams p=scroll.getLayoutParams();p.width=dp(activity,620);scroll.setLayoutParams(p);return null;});settle();
            require(onMain(()->visibleWidthsFit(rich,dp(activity,620))),"expandedLayoutFits");
            String deep="深层引用原文";for(int i=0;i<32;i++)deep="[quote]"+deep+"[/quote]";
            final String deepSource=deep;
            NativeRichText nested=onMain(()->new NativeRichText(activity,loader,deepSource,true,28,null));
            onMain(()->{scroll.removeAllViews();scroll.addView(nested);ViewGroup.LayoutParams p=scroll.getLayoutParams();p.width=dp(activity,280);scroll.setLayoutParams(p);return null;});settle();
            require(onMain(()->find(nested,"深层引用原文")!=null&&find(nested,"深层引用原文").getWidth()>=dp(activity,180)),"deepQuotesRetainReadableCoverWidth");
            put("syntheticNetworkRequests",0);put("checkedWidthsDp",new org.json.JSONArray(new int[]{280,620}));
        } finally {
            onMain(()->{content.removeAllViews();content.addView(original,originalParams);return null;});loader.close();
        }
    }
    private static boolean linksUnderlined(View view) {
        if(view instanceof TextView) {
            CharSequence value=((TextView)view).getText();
            if(value instanceof Spanned)for(ClickableSpan link:((Spanned)value).getSpans(0,value.length(),ClickableSpan.class)) {
                TextPaint paint=new TextPaint();link.updateDrawState(paint);if(paint.isUnderlineText())return true;
            }
        }
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(linksUnderlined(((ViewGroup)view).getChildAt(i)))return true;
        return false;
    }
    private static boolean centered(TextView view) {
        if(view==null||!(view.getText() instanceof Spanned))return false;
        Spanned text=(Spanned)view.getText();
        for(AlignmentSpan alignment:text.getSpans(0,text.length(),AlignmentSpan.class))if(alignment.getAlignment()==Layout.Alignment.ALIGN_CENTER)return true;
        return false;
    }
    private static boolean bold(TextView view) {
        if(view==null||!(view.getText() instanceof Spanned))return false;
        Spanned text=(Spanned)view.getText();
        for(StyleSpan style:text.getSpans(0,text.length(),StyleSpan.class))if((style.getStyle()&android.graphics.Typeface.BOLD)!=0)return true;
        return false;
    }
    private static TextView find(View view,String value) {
        if(view instanceof TextView&&((TextView)view).getText().toString().contains(value))return (TextView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            TextView result=find(((ViewGroup)view).getChildAt(i),value);if(result!=null)return result;
        }
        return null;
    }
    private static boolean visibleWidthsFit(View view,int maxWidth) {
        if(view.getVisibility()!=View.VISIBLE)return true;
        if(view.getWidth()<1||view.getWidth()>maxWidth)return false;
        if(view instanceof HorizontalScrollView)return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)
            if(!visibleWidthsFit(((ViewGroup)view).getChildAt(i),view.getWidth()))return false;
        return true;
    }
    private static int dp(Activity activity,int value) {return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private void settle() {waitForIdleSync();SystemClock.sleep(120);waitForIdleSync();}
    private <T>T onMain(Callable<T> action) throws Exception {FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get(5,TimeUnit.SECONDS);}
    private void require(boolean condition,String key) {put(key,condition);if(!condition)throw new AssertionError();}
    private void put(String key,Object value) {try {result.put(key,value);}catch(Exception ignored) {/* Fixed local projections only. */}}
}
