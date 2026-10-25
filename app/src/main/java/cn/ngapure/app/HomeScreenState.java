package cn.ngapure.app;

/** Small, bounded state for home tabs; restored before their navigation keys are reconstructed. */
final class HomeScreenState {
    static final int QUERY_LIMIT=256,MAX_POSITION=100000;
    private static final int CATEGORY_LIMIT=80,COLUMNS=6;
    final String ratingCategory,boardCategory,boardQuery;
    final int ratingPage,boardPosition,boardOffset;
    private HomeScreenState(String ratingCategory,int ratingPage,String boardCategory,String boardQuery,int boardPosition,int boardOffset){
        this.ratingCategory=ratingCategory;this.ratingPage=ratingPage;this.boardCategory=boardCategory;this.boardQuery=boardQuery;this.boardPosition=boardPosition;this.boardOffset=boardOffset;
    }
    static HomeScreenState defaults(){return new HomeScreenState("2",1,"收藏版块","",0,0);}
    static HomeScreenState capture(String ratingCategory,int ratingPage,String boardCategory,String boardQuery,int boardPosition,int boardOffset){
        return new HomeScreenState(ratingId(ratingCategory)?ratingCategory:"2",Math.max(1,Math.min(NavigationState.MAX_PAGE,ratingPage)),
                safeText(boardCategory,CATEGORY_LIMIT,"收藏版块"),safeText(boardQuery,QUERY_LIMIT,""),Math.max(0,Math.min(MAX_POSITION,boardPosition)),
                Math.max(-NavigationState.MAX_SCROLL,Math.min(NavigationState.MAX_SCROLL,boardOffset)));
    }
    String[] encode(){return new String[]{ratingCategory,Integer.toString(ratingPage),boardCategory,boardQuery,Integer.toString(boardPosition),Integer.toString(boardOffset)};}
    static HomeScreenState decode(Object raw){
        if(!(raw instanceof String[])||((String[])raw).length!=COLUMNS)return defaults();String[] values=(String[])raw;
        try{
            int page=Integer.parseInt(values[1]),position=Integer.parseInt(values[4]),offset=Integer.parseInt(values[5]);
            if(!ratingId(values[0])||page<1||page>NavigationState.MAX_PAGE||!text(values[2],CATEGORY_LIMIT)||values[2].isEmpty()||!text(values[3],QUERY_LIMIT)||position<0||position>MAX_POSITION||offset< -NavigationState.MAX_SCROLL||offset>NavigationState.MAX_SCROLL)return defaults();
            return new HomeScreenState(values[0],page,values[2],values[3],position,offset);
        }catch(RuntimeException malformed){return defaults();}
    }
    private static boolean ratingId(String value){return "2".equals(value)||"4".equals(value)||"8".equals(value);}
    private static boolean text(String value,int limit){return value!=null&&value.length()<=limit&&!value.matches("(?s).*[\\x00-\\x1f\\x7f].*");}
    private static String safeText(String value,int limit,String fallback){
        if(value==null||value.isEmpty())return fallback;String clean=value.replaceAll("[\\x00-\\x1f\\x7f]"," ");return clean.length()>limit?clean.substring(0,limit):clean;
    }
}
