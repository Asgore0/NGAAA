import cn.ngapure.app.NgaApi;
import cn.ngapure.app.ForumRoute;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;

/** Bundled official catalog and synthetic protocol boundaries; no Android Context/network. */
public final class ForumCatalogTest {
    private static int checks;
    private static void ok(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
    @SuppressWarnings("unchecked") private static List<NgaApi.Forum> parse(JSONObject data)throws Exception{return (List<NgaApi.Forum>)ParserSmokeTest.call("parseForums",new Class<?>[]{JSONObject.class},data);}
    private static NgaApi.Forum entry(List<NgaApi.Forum> rows,String key){for(NgaApi.Forum forum:rows)if(forum.key.equals(key))return forum;throw new AssertionError("Missing canonical key "+key);}
    private static JSONObject catalog(JSONArray entries)throws Exception{return new JSONObject().put("data",new JSONArray().put(new JSONObject().put("name","分类").put("groups",new JSONArray().put(new JSONObject().put("name","分组").put("forums",entries)))));}
    private static String repeat(String value,int count){StringBuilder result=new StringBuilder();for(int i=0;i<count;i++)result.append(value);return result.toString();}
    private static void realCatalog(JSONObject source,List<NgaApi.Forum> rows)throws Exception{
        int rawCount=0,rawCollections=0;LinkedHashSet<String> expected=new LinkedHashSet<>();
        JSONArray categories=source.getJSONArray("data");
        for(int i=0;i<categories.length();i++)for(Object groupValue:categories.getJSONObject(i).getJSONArray("groups")){
            JSONArray forums=((JSONObject)groupValue).getJSONArray("forums");
            for(Object value:forums){JSONObject row=(JSONObject)value;rawCount++;boolean collection=row.has("stid");if(collection)rawCollections++;
                String id=row.optString(collection?"stid":"fid","");
                if(collection?ForumRoute.isCollectionId(id):ForumRoute.isForumId(id))expected.add(collection?"stid:"+id:id);
            }
        }
        ok(rawCount==674&&rawCollections==287,"real snapshot inventory remains 674 entries / 287 declared collection entries");
        ok(rows.size()==658&&expected.size()==658&&rawCount-rows.size()==16,"canonical de-duplication retains 658 destinations / only 16 true repeats");
        List<String> actual=new ArrayList<>();int boards=0,collections=0;Set<String> seen=new HashSet<>();
        for(NgaApi.Forum forum:rows){actual.add(forum.key);ok(seen.add(forum.key),"no repeated canonical route");ForumRoute route=ForumRoute.fromKey(forum.key).withParent(forum.parentFid);
            ok(route.fid.equals(forum.fid)&&route.stid.equals(forum.stid),"canonical fields agree with native navigation");
            ok(!forum.name.isEmpty()&&!forum.category.isEmpty()&&forum.categoryNames.contains(forum.category),"display and category membership survive");
            ok(forum.searchAliases.contains(forum.name)&&forum.searchAliases.length()<=4096,"directory search metadata retains name within limit");
            if(forum.stid.isEmpty())boards++;else collections++;
        }
        ok(boards==372&&collections==286,"actual ordinary routes and unique collection routes remain separate");
        ok(actual.equals(new ArrayList<>(expected)),"first appearance order is stable across canonical de-duplication");
        NgaApi.Forum study=entry(rows,"stid:39827852"),parent=entry(rows,"-7");
        ok(study.name.equals("考研讨论")&&study.fid.isEmpty()&&study.stid.equals("39827852")&&study.parentFid.equals("-7"),"study collection does not become its parent forum");
        ok(parent.name.equals("网事杂谈")&&!parent.aliases.contains("考研讨论"),"parent and child collection names do not pollute each other");
        NgaApi.Forum recommendation=entry(rows,"stid:29182350");ok(recommendation.name.equals("评测/安利")&&recommendation.parentFid.isEmpty(),"collection without parent fid is still discoverable");
        NgaApi.Forum mobile=entry(rows,"stid:47494660");ok(mobile.name.equals("洛奇Mobile")&&mobile.parentFid.equals("863")&&entry(rows,"863").name.equals("手机游戏快讯"),"parent board keeps its later true directory entry instead of the first collection name");
        ok(rows.stream().noneMatch(f->f.key.equals("428")),"collection-only parent fid is not invented as a directory board bearing its first collection name");
        NgaApi.Forum toys=entry(rows,"716");ok(toys.name.equals("模型手办")&&toys.aliases.contains("NGATOYS")&&toys.groupNames.equals(Arrays.asList("网事杂谈","二次元综合")),"duplicate board merges alternate names and all group memberships");
        ok(toys.searchAliases.contains("NGATOYS")&&toys.searchAliases.contains("动漫周边"),"alternate name and official description remain searchable");
        ok(entry(rows,"-34587507").searchAliases.contains("Arknights"),"official English description remains searchable");
        try{toys.aliases.add("mutation");throw new AssertionError("Aliases must be immutable snapshots");}catch(UnsupportedOperationException expectedFailure){checks++;}
        try{toys.categoryNames.clear();throw new AssertionError("Categories must be immutable snapshots");}catch(UnsupportedOperationException expectedFailure){checks++;}
    }
    private static void protocolBoundaries()throws Exception{
        JSONArray first=new JSONArray().put(new JSONObject().put("fid",-7).put("name","父版块"))
                .put(new JSONObject().put("fid",-7).put("stid",500).put("name","合集 A").put("nameS","Short A").put("info","说明 A"))
                .put(new JSONObject().put("stid",600).put("name","无父合集"))
                .put(new JSONObject().put("fid",500).put("id",999).put("name","编号相同的普通版块"));
        JSONObject data=catalog(first);data.getJSONArray("data").put(new JSONObject().put("name","另一个分类").put("groups",new JSONArray().put(new JSONObject().put("name","另一个分组").put("forums",new JSONArray().put(new JSONObject().put("fid",-7).put("stid",500).put("name","合集别名").put("infoS","补充说明"))))));
        List<NgaApi.Forum> rows=parse(data);ok(rows.size()==4,"fid and stid namespaces never collide, duplicate collection merges");
        NgaApi.Forum collection=entry(rows,"stid:500");
        ok(collection.fid.isEmpty()&&collection.parentFid.equals("-7")&&collection.stid.equals("500"),"collection route has separate parent metadata");
        ok(collection.aliases.equals(Arrays.asList("Short A","合集别名"))&&collection.categoryNames.equals(Arrays.asList("分类","另一个分类"))&&collection.groupNames.equals(Arrays.asList("分组","另一个分组")),"duplicates merge bounded alternate metadata in stable order");
        ok(collection.searchAliases.contains("补充说明")&&collection.description.equals("说明 A")&&collection.category.equals("分类")&&collection.group.equals("分组"),"primary display metadata stays stable while search includes later descriptions");
        ok(entry(rows,"500").fid.equals("500")&&!rows.stream().anyMatch(f->f.key.equals("999")),"id display field cannot override the explicit fid/stid destination");
        for(Object invalid:new Object[]{"0","-0","01","-01","+7"," 7","7 ","７","1234567890123","7&tid=8",JSONObject.NULL,true,new JSONArray().put(7),7.5})
            ok(parse(catalog(new JSONArray().put(new JSONObject().put("fid",invalid).put("name","无效普通版块")))).isEmpty(),"invalid ordinary ID never produces a native route");
        for(Object invalid:new Object[]{"","0","-1","01","+7"," 7","7 ","７","1234567890123456789","7&fid=8",JSONObject.NULL,true,new JSONArray().put(7),7.5})
            ok(parse(catalog(new JSONArray().put(new JSONObject().put("fid",7).put("stid",invalid).put("name","无效合集")))).isEmpty(),"invalid declared stid never falls back to its valid parent fid");
        for(String valid:new String[]{"1","-1","999999999999","-999999999999"})ok(parse(catalog(new JSONArray().put(new JSONObject().put("fid",valid).put("name","合法普通版块")))).get(0).key.equals(valid),"valid ordinary ID boundary");
        for(Object valid:new Object[]{"1","999999999999999999",Long.valueOf(999999999999999999L)})ok(parse(catalog(new JSONArray().put(new JSONObject().put("stid",valid).put("name","合法合集")))).get(0).key.equals("stid:"+valid),"valid collection ID boundary");
        NgaApi.Forum invalidParent=parse(catalog(new JSONArray().put(new JSONObject().put("fid","0&tid=1").put("stid",7).put("name","合法合集")))).get(0);
        ok(invalidParent.key.equals("stid:7")&&invalidParent.parentFid.isEmpty(),"invalid optional parent is omitted without losing a valid collection");
        ok(parse(new JSONObject()).isEmpty()&&parse(new JSONObject().put("data","invalid")).isEmpty(),"missing/wrong catalog containers are empty");
        ok(parse(new JSONObject().put("data",new JSONArray().put(JSONObject.NULL).put(new JSONObject().put("groups",new JSONArray().put(JSONObject.NULL))))).isEmpty(),"null category/group members are ignored");
    }
    private static void metadataBounds()throws Exception{
        JSONArray entries=new JSONArray();
        for(int i=0;i<60;i++)entries.put(new JSONObject().put("fid",7).put("name","Alias "+i).put("nameS","Short "+i).put("info",repeat("说明",300)+i));
        NgaApi.Forum bounded=parse(catalog(entries)).get(0);
        ok(bounded.aliases.size()<=16&&bounded.searchAliases.length()<=4096&&bounded.description.length()<=512,"many aliases/descriptions stay bounded");
        ok(bounded.name.equals("Alias 0")&&bounded.searchAliases.contains("Alias 0"),"quota does not change first display name or its search token");
        JSONObject data=new JSONObject().put("data",new JSONArray());
        for(int i=0;i<45;i++)data.getJSONArray("data").put(new JSONObject().put("name","Category "+i).put("groups",new JSONArray().put(new JSONObject().put("name","Group "+i).put("forums",new JSONArray().put(new JSONObject().put("fid",7).put("name","Stable"))))));
        NgaApi.Forum groups=parse(data).get(0);ok(groups.categoryNames.size()==16&&groups.groupNames.size()==32,"membership lists are bounded independent of duplicates");
        NgaApi.Forum text=parse(catalog(new JSONArray().put(new JSONObject().put("fid",7).put("name",repeat("x",159)+"😀").put("info",repeat("y",511)+"😀")))).get(0);
        ok(text.name.length()==159&&text.description.length()==511&&!Character.isHighSurrogate(text.name.charAt(text.name.length()-1)),"bounded metadata never cuts a surrogate pair");
        NgaApi.Forum clean=parse(catalog(new JSONArray().put(new JSONObject().put("fid",7).put("name","Name\n\u0000End").put("info","Info\tEnd")))).get(0);
        ok(!clean.name.matches("(?s).*[\\x00-\\x1f\\x7f].*")&&!clean.description.matches("(?s).*[\\x00-\\x1f\\x7f].*"),"display fields do not retain ASCII controls");
    }
    /** Frozen 0.20.8 output, including every public field and the order within every list. */
    private static void completePublicOutput(List<NgaApi.Forum> rows)throws Exception{
        Field[] fields=NgaApi.Forum.class.getFields();Arrays.sort(fields,Comparator.comparing(Field::getName));
        JSONArray names=new JSONArray(),values=new JSONArray();for(Field field:fields)names.put(field.getName());
        for(NgaApi.Forum row:rows){JSONArray cells=new JSONArray();for(Field field:fields){
            Object value=field.get(row);
            if(value instanceof String)cells.put(value);
            else if(value instanceof List)cells.put(new JSONArray((List<?>)value));
            else throw new AssertionError("New public catalog field needs an explicit equivalence check");
        }values.put(cells);}
        byte[] canonical=new JSONArray().put(names).put(values).toString().getBytes(StandardCharsets.UTF_8);
        StringBuilder digest=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(canonical))digest.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        ok(fields.length==13&&canonical.length==141277,"complete catalog snapshot covers 13 public fields across all 658 destinations");
        ok(digest.toString().equals("6904dd0f397d5ca3cc6140da96bf5916fbdbe4fb1ffac19811eb12f8bbc89c9c"),"all public catalog output is byte-equivalent to frozen 0.20.8 with the same Html fixture");
    }
    private static void boundedHtmlWork(JSONObject source)throws Exception{
        android.text.Html.resetCallCounts();List<NgaApi.Forum> rows=parse(source);
        ok(rows.size()==658&&android.text.Html.calls==201,"public catalog parses 201 normalization-sensitive values and bypasses 788 literal labels");
        ok(android.text.Html.emptyCalls==0,"empty catalog values never invoke the HTML parser");
        JSONArray entries=new JSONArray().put(new JSONObject().put("fid",7).put("name","<b>Name</b>").put("info","<i>Primary</i>").put("infoS","Short &amp; description").put("infoL","Long\ttext"))
                .put(new JSONObject().put("fid",8).put("name","Second").put("info","").put("infoS","<b>Short fallback</b>").put("infoL","Long fallback"))
                .put(new JSONObject().put("fid",9).put("name","Third").put("info","").put("infoS","").put("infoL","<b>Long only</b>"));
        JSONObject fixture=catalog(entries);fixture.getJSONArray("data").getJSONObject(0).put("name","<b>Category</b>").getJSONArray("groups").getJSONObject(0).put("name","<i>Group</i>");
        android.text.Html.resetCallCounts();rows=parse(fixture);
        ok(android.text.Html.calls==9&&android.text.Html.emptyCalls==0,"HTML, whitespace and control values keep their parser path; two literal names bypass it");
        ok(rows.get(0).category.equals("Category")&&rows.get(0).group.equals("Group"),"hierarchy HTML normalization unchanged");
        ok(rows.get(0).description.equals("Primary")&&rows.get(0).searchAliases.contains("Short & description")&&rows.get(0).searchAliases.contains("Long text"),"primary description wins while both other descriptions remain searchable");
        ok(rows.get(1).description.equals("Short fallback")&&rows.get(1).searchAliases.contains("Long fallback")&&rows.get(2).description.equals("Long only"),"description fallback order remains info, infoS, infoL");
        android.text.Html.resetCallCounts();
        ok("".equals(ParserSmokeTest.call("catalogText",new Class<?>[]{String.class,int.class},null,80))
                &&"".equals(ParserSmokeTest.call("catalogText",new Class<?>[]{String.class,int.class},"",80))
                &&android.text.Html.calls==0,"null/empty catalog text keeps original output without parsing");
    }
    public static int verify(JSONObject source,List<NgaApi.Forum> forums)throws Exception{
        realCatalog(source,forums);protocolBoundaries();metadataBounds();completePublicOutput(forums);boundedHtmlWork(source);
        System.out.println("Forum catalog: 674 official entries -> 658 canonical destinations (372 boards / 286 collections); "+checks+" offline checks passed");
        return checks;
    }
}
