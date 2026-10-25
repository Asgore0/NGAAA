package cn.ngapure.searchprobe;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import cn.ngapure.app.MainActivity;
import cn.ngapure.app.NativeGlassDialog;
import cn.ngapure.app.NgaApi;
import cn.ngapure.app.NgaStore;
import cn.ngapure.app.SearchHistory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual product Main, Android views/preferences and recreation. Separate UID; no Internet. */
public final class SearchEverydayProbe extends Instrumentation {
    private static final String COOKIE_A="ngaPassportUid=79501; ngaPassportCid=synthetic-search-a";
    private static final String COOKIE_B="ngaPassportUid=79502; ngaPassportCid=synthetic-search-b";
    private static final AtomicInteger BLOCKED_CONNECTIONS=new AtomicInteger();
    private final JSONObject report=new JSONObject();
    private volatile MainActivity activity;
    private boolean authorized,geometryAudit,geometryPhase;
    private float geometryFontScale=1f;
    private int assertions,recreations,savedPrompts;
    private String stage="guard";
    private final Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
        public void onActivityResumed(Activity value){if(value instanceof MainActivity)activity=(MainActivity)value;}
        public void onActivitySaveInstanceState(Activity value,Bundle state){}
        @Override public void onActivityPostSaveInstanceState(Activity value,Bundle state){if(!(value instanceof MainActivity))return;recreations++;String[] prompt=state.getStringArray("ngapure_prompt_draft_v1");if(prompt!=null&&prompt.length==8)savedPrompts++;}
        public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivityDestroyed(Activity a){}
    };
    @Override public void onCreate(Bundle args){super.onCreate(args);authorized=args!=null&&"true".equals(args.getString("syntheticSearchOnly"));geometryAudit=args!=null&&"true".equals(args.getString("geometryAudit"));start();}
    @Override public Activity newActivity(ClassLoader loader,String name,Intent intent)throws InstantiationException,IllegalAccessException,ClassNotFoundException{
        Activity value=super.newActivity(loader,name,intent);
        if(geometryPhase&&value instanceof MainActivity){Configuration local=new Configuration();local.densityDpi=160;local.fontScale=geometryFontScale;value.applyOverrideConfiguration(local);}
        return value;
    }
    private static Object field(Object target,String name)throws Exception{Field value=target.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(target);}
    private static void set(Object target,String name,Object value)throws Exception{Field member=target.getClass().getDeclaredField(name);member.setAccessible(true);member.set(target,value);}
    private static Method method(String name,Class<?>... types)throws Exception{Method result=MainActivity.class.getDeclaredMethod(name,types);result.setAccessible(true);return result;}
    private <T>T main(Callable<T> action)throws Exception{FutureTask<T> task=new FutureTask<>(action);runOnMainSync(task);return task.get(10,TimeUnit.SECONDS);}
    private void check(String label,boolean passed)throws Exception{report.put(label,passed);assertions++;if(!passed)throw new AssertionError(label);}
    private void invoke(String name,Class<?>[] types,Object... args)throws Exception{main(()->{method(name,types).invoke(activity,args);return null;});waitForIdleSync();}
    private String localScope()throws Exception{return main(()->(String)method("localDataScope").invoke(activity));}
    private String selected()throws Exception{return main(()->(String)field(activity,"selectedSearchForum"));}
    private String kind()throws Exception{return main(()->(String)field(field(activity,"current"),"kind"));}
    private String id()throws Exception{return main(()->(String)field(field(activity,"current"),"id"));}
    private String extra()throws Exception{return main(()->(String)field(field(activity,"current"),"extra"));}
    private static View described(View root,String label){if(label.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View result=described(group.getChildAt(i),label);if(result!=null)return result;}}return null;}
    private static View text(View root,String label){if(root instanceof TextView&&label.contentEquals(((TextView)root).getText()))return root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View result=text(group.getChildAt(i),label);if(result!=null)return result;}}return null;}
    private static View decor(NativeGlassDialog dialog){return dialog.getWindow().getDecorView();}
    private EditText input()throws Exception{return main(()->(EditText)field(activity,"searchInput"));}
    private NativeGlassDialog search()throws Exception{return main(()->(NativeGlassDialog)field(activity,"searchDialog"));}
    private NativeGlassDialog openSearch()throws Exception{invoke("search",new Class<?>[0]);NativeGlassDialog result=search();if(result==null||!result.isShowing())throw new IllegalStateException("SEARCH_DIALOG_REQUIRED");return result;}
    private void click(View target)throws Exception{if(target==null)throw new IllegalStateException("FIXTURE_VIEW_REQUIRED");main(()->{target.performClick();return null;});waitForIdleSync();}
    private void type(String value)throws Exception{main(()->{((EditText)field(activity,"searchInput")).setText(value);return null;});}
    private void screen(String kind,String id)throws Exception{main(()->{Class<?> type=Class.forName("cn.ngapure.app.MainActivity$Screen");Constructor<?> constructor=type.getDeclaredConstructor(String.class,String.class,String.class,int.class);constructor.setAccessible(true);Object next=constructor.newInstance(kind,id,"合成原生搜索验证",1);method("go",type,boolean.class).invoke(activity,next,false);return null;});waitForIdleSync();}
    private void recreate()throws Exception{MainActivity previous=activity;int saves=recreations;main(()->{previous.recreate();return null;});for(int i=0;i<160;i++){MainActivity current=activity;if(current!=null&&current!=previous&&!current.isDestroyed()){waitForIdleSync();if(recreations<=saves)throw new AssertionError("FRAMEWORK_SAVE_REQUIRED");return;}SystemClock.sleep(25);}throw new IllegalStateException("ACTUAL_MAIN_RECREATION_TIMEOUT");}
    @SuppressWarnings("unchecked") private NativeGlassDialog lastDialog()throws Exception{return main(()->{NativeGlassDialog result=null;for(NativeGlassDialog value:(Set<NativeGlassDialog>)field(activity,"glassDialogs"))if(value.isShowing()&&!value.isClosing())result=value;return result;});}

    private void recentSearchChecks()throws Exception{
        stage="recentKeywordsNativeUi";screen("settings","");SearchHistory history=new SearchHistory(getTargetContext(),localScope());history.clear();history.recordSubmitted("合成最近甲");history.recordSubmitted("合成最近乙");NativeGlassDialog dialog=openSearch();
        check("recentKeywordsAppearInActualSearchDialog",main(()->text(decor(dialog),"合成最近甲")!=null&&text(decor(dialog),"合成最近乙")!=null));
        click(main(()->text(decor(dialog),"合成最近甲")));check("recentKeywordClickFillsWithoutNavigating",main(()->"合成最近甲".contentEquals(((EditText)field(activity,"searchInput")).getText()))&&"settings".equals(kind()));
        check("recentKeywordClickDoesNotRecordUnsubmittedText",history.get().equals(Arrays.asList("合成最近乙","合成最近甲")));
        click(main(()->described(decor(dialog),"删除搜索记录 合成最近甲")));check("singleDeleteUpdatesDiskAndNativeRows",history.get().equals(Arrays.asList("合成最近乙"))&&main(()->described(decor(dialog),"删除搜索记录 合成最近甲")==null));
        click(main(()->text(decor(dialog),"清空记录")));check("clearUpdatesDiskAndEmptyState",history.get().isEmpty()&&main(()->text(decor(dialog),"搜索过的关键词会显示在这里")!=null));
        type("合成尚未提交的搜索");main(()->{((EditText)field(activity,"searchInput")).setSelection(2,5);return null;});recreate();
        check("unsubmittedSearchRestoresWithoutHistoryRecord",search()!=null&&history.get().isEmpty()&&main(()->{EditText value=(EditText)field(activity,"searchInput");return "合成尚未提交的搜索".contentEquals(value.getText())&&value.getSelectionStart()==2&&value.getSelectionEnd()==5;}));
    }
    private void rangeChecks()throws Exception{
        stage="explicitGlobalAndBoardRange";screen("forum","650");NativeGlassDialog first=openSearch();check("forumSearchDefaultsToCurrentBoard","650".equals(selected()));
        check("currentBoardChoiceIsActuallyChecked",main(()->((RadioButton)described(decor(first),"搜索范围：当前版块")).isChecked()));
        click(main(()->described(decor(first),"搜索范围：全站")));type("合成全站草稿");recreate();NativeGlassDialog global=search();
        check("explicitGlobalChoiceSurvivesActualRecreation",global!=null&&selected().isEmpty()&&main(()->((RadioButton)described(decor(global),"搜索范围：全站")).isChecked()));
        check("rangeRecreationDoesNotSubmitQuery","forum".equals(kind())&&"650".equals(id()));
        click(main(()->described(decor(global),"搜索范围：当前版块")));type("合成版内关键词");recreate();NativeGlassDialog restored=search();
        check("explicitBoardChoiceSurvivesActualRecreation",restored!=null&&"650".equals(selected())&&main(()->((RadioButton)described(decor(restored),"搜索范围：当前版块")).isChecked()));
        click(restored.getButton(Dialog.BUTTON_POSITIVE));check("boardSearchSubmissionRetainsChosenFid","search".equals(kind())&&"合成版内关键词".equals(id())&&"650".equals(extra()));
        check("submittedKeywordIsRecordedForCurrentLocalScope",new SearchHistory(getTargetContext(),localScope()).get().get(0).equals("合成版内关键词"));
        NativeGlassDialog again=openSearch();check("reopenedResultsSearchRetainsBoardRange","650".equals(selected()));
        check("reopenedResultsSearchPrefillsCurrentKeyword",main(()->"合成版内关键词".contentEquals(((EditText)field(activity,"searchInput")).getText())));
        click(main(()->described(decor(again),"搜索范围：全站")));type("合成另一个全站关键词");click(again.getButton(Dialog.BUTTON_POSITIVE));
        check("explicitGlobalSearchUsesEmptyFid","search".equals(kind())&&extra().isEmpty());openSearch();check("reopenedGlobalResultsStayGlobal",selected().isEmpty());
        stage="negativeBoardRangeRecreation";screen("forum","-7");openSearch();type("合成负数版块");recreate();check("negativeBoardRangeSurvivesRecreation","-7".equals(selected()));
        stage="threadMetadataRangeRecreation";screen("thread","7950000001");main(()->{NgaApi.Topic topic=new NgaApi.Topic();topic.tid="7950000001";topic.fid="650";set(field(activity,"current"),"topic",topic);return null;});openSearch();type("合成帖子内搜索");recreate();check("threadMetadataBoardRangeSurvivesRecreation","650".equals(selected()));
    }
    private void historyScopeChecks(NgaStore store)throws Exception{
        stage="stableAccountHistoryAndExactSessionGuard";screen("settings","");String a=localScope();SearchHistory history=new SearchHistory(getTargetContext(),a);history.clear();history.recordSubmitted("合成账号甲关键词");NativeGlassDialog old=openSearch();View remove=main(()->described(decor(old),"删除搜索记录 合成账号甲关键词"));
        main(()->{store.setCookie(COOKIE_B);remove.performClick();return null;});waitForIdleSync();String b=localScope();check("staleHistoryDeleteCannotAffectOldOrNewAccount",history.get().equals(Arrays.asList("合成账号甲关键词"))&&new SearchHistory(getTargetContext(),b).get().isEmpty());
        main(()->{store.setCookie("ngaPassportUid=79501; ngaPassportCid=synthetic-search-renewed");return null;});screen("settings","");
        check("sameAccountSessionRefreshRetainsLocalHistoryScope",a.equals(localScope())&&new SearchHistory(getTargetContext(),localScope()).get().equals(Arrays.asList("合成账号甲关键词")));
        NativeGlassDialog renewed=openSearch();check("renewedSessionShowsExistingHistory",main(()->text(decor(renewed),"合成账号甲关键词")!=null));
        main(()->{renewed.getButton(Dialog.BUTTON_NEGATIVE).performClick();store.setCookie(COOKIE_A);return null;});screen("settings","");
    }
    private void invalidShareChecks()throws Exception{
        stage="untrustedShareCannotOpenEditor";screen("settings","");String[] invalid={"https://bbs.nga.cn/post.php?action=reply&tid=7950000001","https://bbs.nga.cn/nuke.php?__lib=post&__act=send","https://bbs.nga.cn.evil.invalid/read.php?tid=7950000001","https://alice:synthetic@bbs.nga.cn/read.php?tid=7950000001","普通分享文字"};
        for(int i=0;i<invalid.length;i++){invoke("routeIntent",new Class<?>[]{Intent.class},new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,invalid[i]));check("invalidShareStaysOnSettings"+i,"settings".equals(kind())&&main(()->field(activity,"editorView")==null&&field(activity,"loginView")==null));}
        invoke("routeIntent",new Class<?>[]{Intent.class},new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,true));check("wrongTypeShareDoesNotOpenEditor","settings".equals(kind())&&main(()->field(activity,"editorView")==null));
    }
    private void directBoardAndDarkChecks(NgaStore store)throws Exception{
        stage="directBoardNativeValidation";screen("settings","");invoke("customForum",new Class<?>[0]);NativeGlassDialog dialog=lastDialog();View target=main(()->described(decor(dialog),"版块 ID 或链接"));
        main(()->{((EditText)target).setText("0");dialog.getButton(Dialog.BUTTON_POSITIVE).performClick();return null;});check("zeroBoardIdStaysEditableWithError","settings".equals(kind())&&main(()->((EditText)target).getError()!=null&&dialog.isShowing()));
        main(()->{((EditText)target).setText("-7");dialog.getButton(Dialog.BUTTON_POSITIVE).performClick();return null;});waitForIdleSync();check("negativeBoardIdOpensNativeBoard","forum".equals(kind())&&"-7".equals(id()));
        stage="darkRangeContrast";store.setTheme("dark");screen("forum","650");NativeGlassDialog dark=openSearch();check("darkScopeLabelsUseReadableInkAndTint",main(()->{RadioButton all=(RadioButton)described(decor(dark),"搜索范围：全站"),board=(RadioButton)described(decor(dark),"搜索范围：当前版块");int a=all.getCurrentTextColor(),b=board.getCurrentTextColor();return android.graphics.Color.red(a)>160&&android.graphics.Color.red(b)>160&&all.getButtonTintList()!=null&&board.getButtonTintList()!=null;}));
        store.setTheme("light");screen("settings","");
    }
    private void settleGeometry()throws Exception{waitForIdleSync();SystemClock.sleep(120);waitForIdleSync();}
    private static Rect windowBounds(View view){int[] position=new int[2];view.getLocationInWindow(position);return new Rect(position[0],position[1],position[0]+view.getWidth(),position[1]+view.getHeight());}
    private static ScrollView bodyViewport(View child){android.view.ViewParent parent=child.getParent();while(parent instanceof View){if(parent instanceof ScrollView)return (ScrollView)parent;parent=parent.getParent();}throw new IllegalStateException("SEARCH_BODY_SCROLL_VIEW_REQUIRED");}
    private static JSONObject visibleMeasurement(View child)throws Exception{
        if(child==null)throw new IllegalStateException("SYNTHETIC_SEARCH_CHILD_REQUIRED");
        ScrollView viewport=bodyViewport(child);Rect visible=new Rect(),body=new Rect();boolean childVisible=child.getGlobalVisibleRect(visible),bodyVisible=viewport.getGlobalVisibleRect(body);
        boolean whole=childVisible&&bodyVisible&&visible.width()>=child.getWidth()&&visible.height()>=child.getHeight()&&body.contains(visible);
        return new JSONObject().put("viewWidthPx",child.getWidth()).put("viewHeightPx",child.getHeight()).put("visibleRect",visible.toShortString()).put("bodyViewportRect",body.toShortString()).put("fullyInsideBodyViewport",whole);
    }
    private void geometryActivity(int width,float fontScale,JSONArray windows)throws Exception{
        stage="geometryFixtureWindow";geometryPhase=true;geometryFontScale=fontScale;
        activity=(MainActivity)startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(),MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
        main(()->{activity.getWindow().setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);activity.getWindow().setLayout(width,900);return null;});
        screen("forum","650");settleGeometry();
        JSONObject actual=main(()->{Rect safe=cn.ngapure.app.FoldAdaptiveLayout.visibleSafeBounds(activity);View decor=activity.getWindow().getDecorView();return new JSONObject().put("requestedWidthDp",width).put("requestedHeightDp",900).put("decorWidthPx",decor.getWidth()).put("decorHeightPx",decor.getHeight()).put("safeWidthPx",safe.width()).put("safeHeightPx",safe.height()).put("density",activity.getResources().getDisplayMetrics().density).put("fontScale",activity.getResources().getConfiguration().fontScale);});
        windows.put(actual);
        check("geometryFixtureWindow"+width+"Font"+fontScale,Math.abs(actual.getDouble("density")-1)<.001&&Math.abs(actual.getDouble("fontScale")-fontScale)<.001&&Math.abs(actual.getInt("decorWidthPx")-width)<=2&&Math.abs(actual.getInt("safeWidthPx")-width)<=2&&actual.getInt("safeHeightPx")>=760);
    }
    private void firstShowHistoryGeometry(JSONObject geometry)throws Exception{
        stage="geometryFirstShowHistory";SearchHistory history=new SearchHistory(getTargetContext(),localScope());history.clear();history.recordSubmitted("几何历史首条");history.recordSubmitted("几何历史最新");
        NativeGlassDialog dialog=openSearch();settleGeometry();
        JSONObject heading=main(()->visibleMeasurement(text(decor(dialog),"最近搜索"))),first=main(()->visibleMeasurement(text(decor(dialog),"几何历史最新")));
        geometry.put("firstShowHeading",heading).put("firstShowFirstKeyword",first).put("scrolledBeforeFirstShowAssertions",false);
        check("firstShowHistoryHeadingFullyInsideBodyViewport",heading.getBoolean("fullyInsideBodyViewport"));
        check("firstShowFirstKeywordFullyInsideBodyViewport",first.getBoolean("fullyInsideBodyViewport"));
        click(main(()->text(decor(dialog),"清空记录")));settleGeometry();
        check("emptyHistoryKeepsHintWithoutClearAction",main(()->text(decor(dialog),"搜索过的关键词会显示在这里")!=null&&text(decor(dialog),"清空记录")==null&&text(decor(dialog),"最近搜索")==null));
        main(()->{dialog.dismiss();return null;});
    }
    private static JSONObject radioMeasurement(RadioButton value)throws Exception{
        Layout layout=value.getLayout();if(layout==null)throw new IllegalStateException("MEASURED_RADIO_TEXT_REQUIRED");
        int contentWidth=value.getWidth()-value.getCompoundPaddingLeft()-value.getCompoundPaddingRight(),contentHeight=value.getHeight()-value.getCompoundPaddingTop()-value.getCompoundPaddingBottom();
        boolean complete=layout.getLineCount()>0&&layout.getLineEnd(layout.getLineCount()-1)==value.length();JSONArray lines=new JSONArray();
        for(int line=0;line<layout.getLineCount();line++){
            boolean fits=layout.getEllipsisCount(line)==0&&layout.getLineLeft(line)>=-1&&layout.getLineRight(line)<=contentWidth+1&&layout.getLineBottom(line)<=contentHeight+1;
            complete&=fits;lines.put(new JSONObject().put("end",layout.getLineEnd(line)).put("left",layout.getLineLeft(line)).put("right",layout.getLineRight(line)).put("bottom",layout.getLineBottom(line)).put("ellipsis",layout.getEllipsisCount(line)).put("fits",fits));
        }
        int touch=Math.round(48*value.getResources().getDisplayMetrics().density);
        return new JSONObject().put("text",value.getText().toString()).put("contentWidthPx",contentWidth).put("contentHeightPx",contentHeight).put("widthPx",value.getWidth()).put("heightPx",value.getHeight()).put("windowBounds",windowBounds(value).toShortString()).put("minimumTouchPx",touch).put("minimumTouchTargetRetained",value.getWidth()>=touch&&value.getHeight()>=touch).put("completeTextFits",complete).put("lines",lines);
    }
    private int scopeGeometrySample(NativeGlassDialog dialog,String name,JSONArray samples)throws Exception{
        settleGeometry();JSONObject sample=main(()->{RadioButton all=(RadioButton)described(decor(dialog),"搜索范围：全站"),board=(RadioButton)described(decor(dialog),"搜索范围：当前版块");RadioGroup group=(RadioGroup)all.getParent();Rect one=windowBounds(all),two=windowBounds(board);return new JSONObject().put("name",name).put("orientation",group.getOrientation()==RadioGroup.HORIZONTAL?"horizontal":"vertical").put("groupWidthPx",group.getWidth()).put("groupHeightPx",group.getHeight()).put("all",radioMeasurement(all)).put("board",radioMeasurement(board)).put("targetsDoNotOverlap",!Rect.intersects(one,two));});
        samples.put(sample);check(name+"CompleteScopeLabels",sample.getJSONObject("all").getBoolean("completeTextFits")&&sample.getJSONObject("board").getBoolean("completeTextFits"));check(name+"Minimum48DpTargets",sample.getJSONObject("all").getBoolean("minimumTouchTargetRetained")&&sample.getJSONObject("board").getBoolean("minimumTouchTargetRetained"));check(name+"TargetsDoNotOverlap",sample.getBoolean("targetsDoNotOverlap"));
        return "horizontal".equals(sample.getString("orientation"))?1:2;
    }
    private void geometryChecks()throws Exception{
        JSONObject geometry=new JSONObject().put("ownActivityConfigurationOnly",true).put("globalDisplaySettingsChanged",false).put("windowHeightDp",900).put("longScopeMayRequireBodyScrolling",true);JSONArray windows=new JSONArray(),samples=new JSONArray();geometry.put("windows",windows).put("scopeSamples",samples);report.put("geometryAudit",geometry);int orientations=0;
        for(float scale:new float[]{1f,2f})for(int width:new int[]{412,220}){
            geometryActivity(width,scale,windows);if(width==412&&scale==1f)firstShowHistoryGeometry(geometry);
            stage="geometryScopeLabels";new SearchHistory(getTargetContext(),localScope()).clear();NativeGlassDialog dialog=openSearch();
            main(()->{((RadioButton)described(decor(dialog),"搜索范围：当前版块")).setText("版块内：萌萌宠物");return null;});
            String tag="scope"+width+"Font"+scale;orientations|=scopeGeometrySample(dialog,tag+"Short",samples);
            main(()->{((RadioButton)described(decor(dialog),"搜索范围：当前版块")).setText("版块内：这是用于验证自动换行的合成长版块名称");return null;});orientations|=scopeGeometrySample(dialog,tag+"Long",samples);
            main(()->{dialog.dismiss();return null;});
        }
        check("scopeGeometryCoversHorizontalAndVertical",orientations==3);geometry.put("completed",true);
    }
    private static final class LocalDisabled extends HttpURLConnection {
        LocalDisabled(URL value){super(value);}
        public void connect()throws IOException{throw new IOException("LOCAL_FIXTURE_NETWORK_DISABLED");}public void disconnect(){}public boolean usingProxy(){return false;}
        @Override public int getResponseCode()throws IOException{BLOCKED_CONNECTIONS.incrementAndGet();throw new IOException("LOCAL_FIXTURE_NETWORK_DISABLED");}
        @Override public InputStream getInputStream()throws IOException{throw new IOException("LOCAL_FIXTURE_NETWORK_DISABLED");}
        @Override public OutputStream getOutputStream()throws IOException{throw new IOException("LOCAL_FIXTURE_NETWORK_DISABLED");}
    }
    private void blockAllHttp(){URL.setURLStreamHandlerFactory(protocol->("https".equals(protocol)||"http".equals(protocol))?new URLStreamHandler(){@Override protected URLConnection openConnection(URL url){return new LocalDisabled(url);}}:null);}
    @Override public void onStart(){Application app=null;try{
        if(!authorized||android.os.Build.VERSION.SDK_INT<29||!"cn.ngapure.searchprobe".equals(getTargetContext().getPackageName())||getTargetContext().checkSelfPermission(android.Manifest.permission.INTERNET)==android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IllegalStateException("OWN_UID_NO_INTERNET_API29_SYNTHETIC_REQUIRED");
        blockAllHttp();NgaStore store=new NgaStore(getTargetContext());store.setAutoCheckIn(false);store.setMotionEnabled(false);store.setTheme("light");store.setCookie(COOKIE_A);getTargetContext().getSharedPreferences("nga_unread_notifications",0).edit().putBoolean("enabled",false).commit();
        app=(Application)getTargetContext().getApplicationContext();app.registerActivityLifecycleCallbacks(lifecycle);activity=(MainActivity)startActivitySync(new Intent().setClassName(getTargetContext().getPackageName(),MainActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
        recentSearchChecks();rangeChecks();historyScopeChecks(store);invalidShareChecks();directBoardAndDarkChecks(store);check("actualFrameworkRecreationsPerformed",recreations>=5&&savedPrompts>=5);if(geometryAudit)geometryChecks();report.put("completed",true).put("passed",true).put("assertions",assertions).put("actualMainRecreations",recreations).put("eightFieldSearchPromptsSaved",savedPrompts);
    }catch(Exception|Error failure){try{report.put("completed",false).put("passed",false).put("failureStage",stage).put("failureType",failure.getClass().getSimpleName()).put("assertions",assertions);}catch(Exception ignored){}
    }finally{try{if(activity!=null&&!activity.isDestroyed())main(()->{activity.finish();return null;});if(app!=null)app.unregisterActivityLifecycleCallbacks(lifecycle);report.put("ownUidOnly",true).put("internetPermission",false).put("realNetworkConnections",0).put("blockedLocalConnectionAttempts",BLOCKED_CONNECTIONS.get()).put("realCommunityMutationCount",0).put("realClientStarted",false).put("realAccountAccessed",false).put("clipboardAccessedByProbe",false).put("privateInputsExported",false);}catch(Exception ignored){}Bundle result=new Bundle();result.putString("safe_search_everyday_report",report.toString());finish(Activity.RESULT_OK,result);}}
}
