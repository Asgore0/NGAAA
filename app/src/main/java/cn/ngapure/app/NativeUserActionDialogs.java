package cn.ngapure.app;

import android.app.Activity;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import java.io.ByteArrayInputStream;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Button;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

/** Native glass account actions. Every write has a visible confirmation and retained busy state. */
public final class NativeUserActionDialogs {
    public interface AvatarSelection {void selected(Uri uri);}
    public interface Host {void changed();void openUser(String uid,String username);void chooseAvatar(AvatarSelection selection);}
    private interface Work<T>{T run() throws Exception;}
    private interface Done<T>{void accept(T value);}
    private final Activity activity;private final boolean dark;private final Executor executor,confirmedWriteExecutor;private final Host host;
    private final List<NativeGlassDialog> dialogs=new ArrayList<>();private volatile boolean closed;
    private final Object avatarReadLock=new Object();
    private volatile int avatarGeneration;
    private InputStream avatarInput;
    private FutureTask<?> avatarDecode;
    private Uri avatarUri;
    private NativeUserActions avatarApi;
    private NativeAvatarCropView avatarCrop;
    private NativeGlassDialog avatarDialog;
    private float[] avatarTransform;
    public NativeUserActionDialogs(Activity activity,boolean dark,Executor executor,Host host){this(activity,dark,executor,executor,host);}
    /** Disposable reads and explicitly confirmed writes have separate lifecycle owners. */
    public NativeUserActionDialogs(Activity activity,boolean dark,Executor readExecutor,Executor writeExecutor,Host host){
        if(activity==null||readExecutor==null||writeExecutor==null||host==null)throw new IllegalArgumentException("缺少用户操作配置");
        this.activity=activity;this.dark=dark;executor=readExecutor;confirmedWriteExecutor=writeExecutor;this.host=host;
    }
    public void close(){closed=true;clearAvatarPreview(true);for(NativeGlassDialog dialog:new ArrayList<>(dialogs))dialog.dismiss();dialogs.clear();}
    private boolean alive(){return !closed&&!activity.isFinishing()&&!activity.isDestroyed();}
    private NativeGlassDialog.Builder builder(String title){return new NativeGlassDialog.Builder(activity,dark).setTitle(title);}
    private NativeGlassDialog show(NativeGlassDialog.Builder builder){NativeGlassDialog dialog=builder.create();dialogs.add(dialog);dialog.setOnDismissListener(d->dialogs.remove(dialog));dialog.show();return dialog;}
    private void error(Exception error){if(!alive())return;String message=error instanceof NativeCommunity.CommunityException||error instanceof NativeUserActions.PartialFolderException||error instanceof NativeCloudBlocks.ConflictException||error instanceof NativeCloudBlocks.CacheException||error instanceof IllegalArgumentException?error.getMessage():"操作未完成，请检查网络后重新核对";show(builder("操作未完成").setMessage(message).setPositiveButton("知道了",null));}
    private <T> void read(String title,Work<T> work,Done<T> done){
        if(!alive())return;
        NativeGlassDialog progress=show(builder(title).setProgress(true).setMessage("正在读取云端数据…").setNegativeButton("取消",null));
        RequestCancellation.Token token=new RequestCancellation.Token();
        final boolean[] accepted={true},settled={false};
        final FutureTask<?>[] pending={null};
        Runnable cancel=()->{
            if(settled[0])return;accepted[0]=false;token.cancel();
            FutureTask<?> task=pending[0];if(task!=null)task.cancel(true);
        };
        progress.setOnDismissListener(d->{dialogs.remove(progress);cancel.run();});
        progress.setOnCancelListener(d->cancel.run());
        progress.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v->{cancel.run();progress.dismiss();});
        FutureTask<Void> task=new FutureTask<>(()->{
            RequestCancellation.Scope scope=RequestCancellation.bind(token);
            try{
                RequestCancellation.check();T value=work.run();
                activity.runOnUiThread(()->{
                    boolean visible=progress.isShowing()&&!progress.isClosing();settled[0]=true;progress.dismiss();
                    if(alive()&&visible&&accepted[0]&&!token.isCancelled())done.accept(value);
                });
            }catch(Exception failure){
                activity.runOnUiThread(()->{
                    boolean visible=progress.isShowing()&&!progress.isClosing();settled[0]=true;progress.dismiss();
                    if(alive()&&visible&&accepted[0]&&!token.isCancelled())error(failure);
                });
            }finally{scope.close();}
            return null;
        });
        pending[0]=task;
        try{executor.execute(task);}catch(RuntimeException unavailable){cancel.run();progress.dismiss();if(alive())error(new IOException("读取暂时未能开始，请重试"));}
    }
    private boolean acceptsWrite(NativeGlassDialog dialog){return alive()&&dialog!=null&&dialogs.contains(dialog)&&dialog.isShowing()&&!dialog.isClosing()&&dialog.getButton(DialogInterface.BUTTON_POSITIVE)!=null&&dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled();}
    private <T> boolean write(NativeGlassDialog dialog,Work<T> work,Done<T> done){
        if(!acceptsWrite(dialog))return false;
        final String expectedScope=writeUiScope();
        final int[] which={DialogInterface.BUTTON_POSITIVE,DialogInterface.BUTTON_NEGATIVE,DialogInterface.BUTTON_NEUTRAL};
        final boolean[] enabled=new boolean[which.length];
        final CharSequence positiveLabel=dialog.getButton(DialogInterface.BUTTON_POSITIVE).getText();
        dialog.setCancelable(false);
        for(int i=0;i<which.length;i++){Button button=dialog.getButton(which[i]);if(button!=null){enabled[i]=button.isEnabled();button.setEnabled(false);}}
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText("正在提交并核对…");
        try{confirmedWriteExecutor.execute(()->{
            try{T value=work.run();activity.runOnUiThread(()->{dialog.dismiss();if(alive()&&expectedScope.equals(writeUiScope()))done.accept(value);});}
            catch(Exception failure){activity.runOnUiThread(()->{dialog.dismiss();if(alive()&&expectedScope.equals(writeUiScope()))error(failure);});}
        });return true;}catch(RejectedExecutionException busy){
            if(!alive()||!dialog.isShowing()||dialog.isClosing())return false;
            dialog.setCancelable(true);
            for(int i=0;i<which.length;i++){Button button=dialog.getButton(which[i]);if(button!=null)button.setEnabled(enabled[i]);}
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText(positiveLabel);
            error(new IllegalArgumentException("提交任务较多，请稍后重新确认。本次未发出请求，也不会自动重试。"));
            return false;
        }
    }
    /** Hash only: old account results may not enter a new account's live UI. Never persisted. */
    private String writeUiScope(){
        NgaStore.CheckInSession state=new NgaStore(activity.getApplicationContext()).captureCheckInSession();
        try{byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest((state.host+"\n"+state.cookie).getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder value=new StringBuilder();for(byte item:digest)value.append(String.format(java.util.Locale.ROOT,"%02x",item&255));return value.toString();}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("无法隔离用户操作结果",impossible);}
    }
    private void outcome(NativeUserActions.Outcome value,Runnable changed){if(!alive())return;Toast.makeText(activity,value.message,Toast.LENGTH_LONG).show();if(changed!=null)changed.run();host.changed();}
    public void showUser(String uid,String username,boolean self){
        read("用户操作",()->{NativeUserActions api=new NativeUserActions(activity);return new UserState(api,self?null:api.relationship(uid));},state->{
            boolean blocked=NativeUserActions.isLocallyBlocked(activity,uid);String[] labels=self?new String[]{"编辑签名","更换头像","我的关注","云端屏蔽与关键词","管理本机屏蔽"}:new String[]{state.relationship.followed?"取消关注（云端）":"关注此用户（云端）","NGA 云端屏蔽设置",blocked?"解除本机屏蔽":"屏蔽此用户（仅本机）"};
            show(builder(self?"我的资料设置":username+" 的用户操作").setMessage(self?"资料修改将保存到当前 NGA 账号":"关注同步到 NGA；本机屏蔽只隐藏当前账号在本客户端看到的主题和回复。").setItems(labels,(dialog,which)->{if(self){if(which==0)editSignature(state.api);if(which==1)chooseAvatar(state.api);if(which==2)showFollowing(1);if(which==3)showCloudBlocks();if(which==4)showBlockedUsers();}else if(which==0)confirmFollow(state.api,uid,username,!state.relationship.followed);else if(which==1)showCloudBlockUser(uid,username);else confirmBlock(state.api,uid,username,!blocked);}).setNegativeButton("关闭",null));
        });
    }
    private void chooseAvatar(NativeUserActions api){
        try{api.requireCurrent();host.chooseAvatar(uri->{if(uri!=null&&alive())editAvatar(api,uri);});}
        catch(NativeCommunity.CommunityException failure){error(failure);}
    }
    private static final class CloudState{final NativeUserActions api;final NativeCloudBlocks.Snapshot data;CloudState(NativeUserActions a,NativeCloudBlocks.Snapshot d){api=a;data=d;}}
    private CloudState cloudState() throws Exception {NativeUserActions api=new NativeUserActions(activity);NativeCloudBlocks.Snapshot data=api.cloudBlocks();api.cacheBlocks(data);return new CloudState(api,data);}
    public void showCloudBlocks(){read("云端屏蔽与关键词",this::cloudState,state->{LinearLayout content=column();TextView users=new TextView(activity);users.setText("屏蔽用户（用户 ID、用户名或 ID/用户名，以空格分隔）");users.setTextColor(AppPalette.of(dark).ink);content.addView(users);EditText authors=input("例如：用户 ID",state.data.authors,true);authors.setMinLines(3);content.addView(authors);TextView terms=new TextView(activity);terms.setText("屏蔽内容关键词（以空格分隔）");terms.setTextColor(AppPalette.of(dark).ink);content.addView(terms);EditText keywords=input("包含任一关键词的内容将隐藏",state.data.keywords,true);keywords.setMinLines(3);content.addView(keywords);NativeGlassDialog dialog=show(builder("NGA 云端屏蔽").setMessage("同步 NGA 云端屏蔽列表。当前 "+state.data.authorCount()+" 个用户、"+state.data.keywordCount()+" 个关键词"+(state.data.limit>0?"，合计上限 "+state.data.limit:"" )+"。本机屏蔽另行保存。").setView(content).setNegativeButton("取消",null).setPositiveButton("保存到 NGA 云端",null));dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{String savedAuthors=authors.getText().toString(),savedKeywords=keywords.getText().toString();write(dialog,()->state.api.saveCloudBlocks(state.data,savedKeywords,savedAuthors),result->outcome(result,null));});});}
    private void showCloudBlockUser(String uid,String name){read("读取云端屏蔽状态",this::cloudState,state->{boolean blocked=state.data.contains(uid,name);NativeGlassDialog dialog=show(builder(blocked?"解除云端屏蔽":"云端屏蔽用户").setMessage((blocked?"解除对 ":"在 NGA 云端屏蔽 ")+name+"？原有关键词和其他用户将保留。").setNegativeButton("取消",null).setPositiveButton(blocked?"确认解除云端屏蔽":"确认云端屏蔽",null));dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->write(dialog,()->state.api.setCloudBlock(uid,name,!blocked),result->outcome(result,null)));});}
    private static final class UserState{final NativeUserActions api;final NativeUserActions.Relationship relationship;UserState(NativeUserActions a,NativeUserActions.Relationship r){api=a;relationship=r;}}
    private void confirmFollow(NativeUserActions api,String uid,String name,boolean desired){NativeGlassDialog dialog=show(builder(desired?"关注此用户":"取消关注").setMessage((desired?"关注 ":"取消关注 ")+name+"？操作会同步到 NGA 云端。").setNegativeButton("取消",null).setPositiveButton(desired?"确认关注":"确认取消关注",null));dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->write(dialog,()->api.setFollow(uid,desired),value->outcome(value,null)));}
    private void confirmBlock(NativeUserActions api,String uid,String name,boolean blocked){NativeGlassDialog dialog=show(builder(blocked?"本机屏蔽用户":"解除本机屏蔽").setMessage((blocked?"隐藏 "+name+" 的主题和回复？":"恢复显示 "+name+" 的主题和回复？")+"此设置只保存在本机，按 NGA 账号和线路分别保存。").setNegativeButton("取消",null).setPositiveButton(blocked?"确认屏蔽":"确认解除",null));dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->write(dialog,()->{api.setLocalBlock(uid,name,blocked);return new NativeUserActions.Outcome(blocked?"已在本机屏蔽":"已解除本机屏蔽",uid);},value->outcome(value,null)));}
    public void showBlockedUsers(){List<NativeUserActions.BlockedUser> users=NativeUserActions.blockedUsers(activity);if(users.isEmpty()){show(builder("本机屏蔽列表").setMessage("当前账号没有本机屏蔽的用户。屏蔽仅在本机生效，不会修改 NGA 云端关系。").setNeutralButton("清理本机屏蔽数据",(d,n)->clearBlocks()).setPositiveButton("完成",null));return;}CharSequence[] labels=new CharSequence[users.size()];for(int i=0;i<users.size();i++)labels[i]=users.get(i).name+" · 解除屏蔽";show(builder("本机屏蔽列表").setMessage("当前账号和线路 · "+users.size()+" 人").setItems(labels,(d,n)->{NativeUserActions.BlockedUser user=users.get(n);try{confirmBlock(new NativeUserActions(activity),user.uid,user.name,false);}catch(Exception e){error(e);}}).setNeutralButton("清空本机屏蔽",(d,n)->clearBlocks()).setNegativeButton("关闭",null));}
    private void clearBlocks(){
        final NativeUserActions api;try{api=new NativeUserActions(activity);}catch(NativeCommunity.CommunityException unavailable){error(unavailable);return;}
        NativeGlassDialog dialog=show(builder("清空本机屏蔽").setMessage("清空当前 NGA 账号和线路的本机屏蔽列表？其他账号与云端关注关系不受影响。").setNegativeButton("取消",null).setPositiveButton("确认清空",null));
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->write(dialog,()->{api.clearLocalBlockList();return new NativeUserActions.Outcome("已清空当前账号的本机屏蔽","");},result->outcome(result,null)));
    }
    public void showFollowing(int page){read("我的关注",()->new NativeUserActions(activity).followedUsers(page),data->{CharSequence[] labels=new CharSequence[data.items.size()];for(int i=0;i<labels.length;i++)labels[i]=data.items.get(i).username+" · 查看资料";NativeGlassDialog.Builder builder=builder("我的关注 · 第 "+page+" 页").setMessage(labels.length==0?"这一页没有关注用户。":"关注关系保存在 NGA 云端。").setItems(labels,(d,n)->{NativeUserActions.FollowedUser user=data.items.get(n);host.openUser(user.uid,user.username);}).setNegativeButton("关闭",null);if(page>1)builder.setNeutralButton("上一页",(d,n)->showFollowing(page-1));if(data.canTryNext)builder.setPositiveButton("下一页",(d,n)->showFollowing(page+1));show(builder);});}
    private void editSignature(NativeUserActions api){read("编辑签名",api::signature,value->{EditText input=input("签名支持 NGA BBCode",value,true);NativeGlassDialog dialog=show(builder("编辑签名").setMessage("保存后同步到 NGA。签名支持原有 BBCode。").setView(input).setNegativeButton("取消",null).setPositiveButton("保存到云端",null));dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{String sign=input.getText().toString();if(sign.length()>30000){input.setError("签名不能超过 30000 字");return;}write(dialog,()->api.setSignature(sign),result->outcome(result,null));});});}
    /** Caller has consumed a picker request bound to this exact session. Local preview never uploads. */
    public void resumeAvatarSelection(Uri uri){
        if(!alive())return;
        try{
            if(uri==null||!"content".equals(uri.getScheme())||uri.getAuthority()==null||uri.getAuthority().isEmpty())throw new IllegalArgumentException("请选择系统图库中的图片");
            NativeUserActions api=new NativeUserActions(activity);api.requireCurrent();editAvatar(api,uri);
        }catch(Exception failure){error(failure);}
    }
    /** Only a local preview is restorable. Uploading is deliberately excluded to prevent replay. */
    public String[] saveAvatarPreview(String currentScope){
        if(!alive()||avatarUri==null||avatarApi==null||!avatarApi.current()||avatarDialog==null||avatarDialog.isClosing())return null;
        float[] transform=avatarCrop==null?avatarTransform:avatarCrop.saveTransform();
        AvatarPickerState.Crop state=AvatarPickerState.crop(currentScope,avatarUri.toString(),transform==null?new float[]{1,0,0}:transform);
        return state==null?null:state.encode();
    }
    public void restoreAvatarPreview(Object raw,String currentScope,boolean loggedIn){
        if(!alive())return;AvatarPickerState.Crop state=AvatarPickerState.decodeCrop(raw);
        if(state==null||!state.matches(currentScope,loggedIn)){Toast.makeText(activity,"头像编辑已失效，请在当前账号重新选择",Toast.LENGTH_SHORT).show();return;}
        try{NativeUserActions api=new NativeUserActions(activity);api.requireCurrent();editAvatar(api,Uri.parse(state.uri),state.transform());}
        catch(Exception failure){error(failure);}
    }
    public void showFolders(Runnable changed){read("管理云端收藏夹",()->{NativeUserActions api=new NativeUserActions(activity);return new FolderState(api,api.folders());},state->{CharSequence[] labels=new CharSequence[state.folders.size()];for(int i=0;i<labels.length;i++){NativeCommunity.Folder f=state.folders.get(i);labels[i]=f.name+(f.defaultFolder?" · 默认":"")+(f.publicFolder?" · 公开":" · 私密");}show(builder("管理云端收藏夹").setMessage(labels.length==0?"还没有云端收藏夹。在帖子中选择“新建收藏夹并收藏”即可创建。":"点击收藏夹修改名称、可见性、默认选项或删除。新建收藏夹可从帖子收藏入口完成。").setItems(labels,(d,n)->folderForm(state.api,state.folders.get(n),null,null,changed)).setNegativeButton("关闭",null));});}
    public void chooseFavorite(String tid,String pid,Runnable changed){read("收藏到云端",()->{NativeUserActions api=new NativeUserActions(activity);return new FolderState(api,api.folders());},state->{CharSequence[] labels=new CharSequence[state.folders.size()+1];for(int i=0;i<state.folders.size();i++){NativeCommunity.Folder f=state.folders.get(i);labels[i]=f.name+(f.defaultFolder?" · 默认":"")+(f.publicFolder?" · 公开":" · 私密");}labels[labels.length-1]="＋ 新建收藏夹并收藏";show(builder("选择云端收藏夹").setMessage(pid==null||pid.isEmpty()||pid.equals("0")?"收藏整个主题到 NGA 云端":"收藏这条回复到 NGA 云端").setItems(labels,(d,n)->{if(n==state.folders.size())folderForm(state.api,null,tid,pid,changed);else{NativeCommunity.Folder folder=state.folders.get(n);NativeGlassDialog confirm=show(builder("确认收藏").setMessage("收藏到“"+folder.name+"”？"+(folder.publicFolder?"此收藏夹公开可见。":"")).setNegativeButton("取消",null).setPositiveButton("收藏到云端",null));confirm.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->write(confirm,()->state.api.addFavorite(tid,pid,folder.id),result->outcome(result,changed)));}}).setNegativeButton("取消",null));});}
    private static final class FolderState{final NativeUserActions api;final List<NativeCommunity.Folder> folders;FolderState(NativeUserActions a,List<NativeCommunity.Folder> f){api=a;folders=f;}}
    private void folderForm(NativeUserActions api,NativeCommunity.Folder folder,String tid,String pid,Runnable changed){boolean create=folder==null;LinearLayout content=column();EditText name=input("收藏夹名称",create?"":folder.name,false);content.addView(name);CheckBox publicBox=new CheckBox(activity);publicBox.setText("公开收藏夹（其他 NGA 用户可查看）");publicBox.setChecked(!create&&folder.publicFolder);content.addView(publicBox);CheckBox defaultBox=new CheckBox(activity);defaultBox.setText("设为默认收藏夹");defaultBox.setChecked(!create&&folder.defaultFolder);content.addView(defaultBox);NativeGlassDialog.Builder config=builder(create?"新建收藏夹并收藏":"编辑云端收藏夹").setMessage(create?"将此帖子收藏到新收藏夹，再保存名称和选项。":"修改将同步到 NGA 云端。").setView(content).setNegativeButton("取消",null).setPositiveButton(create?"新建并收藏":"保存到云端",null);if(!create)config.setNeutralButton("删除收藏夹",(d,n)->deleteFolder(api,folder,changed));NativeGlassDialog dialog=show(config);dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{String value=name.getText().toString().trim();if(value.isEmpty()||value.length()>100){name.setError("请输入 1–100 字的名称");return;}boolean savedPublic=publicBox.isChecked(),savedDefault=defaultBox.isChecked();write(dialog,()->create?api.createFolderWithFavorite(tid,pid,value,savedPublic,savedDefault):api.modifyFolder(folder.id,value,savedPublic,savedDefault),result->outcome(result,changed));});}
    private void deleteFolder(NativeUserActions api,NativeCommunity.Folder folder,Runnable changed){NativeGlassDialog dialog=show(builder("删除云端收藏夹").setMessage("删除“"+folder.name+"”及其中全部收藏？该操作会同步到 NGA 云端，无法撤销。").setNegativeButton("保留",null).setPositiveButton("确认删除收藏夹",null));dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->write(dialog,()->api.deleteFolder(folder.id),result->outcome(result,changed)));}
    private void editAvatar(NativeUserActions api,Uri uri){editAvatar(api,uri,null);}
    private void editAvatar(NativeUserActions api,Uri uri,float[] restored){
        if(!alive())return;
        if(uri==null||!"content".equals(uri.getScheme())){error(new IllegalArgumentException("请选择系统图库中的图片"));return;}
        clearAvatarPreview(true);final int owner=avatarGeneration;avatarUri=uri;avatarApi=api;avatarTransform=restored==null?new float[]{1,0,0}:restored.clone();
        NativeGlassDialog progress=show(builder("准备头像").setProgress(true).setMessage("正在读取本机图片…").setNegativeButton("取消",null));ownAvatarDialog(progress);
        avatarDecode=new FutureTask<Void>(()->{
            Bitmap decoded=null;Exception failure=null;
            try{api.requireCurrent();decoded=decode(uri,owner);}catch(Exception unavailable){failure=unavailable;}catch(OutOfMemoryError memory){failure=new IOException("图片内存不足，请选择尺寸较小的图片");}
            final Bitmap bitmap=decoded;final Exception error=failure;
            activity.runOnUiThread(()->{
                boolean accepted=owner==avatarGeneration&&alive()&&avatarDialog==progress&&progress.isShowing()&&!progress.isClosing();
                if(!accepted){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();return;}
                avatarDecode=null;avatarDialog=null;progress.dismiss();
                if(error!=null||!api.current()){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();clearAvatarPreview(false);error(error==null?new IllegalArgumentException("登录状态已变化，请重新选择头像"):error);return;}
                showAvatarCrop(api,bitmap);
            });return null;
        });
        try{executor.execute(avatarDecode);}catch(RuntimeException unavailable){clearAvatarPreview(true);error(new IOException("图片准备未能开始，请重试"));}
    }
    private void ownAvatarDialog(NativeGlassDialog dialog){avatarDialog=dialog;dialog.setOnDismissListener(d->{dialogs.remove(dialog);if(avatarDialog==dialog)clearAvatarPreview(false);});}
    private void clearAvatarPreview(boolean dismiss){
        ++avatarGeneration;avatarUri=null;avatarApi=null;avatarTransform=null;
        FutureTask<?> task=avatarDecode;avatarDecode=null;if(task!=null)task.cancel(true);
        InputStream input;synchronized(avatarReadLock){input=avatarInput;avatarInput=null;}if(input!=null)try{input.close();}catch(IOException ignored){}
        NativeGlassDialog dialog=avatarDialog;avatarDialog=null;NativeAvatarCropView crop=avatarCrop;avatarCrop=null;
        if(dismiss&&dialog!=null)dialog.dismiss();if(crop!=null)crop.releaseImage();
    }
    private void showAvatarCrop(NativeUserActions api,Bitmap bitmap){
        NativeAvatarCropView crop=new NativeAvatarCropView(activity,bitmap);avatarCrop=crop;crop.restoreTransform(avatarTransform);LinearLayout content=column();content.addView(crop,new LinearLayout.LayoutParams(-1,dp(300)));
        TextView hint=new TextView(activity);hint.setText("拖动调整位置，双指缩放。将替换当前全部头像。");hint.setTextColor(AppPalette.of(dark).muted);hint.setPadding(0,dp(10),0,0);content.addView(hint);
        NativeGlassDialog dialog=show(builder("裁剪头像").setView(content).setNegativeButton("取消",null).setPositiveButton("确认上传并替换头像",null));ownAvatarDialog(dialog);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v->{
            if(!acceptsWrite(dialog)||avatarDialog!=dialog||avatarCrop!=crop)return;
            try{api.requireCurrent();Bitmap selected=crop.crop();
                boolean queued=write(dialog,()->{try{return new NativeAvatarUpload(api).upload(selected);}finally{selected.recycle();}},result->outcome(result,null));
                if(queued){avatarUri=null;avatarApi=null;avatarTransform=null;}else selected.recycle();
            }catch(Exception failure){clearAvatarPreview(true);error(failure);}
        });
    }
    private void requireAvatarRead(int owner)throws IOException{if(closed||owner!=avatarGeneration||Thread.currentThread().isInterrupted())throw new IOException("头像预览已取消");}
    private Bitmap decode(Uri uri,int owner) throws IOException {byte[] bytes;requireAvatarRead(owner);InputStream opened=activity.getContentResolver().openInputStream(uri);if(opened==null)throw new IOException("图片无法打开");
        try(InputStream input=opened;ByteArrayOutputStream output=new ByteArrayOutputStream()){
            synchronized(avatarReadLock){requireAvatarRead(owner);avatarInput=input;}
            byte[] buffer=new byte[8192];for(int n;(n=input.read(buffer))!=-1;){requireAvatarRead(owner);if(output.size()+n>15*1024*1024)throw new IOException("图片不能超过 15 MB");output.write(buffer,0,n);}requireAvatarRead(owner);bytes=output.toByteArray();
        }finally{synchronized(avatarReadLock){if(avatarInput==opened)avatarInput=null;}}
        requireAvatarRead(owner);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>40000||bounds.outHeight>40000)throw new IOException("不支持的图片格式或尺寸");BitmapFactory.Options options=new BitmapFactory.Options();int sample=1;while(bounds.outWidth/sample>2048||bounds.outHeight/sample>2048)sample*=2;options.inSampleSize=sample;Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(bitmap==null)throw new IOException("图片解码失败");
        try{
            try{int orientation=new ExifInterface(new ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);Matrix matrix=new Matrix();switch(orientation){case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:matrix.setScale(-1,1);break;case ExifInterface.ORIENTATION_ROTATE_180:matrix.setRotate(180);break;case ExifInterface.ORIENTATION_FLIP_VERTICAL:matrix.setScale(1,-1);break;case ExifInterface.ORIENTATION_TRANSPOSE:matrix.setRotate(90);matrix.postScale(-1,1);break;case ExifInterface.ORIENTATION_ROTATE_90:matrix.setRotate(90);break;case ExifInterface.ORIENTATION_TRANSVERSE:matrix.setRotate(270);matrix.postScale(-1,1);break;case ExifInterface.ORIENTATION_ROTATE_270:matrix.setRotate(270);break;default:break;}if(!matrix.isIdentity()){Bitmap oriented=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);if(oriented!=bitmap){bitmap.recycle();bitmap=oriented;}}}catch(IOException unsupportedExif){/* PNG and other valid images may have no EXIF metadata. */}
            requireAvatarRead(owner);return bitmap;
        }catch(IOException|RuntimeException|Error failure){if(!bitmap.isRecycled())bitmap.recycle();throw failure;}
    }
    private LinearLayout column(){LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private EditText input(String hint,String value,boolean multiline){EditText input=new EditText(activity);input.setHint(hint);input.setText(value);input.setTextSize(16);input.setSingleLine(!multiline);input.setInputType(InputType.TYPE_CLASS_TEXT|(multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));input.setGravity(Gravity.TOP|Gravity.START);if(multiline)input.setMinLines(5);return input;}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
}
