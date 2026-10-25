package cn.ngapure.app;

import android.content.Context;
import android.view.View;
import android.widget.ListView;
import java.util.ArrayList;
import java.util.List;

/** A viewport-sized native list; every topic is in the adapter before its first layout. */
final class ForumTopicListView extends ListView {
    private final String scope,route,parent;
    private final int page;
    private final List<String> topicIds;
    ForumTopicListView(Context context,String scope,String route,String parent,int page,List<String> ids){
        super(context);this.scope=scope;this.route=route;this.parent=parent;this.page=page;topicIds=new ArrayList<>(ids);
        setTag("forum-topic-list");setSaveEnabled(false);setDivider(null);setDividerHeight(0);setVerticalScrollBarEnabled(false);
        setItemsCanFocus(true);setClipToPadding(false);setPadding(0,0,0,Math.round(20*getResources().getDisplayMetrics().density));
        setSelector(android.R.color.transparent);
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int limit=Math.round(720*getResources().getDisplayMetrics().density),gutter=Math.max(0,(View.MeasureSpec.getSize(widthSpec)-limit)/2);
        if(getPaddingLeft()!=gutter||getPaddingRight()!=gutter)setPadding(gutter,0,gutter,getPaddingBottom());
        super.onMeasure(widthSpec,heightSpec);
    }
    ForumListAnchor anchor(){
        View first=getChildAt(0);if(first==null)return null;int index=getFirstVisiblePosition()-getHeaderViewsCount();
        if(index>=topicIds.size())return null;
        return ForumListAnchor.create(scope,route,parent,page,index<0?"":topicIds.get(index),Math.max(-1,index),first.getTop()-getPaddingTop());
    }
    void restore(ForumListAnchor anchor){
        if(anchor!=null&&anchor.matches(scope,route,parent,page)&&getAdapter()!=null&&getAdapter().getCount()>0)setSelectionFromTop(Math.min(getAdapter().getCount()-1,anchor.position(topicIds,getHeaderViewsCount())),anchor.top);
    }
}
