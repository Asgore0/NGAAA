package cn.ngapure.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure discovery checks: no Android, persistence, device, account or network. */
public final class BoardDirectoryTest {
    private static int checks;
    private static void ok(boolean value,String description){checks++;if(!value)throw new AssertionError(description);}
    private static String[] old(String key,String name,String category,String alias){return new String[]{key,name,category,alias};}
    private static String[] row(String key,String name,String category,String alias,String parent,String aliases,String categories){return new String[]{key,name,category,alias,parent,aliases,categories};}
    private static List<String[]> rows(String[]... values){return Arrays.asList(values);}
    private static Set<String> favorites(String... keys){return new HashSet<>(Arrays.asList(keys));}
    private static List<String> ids(List<String[]> entries){List<String> keys=new ArrayList<>();for(String[] row:entries)keys.add(row[0]);return keys;}
    private static void idsAre(List<String[]> actual,String... expected){ok(ids(actual).equals(Arrays.asList(expected)),"ordered result "+Arrays.toString(expected)+" was "+ids(actual));}
    private static List<String[]> filter(List<String[]> source,String query){return BoardDirectory.filter(source,query,null,null);}
    private static void rejectsMutation(Runnable action,String description){try{action.run();throw new AssertionError(description);}catch(UnsupportedOperationException expected){checks++;}}

    private static void searchNormalizationAndTokens(){
        List<String[]> source=rows(
                row("650","原神 综合 攻略","手机游戏","原神","","Genshin Impact RPG","手机游戏\n角色扮演\nGames"),
                old("7","魔兽世界","网络游戏","WoW"),
                old("8","原神周边","玩家生活",""),
                old("9","独立游戏","Games","INDIE"));
        idsAre(filter(source,"  ＧＥＮＳＨＩＮ\tＲＰＧ\u00a0"),"650");
        idsAre(filter(source,"原神\n\t攻略"),"650");
        idsAre(filter(source,"攻略 原神"),"650");
        idsAre(filter(source,"原神\u3000手机"),"650");
        idsAre(filter(source,"原神 手机 不存在"));
        idsAre(filter(source,"ｗｏｗ"),"7");
        idsAre(filter(source,"角色扮演 RPG"),"650");
        idsAre(filter(source,"650"),"650");
        idsAre(filter(source,"６５０"),"650");
        idsAre(BoardDirectory.filter(source,"", "角色扮演",null),"650");
        idsAre(BoardDirectory.filter(source,"", "ｇａｍｅｓ",null),"650","9");
        idsAre(BoardDirectory.filter(source,"", "手机",null));
        idsAre(BoardDirectory.filter(source,"", "  ",null),"650","7","8","9");
        idsAre(BoardDirectory.filter(source,"", "全部版块",null),"650","7","8","9");
        idsAre(BoardDirectory.filter(source,null,null,favorites("9")),"650","7","8","9");
        idsAre(BoardDirectory.filter(source," \n\t\u3000 ",null,favorites("9","7")),"650","7","8","9");
        Locale previous=Locale.getDefault();
        try{Locale.setDefault(new Locale("tr","TR"));idsAre(filter(source,"indie"),"9");idsAre(filter(source,"GENSHIN IMPACT"),"650");}
        finally{Locale.setDefault(previous);}
        List<String[]> cross=rows(old("21","原神","手机游戏",""),old("22","手机原神","其他",""));
        idsAre(BoardDirectory.filter(cross,"原神 手机",null,favorites("21")),"22","21");
        idsAre(filter(source,"原神 原神"),"650","8");
        idsAre(filter(source,"\u00a0\u2003\u202f原神\u2009攻略\u00a0"),"650");
        idsAre(filter(rows(old("51","Café 游戏","国际","")),"Ｃａｆｅ\u0301"),"51");
        idsAre(filter(rows(old("52","游戏 🚀 讨论","兴趣","")),"🚀\u2003游戏"),"52");
        List<String[]> sourceWidth=rows(row("53","ＡＢＣ 专区","ＧＡＭＥＳ","","","Ｆｏｏ　Ｂａｒ","ＧＡＭＥＳ\nＲＰＧ"));
        idsAre(BoardDirectory.filter(sourceWidth,"abc foo", "games",null),"53");
        idsAre(BoardDirectory.filter(sourceWidth,"bar", "rpg",null),"53");
    }

    private static void rankingAndStableFavorites(){
        List<String[]> source=rows(old("6","分类入口","123 讨论",""),old("5","别名入口","其他","123"),
                old("4","综合123讨论","其他",""),old("3","123专区","其他",""),old("2","123","其他",""),old("123","标识入口","其他",""));
        idsAre(BoardDirectory.filter(source,"123",null,favorites("6","5","4","3","2")),"123","2","3","4","5","6");
        idsAre(BoardDirectory.filter(source,"",null,favorites("123","2")),"6","5","4","3","2","123");
        List<String[]> ties=rows(old("10","原神讨论","手机游戏",""),old("9","原神攻略","手机游戏",""),old("11","原神资料","手机游戏",""));
        idsAre(BoardDirectory.filter(ties,"原神",null,favorites("9")),"9","10","11");
        idsAre(BoardDirectory.filter(ties,"原神",null,Collections.emptySet()),"10","9","11");
        idsAre(BoardDirectory.filter(ties,"原神",null,favorites("9999999")),"10","9","11");
        idsAre(BoardDirectory.filter(ties,"",null,favorites("9")),"10","9","11");
        List<String[]> exact=rows(old("31","原神资料","手机游戏",""),old("32","原神","手机游戏",""));
        idsAre(BoardDirectory.filter(exact,"原神",null,favorites("31")),"32","31");
        List<String[]> collections=rows(old("stid:123","主题合集","合集",""),old("123","普通版块","版块",""));
        idsAre(filter(collections,"ＳＴＩＤ：１２３"),"stid:123");
        idsAre(filter(collections,"123"),"stid:123","123");
        idsAre(BoardDirectory.filter(collections,"123",null,favorites("123")),"123","stid:123");
        idsAre(filter(rows(row("stid:900","子合集","合集","","650","","合集")),"650"));
        idsAre(BoardDirectory.filter(ties,"", "收藏版块",favorites("9")));
    }

    private static void malformedRowsAndDuplicateMembership(){
        List<String[]> source=rows(null,new String[0],new String[]{"1","太短","分类"},old(null,"名字","分类",""),
                old("0","零编号","分类",""),old("01","非规范编号","分类",""),old("-0","负零","分类",""),
                old("stid:0","无效合集","分类",""),old("STID:2","非规范大写前缀","分类",""),old("stid:02","非规范合集","分类",""),
                old("1"," ","分类",""),old("2",null,"分类",""),old(" 3","前置空格ID","分类",""),
                old("-7","旧四列正常版块","玩家生活",null),row("stid:888","合集首名","手机游戏","原","650","First Alias","手机游戏\n角色扮演"),
                row("stid:888","合集另名","网络游戏","合集缩写","7","Second Alias","网络游戏\nＲＰＧ\n手机游戏"),
                old("888","普通888版块","普通分类",""),row("9","缺省分类",null,null,"not-a-parent",null,null));
        List<String[]> filtered=filter(source,"");idsAre(filtered,"-7","stid:888","888","9");
        String[] merged=filtered.get(1);
        ok(merged.length==7&&merged[1].equals("合集首名")&&merged[2].equals("手机游戏"),"first valid display identity and primary category retained");
        ok(merged[4].equals("650"),"duplicate does not silently change an existing valid parent");
        ok(merged[6].equals("手机游戏\n角色扮演\n网络游戏\nＲＰＧ"),"all first-seen category memberships merged without duplicate labels");
        idsAre(filter(source,"Second Alias"),"stid:888");
        idsAre(filter(source,"合集另名"),"stid:888");
        idsAre(filter(source,"合集缩写"),"stid:888");
        idsAre(BoardDirectory.filter(source,"", "网络游戏",null),"stid:888");
        idsAre(BoardDirectory.filter(source,"", "rpg",null),"stid:888");
        ok(filtered.get(0)[3].isEmpty()&&filtered.get(0)[4].isEmpty(),"legacy four-column row receives empty optional slots");
        ok(filtered.get(3)[2].equals("其他")&&filtered.get(3)[4].isEmpty(),"missing primary category has display fallback and invalid optional parent is ignored");
        idsAre(filter(null,"anything"));idsAre(filter(Collections.emptyList(),""));
        List<String[]> alternateParent=rows(old("stid:42","合集","主题",""),row("stid:42","合集","主题","","-7","","主题"));
        ok(filter(alternateParent,"").get(0)[4].equals("-7"),"later duplicate can supply a previously missing valid parent");
        String maxFid="999999999999",maxStid="999999999999999999";
        idsAre(filter(rows(old(maxFid,"最大版块","分类",""),old("stid:"+maxStid,"最大合集","分类",""),old(maxFid+"9","越界版块","分类",""),old("stid:"+maxStid+"9","越界合集","分类","")),""),maxFid,"stid:"+maxStid);
    }

    private static void measuredColumns(){
        ok(BoardDirectory.columns(339,1,1,true)==1,"339 content dp is one column");
        ok(BoardDirectory.columns(340,1,1,true)==2,"340 content dp is two columns without subtracting gutters twice");
        ok(BoardDirectory.columns(659,1,1,true)==2,"659 content dp is two columns");
        ok(BoardDirectory.columns(660,1,1,true)==3,"660 content dp is three columns");
        ok(BoardDirectory.columns(720,2,1,true)==2,"physical density converts measured content width");
        ok(BoardDirectory.columns(893,2.625f,1,true)==2&&BoardDirectory.columns(892,2.625f,1,true)==1,"fractional density threshold does not round up a too-narrow pane");
        ok(BoardDirectory.columns(441,1,1.3f,true)==1&&BoardDirectory.columns(442,1,1.3f,true)==2,"large text reduces columns at its actual threshold");
        ok(BoardDirectory.columns(857,1,1.3f,true)==2&&BoardDirectory.columns(858,1,1.3f,true)==3,"large-text three-column threshold");
        ok(BoardDirectory.columns(600,1,2,true)==1,"very large text leaves safe single-column cells");
        ok(BoardDirectory.columns(300,1,.85f,true)==2,"smaller explicit font scale follows supplied threshold");
        for(int width:new int[]{-100,0,1,339,340,660,2000,Integer.MAX_VALUE})ok(BoardDirectory.columns(width,1,1,false)==1,"list mode always single column");
        ok(BoardDirectory.columns(-1,1,1,true)==1&&BoardDirectory.columns(0,1,1,true)==1,"nonpositive measured area cannot create columns");
        for(float invalid:new float[]{0,-1,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}){
            ok(BoardDirectory.columns(660,invalid,1,true)==3,"invalid density falls back to one");
            ok(BoardDirectory.columns(660,1,invalid,true)==3,"invalid font scale falls back to one");
        }
        ok(BoardDirectory.columns(Integer.MAX_VALUE,1,1,true)==3,"large width stays bounded to three columns");
        ok(BoardDirectory.columns(660,Float.MAX_VALUE,1,true)==1&&BoardDirectory.columns(660,1,Float.MAX_VALUE,true)==1,"very large finite scale cannot overflow to extra columns");
        int previous=0;
        for(int width=0;width<=1600;width+=17){int actual=BoardDirectory.columns(width,1,1.2f,true);ok(actual>=previous&&actual>=1&&actual<=3,"column count is bounded and monotonic with actual pane width");previous=actual;}
    }

    private static void packingAndStableKeyLocations(){
        List<String[]> source=rows(old("1","甲一","甲类",""),old("2","乙一","乙类",""),old("3","甲二","甲类",""),old("4","丙一","丙类",""),old("5","乙二","乙类",""));
        List<BoardDirectory.Row> plain=BoardDirectory.pack(source,2,false);
        ok(plain.size()==3&&!plain.get(0).isHeader()&&plain.get(0).category.isEmpty(),"ungrouped grid has tile rows without headers");
        idsAre(plain.get(0).entries,"1","2");idsAre(plain.get(1).entries,"3","4");idsAre(plain.get(2).entries,"5");
        List<BoardDirectory.Row> grouped=BoardDirectory.pack(source,2,true);
        ok(grouped.size()==6,"three first-seen categories each have a header and a tile row");
        ok(grouped.get(0).isHeader()&&grouped.get(0).category.equals("甲类")&&grouped.get(0).entries.isEmpty(),"primary group header is distinct from a tile");
        ok(grouped.get(2).isHeader()&&grouped.get(2).category.equals("乙类")&&grouped.get(4).category.equals("丙类"),"category groups preserve first-seen source order");
        idsAre(grouped.get(1).entries,"1","3");idsAre(grouped.get(3).entries,"2","5");idsAre(grouped.get(5).entries,"4");
        ok(BoardDirectory.rowForKey(grouped,"3")==1&&BoardDirectory.rowForKey(grouped,"5")==3&&BoardDirectory.rowForKey(grouped,"4")==5,"stable key lookup returns adapter row including headers");
        ok(BoardDirectory.rowForKey(plain,"4")==1,"lookup does not return the tile's column");
        ok(BoardDirectory.rowForKey(grouped,"999")==-1&&BoardDirectory.rowForKey(grouped,null)==-1&&BoardDirectory.rowForKey(null,"1")==-1,"missing key and missing grid use explicit not-found");
        ok(BoardDirectory.pack(source,0,false).size()==5&&BoardDirectory.pack(source,-1,false).size()==5,"invalid requested packing columns fall back to one");
        ok(BoardDirectory.pack(source,Integer.MAX_VALUE,false).size()==1,"oversized requested packing count does not overflow row arithmetic");
        ok(BoardDirectory.pack(null,2,true).isEmpty()&&BoardDirectory.pack(Collections.emptyList(),2,true).isEmpty(),"empty inputs create no phantom headers");
        List<String[]> duplicate=rows(old("stid:33","合集","甲类",""),old("stid:33","合集","乙类",""),old("33","普通版块","乙类",""));
        List<BoardDirectory.Row> collectionRows=BoardDirectory.pack(duplicate,2,true);
        ok(collectionRows.size()==4&&BoardDirectory.rowForKey(collectionRows,"stid:33")==1&&BoardDirectory.rowForKey(collectionRows,"33")==3,"collection duplicate appears once and never aliases same-number ordinary board");
        List<String[]> nullRows=rows(null,old("6","正常","甲类",""),new String[]{"7"});
        ok(BoardDirectory.pack(nullRows,2,true).size()==2,"malformed raw rows do not create empty tile rows");
        List<BoardDirectory.Row> nullRowList=new ArrayList<>(plain);nullRowList.add(0,null);
        ok(BoardDirectory.rowForKey(nullRowList,"1")==1,"lookup tolerates absent row while preserving adapter indices");
        for(boolean group:new boolean[]{false,true})for(int columns:new int[]{1,2,3}){
            List<BoardDirectory.Row> packed=BoardDirectory.pack(source,columns,group);
            for(String key:new String[]{"1","2","3","4","5"}){
                int index=BoardDirectory.rowForKey(packed,key);ok(index>=0&&!packed.get(index).isHeader()&&ids(packed.get(index).entries).contains(key),"stable board key survives grouping and column reflow");
            }
        }
        List<String[]> reordered=new ArrayList<>(source);Collections.reverse(reordered);
        List<BoardDirectory.Row> changed=BoardDirectory.pack(reordered,3,true);
        ok(BoardDirectory.rowForKey(changed,"3")>=0&&ids(changed.get(BoardDirectory.rowForKey(changed,"3")).entries).contains("3"),"key-based anchor survives changed catalog order");
        List<BoardDirectory.Row> removed=BoardDirectory.pack(BoardDirectory.filter(source,"乙",null,null),2,false);
        ok(BoardDirectory.rowForKey(removed,"3")==-1&&BoardDirectory.rowForKey(removed,"5")==0,"filtering missing anchor does not silently locate unrelated row");
        List<BoardDirectory.Row> equivalentCategories=BoardDirectory.pack(rows(old("61","甲","ＧＡＭＥＳ",""),old("62","乙","games","")),2,true);
        ok(equivalentCategories.size()==2&&equivalentCategories.get(0).category.equals("ＧＡＭＥＳ")&&equivalentCategories.get(1).entries.size()==2,"normalized equivalent category labels share first-display group");
        List<String[]> changedCollections=new ArrayList<>();
        for(int index=1;index<=30;index++)changedCollections.add(row("stid:"+(9000+index),"合集 "+index,index%2==0?"游戏":"生活","","650","搜索词 "+index,"游戏\n生活"));
        changedCollections.add(old("stid:9015","重复十五","第三分类",""));
        List<String[]> allCategories=BoardDirectory.filter(changedCollections,"合集", "生活",favorites("stid:9015"));
        ok(allCategories.size()==30,"duplicate collection memberships remain searchable without duplicate cards");
        Set<String> expectedKeys=new HashSet<>();for(String[] entry:allCategories)expectedKeys.add(entry[0]);
        for(int count:new int[]{1,2,3}){
            List<BoardDirectory.Row> packed=BoardDirectory.pack(allCategories,count,true);Set<String> packedKeys=new HashSet<>();int tileCount=0;
            for(BoardDirectory.Row packedRow:packed){if(packedRow.isHeader())continue;ok(packedRow.entries.size()>=1&&packedRow.entries.size()<=count,"no padding or overfull tile rows after reflow");for(String[] entry:packedRow.entries){tileCount++;packedKeys.add(entry[0]);}}
            ok(tileCount==30&&packedKeys.equals(expectedKeys),"grouped reflow neither duplicates nor loses collection identities");
            int anchored=BoardDirectory.rowForKey(packed,"stid:9015");ok(anchored>=0&&ids(packed.get(anchored).entries).contains("stid:9015"),"same collection remains locatable through all supported column counts");
        }
    }

    private static void defensiveSnapshots(){
        String[] first=row("1","原始名字","分类","原","7","别名","分类\n额外分类");
        String[] second=old("2","第二个","分类","");List<String[]> source=new ArrayList<>(rows(first,second));
        String[] before=first.clone();List<String[]> filtered=filter(source,"");
        ok(Arrays.equals(first,before),"filter does not change source row or source metadata");
        rejectsMutation(()->filtered.add(first),"filtered snapshot cannot append entries");
        rejectsMutation(()->filtered.remove(0),"filtered snapshot cannot remove entries");
        filtered.get(0)[1]="外部改写";ok(filtered.get(0)[1].equals("原始名字"),"array obtained from filtered list cannot mutate snapshot");
        List<BoardDirectory.Row> packed=BoardDirectory.pack(source,2,true);BoardDirectory.Row tile=packed.get(1);
        rejectsMutation(()->packed.clear(),"row list is immutable");
        rejectsMutation(()->tile.entries.clear(),"tile row entry list is immutable");
        rejectsMutation(()->packed.get(0).entries.add(first),"header entry list is immutable");
        tile.entries.get(0)[0]="stid:999";ok(BoardDirectory.rowForKey(packed,"1")==1&&BoardDirectory.rowForKey(packed,"stid:999")==-1,"mutating obtained tile array cannot corrupt stable-key identity");
        first[0]="3";first[1]="源被改写";source.clear();
        ok(filtered.size()==2&&filtered.get(0)[0].equals("1")&&filtered.get(0)[1].equals("原始名字"),"filtered snapshot is independent of subsequent input mutation");
        ok(tile.entries.size()==2&&tile.entries.get(0)[0].equals("1"),"packed snapshot is independent of subsequent input mutation");
    }

    public static void main(String[] args){
        searchNormalizationAndTokens();rankingAndStableFavorites();malformedRowsAndDuplicateMembership();measuredColumns();packingAndStableKeyLocations();defensiveSnapshots();
        System.out.println("BoardDirectory: "+checks+" normalization, ranking, category, grid and stable-key assertions PASS; no Android, network or device");
    }
}
