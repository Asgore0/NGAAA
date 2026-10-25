package cn.ngapure.app;

/** Bounded navigation geometry only. A changed column width keeps the same floor in view. */
final class ReaderAnchor {
    final String tid;
    final int page,floor,fraction,columnWidth,scrollY;
    private ReaderAnchor(String tid,int page,int floor,int fraction,int columnWidth,int scrollY){
        this.tid=tid;this.page=page;this.floor=floor;this.fraction=fraction;this.columnWidth=columnWidth;this.scrollY=scrollY;
    }
    static ReaderAnchor create(String tid,int page,int floor,int fraction,int columnWidth,int scrollY){
        if(tid==null||!tid.matches("[1-9][0-9]{0,17}")||page<1||page>99999||floor<0||floor>2000000||fraction<0||fraction>10000||columnWidth<1||columnWidth>100000||scrollY<0||scrollY>NavigationState.MAX_SCROLL)return null;
        return new ReaderAnchor(tid,page,floor,fraction,columnWidth,scrollY);
    }
    boolean matches(String id,int currentPage){return tid.equals(id)&&page==currentPage;}
    int targetY(int top,int height){return NavigationState.offset((int)Math.min(NavigationState.MAX_SCROLL,(long)Math.max(0,top)+(long)Math.max(0,height)*fraction/10000));}
    String[] encode(){return new String[]{tid,Integer.toString(page),Integer.toString(floor),Integer.toString(fraction),Integer.toString(columnWidth),Integer.toString(scrollY)};}
    static ReaderAnchor decode(Object raw){
        if(!(raw instanceof String[])||((String[])raw).length!=6)return null;
        String[] values=(String[])raw;
        try{return create(values[0],Integer.parseInt(values[1]),Integer.parseInt(values[2]),Integer.parseInt(values[3]),Integer.parseInt(values[4]),Integer.parseInt(values[5]));}catch(RuntimeException malformed){return null;}
    }
}
