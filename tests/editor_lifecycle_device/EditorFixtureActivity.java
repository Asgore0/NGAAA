package cn.ngapure.editorfixturev195;
import android.app.Activity;import android.os.Bundle;import android.widget.FrameLayout;import cn.ngapure.app.NativeEditorView;import cn.ngapure.app.NgaStore;
/** Isolated application data and keystore; only deliberately synthetic account material is stored. */
public final class EditorFixtureActivity extends Activity {
 public FrameLayout holder;public NativeEditorView editor;public int published;
 @Override public void onCreate(Bundle state){super.onCreate(state);holder=new FrameLayout(this);setContentView(holder);NgaStore store=new NgaStore(this);store.setCookie("ngaPassportUid=123; ngaPassportCid=synthetic-editor-lifecycle-only");}
 public NativeEditorView show(){closeCurrent();editor=new NativeEditorView(this,new NgaStore(this),"new","7","","",new NativeEditorView.Listener(){public void published(String tid,String pid){published++;}public void chooseAttachment(NativeEditorView owner){}});holder.addView(editor,new FrameLayout.LayoutParams(-1,-1));return editor;}
 public void closeCurrent(){if(editor!=null)editor.close();holder.removeAllViews();}
 @Override public void onDestroy(){closeCurrent();super.onDestroy();}
}
