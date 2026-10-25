package cn.ngapure.gesture;

/** Sets only the explicitly selected local search/filter/page field; never submits an action. */
@SuppressWarnings("deprecation")
public final class EverydayTextInput extends com.android.uiautomator.testrunner.UiAutomatorTestCase {
    private static final String TARGET="cn.ngapure.app";
    private static final class Field extends com.android.uiautomator.core.UiObject {
        Field(String label){super(new com.android.uiautomator.core.UiSelector().packageName(TARGET).className("android.widget.EditText").description(label));}
        boolean replace(String value){android.view.accessibility.AccessibilityNodeInfo node=findAccessibilityNodeInfo(5000);if(node==null||!node.isEnabled())return false;android.os.Bundle args=new android.os.Bundle();args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,args);}
    }
    public void testLocalInput()throws Exception{
        if(!TARGET.equals(getUiDevice().getCurrentPackageName()))throw new AssertionError("Expected the dedicated native client foreground");
        String label=getParams().getString("label"),encoded=getParams().getString("text64");
        if(!("搜索内容".equals(label)||"筛选浏览历史".equals(label)||"筛选本地收藏".equals(label)||"版块筛选关键词".equals(label)||"版块 ID 或链接".equals(label)))throw new AssertionError("Only local navigation fields are allowed");
        if(encoded==null||encoded.length()>5500)throw new AssertionError("Bounded explicit input required");
        String value=new String(java.util.Base64.getDecoder().decode(encoded),java.nio.charset.StandardCharsets.UTF_8);if(value.length()>4096)throw new AssertionError("Input too long");
        Field field=new Field(label);if(!field.replace(value)||!value.equals(field.getText()))throw new AssertionError("Text field did not retain the exact input");
        System.out.println("LOCAL_INPUT_SET; no button clicked, no submission");
    }
}
