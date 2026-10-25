package cn.ngapure.gesture;


/** Only types synthetic local text. Never presses publish/send/upload or opens another app. */
@SuppressWarnings("deprecation")
public final class NativeTextFixtureTest extends com.android.uiautomator.testrunner.UiAutomatorTestCase {
    private static final String TARGET="cn.ngapure.app";
    private static final class TextField extends com.android.uiautomator.core.UiObject {
        TextField(int index){super(new com.android.uiautomator.core.UiSelector().packageName(TARGET).className("android.widget.EditText").instance(index));}
        @Override public boolean setText(String value){
            android.view.accessibility.AccessibilityNodeInfo node=findAccessibilityNodeInfo(5000);
            if(node==null||!node.isEnabled())return false;
            android.os.Bundle args=new android.os.Bundle();
            args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);
            return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,args);
        }
    }
    private TextField field(int index){return new TextField(index);}
    private void target() throws Exception {if(!TARGET.equals(getUiDevice().getCurrentPackageName()))throw new AssertionError("Target app must be foreground");}
    public void testEditorFixture()throws Exception{
        target();if(!field(0).exists()||!field(1).exists()||field(2).exists())throw new AssertionError("Expected native topic editor");
        String existing=field(0).getText();
        if(!existing.isEmpty()&&!existing.equals("标题")&&!existing.startsWith("Native_")&&!existing.startsWith("原生测试"))throw new AssertionError("Refusing to replace a non-test draft");
        if(!field(0).setText("原生测试 · 仅本机草稿"))throw new AssertionError("Unable to set title");
        String body="这份内容只保存在本机，不会发布。\n[b]原生粗体[/b] [s:ac:哭笑] [s:ac:goodjob] [s:a2:不明觉厉]\n[collapse=点开查看]折叠内容与 [s:ac:茶][/collapse]\n[code]line 1\n    line 2 & <text>[/code]";
        if(!field(1).setText(body))throw new AssertionError("Unable to set body");
        Thread.sleep(1000);
        if(!"原生测试 · 仅本机草稿".equals(field(0).getText())||!body.equals(field(1).getText()))throw new AssertionError("Editor did not retain the synthetic text");
        System.out.println("PASS synthetic editor fixture verified locally; no submit action");
    }
    public void testMessageFixture()throws Exception{
        target();if(!field(0).exists()||!field(1).exists()||!field(2).exists()||field(3).exists())throw new AssertionError("Expected native new-message dialog");
        String existing=field(1).getText();if(!existing.isEmpty()&&!existing.equals("标题")&&!existing.startsWith("原生测试"))throw new AssertionError("Refusing to replace a non-test message draft");
        if(!field(1).setText("原生测试 · 私信草稿不会发送"))throw new AssertionError("Unable to set PM subject");
        if(!field(2).setText("仅本机保存，测试关闭、旋转与进程重启后的恢复。不会点击检查并发送。"))throw new AssertionError("Unable to set PM body");
        Thread.sleep(1000);
        if(!"原生测试 · 私信草稿不会发送".equals(field(1).getText())||!field(2).getText().startsWith("仅本机保存"))throw new AssertionError("Message editor did not retain synthetic text");
        System.out.println("PASS synthetic message fixture verified locally; no send action");
    }
    public void testPreviewFixture()throws Exception{
        target();
        if(!field(0).getText().startsWith("原生测试"))throw new AssertionError("Expected our local draft");
        com.android.uiautomator.core.UiSelector preview=new com.android.uiautomator.core.UiSelector().packageName(TARGET).text("预览");
        new com.android.uiautomator.core.UiScrollable(new com.android.uiautomator.core.UiSelector().className("android.widget.ScrollView")).scrollIntoView(preview);
        if(!new com.android.uiautomator.core.UiObject(preview).click())throw new AssertionError("Preview unreachable");
        if(!new com.android.uiautomator.core.UiObject(new com.android.uiautomator.core.UiSelector().text("内容预览")).waitForExists(5000))throw new AssertionError("Native preview absent");
        System.out.println("PASS local native preview reachable");
    }
}
