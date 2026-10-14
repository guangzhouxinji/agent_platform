import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;

/** Managed DM8 lifecycle adapter. Admin bootstrap and owner forward execution use separate connections. */
public final class SingleDatabaseLifecycleJdbc {
  static final ObjectMapper JSON=new ObjectMapper();
  // Exact source/statement SHA pins from the controlled forward-originals registry. No SQL copies.
  static final Map<String,Set<String>> FORWARD_PINS=Map.ofEntries(
    Map.entry("db764106267f411c7bfe3396fa06d935eb3bf9fc9ab00ec2d8a3f5f907481fc8",Set.of("9d533e023382090a655470b1ed59c872482fd712b03bce0584241508a088a812","6f21da798f66e2892c88c1176a916ac8e3b4fbd44ded5b4533d9c675090b26e9","7b7d7ce1aa244b2975a990fc7f09d199907051cf209266ec79f434cc15bf723c","f0ed51d05ba9a72df3a66ee5d9f59dad728c31ea17d894229942745e693b5c66","1b7f9dbec3827d9881001ebe136cc62a7c098a358b4008387a5dddf0cafb9c28","a415cd37a115bd1926c69db563e1b156ab89162b2ee013d4e3b204662d982cad","db10bd1b36ef19e6319458c78f5d0aba78bf3cf58b27bf1ce3db418c5eaac933","acc4be0163518bd28bc8033265d06002b543bceaa9c7e9a6448ae694d918f913","91484506b12c6c13645249a31c558c04f0cbb681b58430cde2c2ac572ffedd8c","4135d599c8f46faf28a82dac8b28033acac829ab8749b50a57b1ee7b790a5b17","6b63426f686d7b781d3450f44e035153662b2e23b8d30518fed382777d06e6d6","768824f9e187528895e5aa3d65740462f0722b446ad10e3aa3930b27b0e62aeb","9d9df245a59aae841cd76009963fa7708695d0ef594807915edc7be3b22ca504","8abfa20b6d54a8694aceb777eed912facee56cc247e9f22127641d7d52486872","10bc32eff2f3c924fedd4cfca3c4bc59aa155374c90adeeb98f87f4a908ad127","f5a578ff7f7e65c4acc8d774f7d1110ebe1ec39711fdb80e6c00cff05514f360")),
    Map.entry("898b60c54a539d9fd2691ed127f4f21cd5ecade8d7c0742e847f4294c0605ec6",Set.of("07bd0724ab3b909075bde750b1117d7004a970fedffc49ac11c1228018b85416","f16399faa03f1d05995bad4568a93022137c8e7ee4f0a757529c0a86297c083c")),
    Map.entry("9eba613b70ca7111f36c8b1f395d7766b288b8ce3691c203530f6b12c2622b41",Set.of("f16399faa03f1d05995bad4568a93022137c8e7ee4f0a757529c0a86297c083c","aefe94aa688a2aa1662e0aa3918f2c3c1062b72accb85dab6eca18ea1cc82d2a")),
    Map.entry("da746a24b53fb458523cef825122d643212b711c8f0163d7f744d88a7d6400d4",Set.of("74bdcbb809c0348e2a7b366bde2330a8554611bcfa5b9212ff7a545d211319c2","1978fb7558e26f3fc0a89b8b4fe2f381c9a742beae3bde8af63ec6926cf2b40d")),
    Map.entry("75c4e720e7231e2784953ce5b076b41cd6991fa28fe2721024ca3fb14b9e766f",Set.of("61ecfc27be93e8a527252cf5f7ed5a7166eef713de1d0bef49fbb34bcd0bbcd2","93c1adfee4db535c59026acb95940934f2122c466a61854ea00b02bd54702f76","ea1ad05bb5b4bc46197d625e62f23bdd7782ba62d8c157cc48fda8b2a1ee9e14","0716c60eb71ccbcc70154d4dde02572bd310d936aacc39b8de2b59a8b4e4ac41","dd4e4a4aebe0b641cf115326bc1011bebd3416542c7d672ecd4fd7dc18656d55","2c7b4115a4715dedeba8ff299f0ecb0e7fe9f0a0ec662a6f84507aa2ccf99725","6278a731be654c6da9ae904fe08a2af1ba9e43bd2e05fbcef9cc9952c9b4b0ac","1bb4959ab82b522632aa8c687efd3489efd8837b430235701011738a4a7679e8")),
    Map.entry("229eb191e70ff22788d324fb9bcec52b7b1e15cc8a536bdf2f08e7aecfce1936",Set.of("0320044028323e3fc8d317d0857ab30fe3e33ca91a8061f21affe59518daaf0c","ba181eae5628ed9f9674d873ddb3b21eb5a29a40c077fdf6f6f9e38e0db49d58","8618c04f4247a3d952fc824146fc86a2ca8108f35a5fea269d3436cc4a713767","1e5958724e8a7b73911b1e0b4030974e20c08893415c1acd7e80f778421316aa","3cf5546b018df2b2fdd5740aa839f58efd63f3ca8aa6bf9d9daabc1dbe3b5efc","384a528fd2d11a7cb5ba4666e37c8b6c7c1204110d118c879a4a3f37e9da2823","28826e791e59cc06b34e594ce6303643f4ef33270ca8a01804269392df41e926","71eaf959f9de85eea4ffd21be1c527e2abb2c3824c1e68c80ff80db43dc9fe10")),
    Map.entry("ce411a62002c785d79af657ae1f678384d44e4c2b682c7295abbe6950037c5f6",Set.of("a01a1d1ac611b41afd2e41dc55f54481a77628bd239b1d0528395bb51999118e")),
    Map.entry("51dd9fe2bcab09226700487b89c8522badee9c872363416b9e7b2a936cb2e5f6",Set.of("f16399faa03f1d05995bad4568a93022137c8e7ee4f0a757529c0a86297c083c")),
    Map.entry("7590d13862acba38b2560683bca1fbff00903be9eeac65042253178321a30a60",Set.of("66848081d9f71d9fac4bda3032fc85a035dc7399c1d9519b5b36f2e0829a1332","89a17fe88e50aa9af26d9bdd4534adc5d9bf96db69b678764a65d79c565706e2","62b4ea590005e6f956168a47b28e823f92b51d0002b0247e8b90308ebd84448b","392e10b12c5e93abcd12ae0444306136328b73a72191ee4272bbf01bf96f71b8","611d74757964a163693aa098d5db1af480f03e4b8223bc54ea4e09e21ca86d5b","5632a586fa4dfc488ea99df0dbc9784be6861a030b7f96da9efb473393a0c90f","dcd2e52950162858712968de8f1af74ae5711b2b7ad44e285db54182432075c6","375702afa961b4b9867a99d30745792916d307c4eee4fe3facdb9c5e57871b69")),
    Map.entry("65ee83d4b801d8d0e0a4ee90e72625ff767d8c0c1bd46cec7301a97d97823694",Set.of("488b37ec910aa8bbdfc2f691506f07a49be16720189d04b2349319d737a106ee","54a7103a51677194c32d2619f84231c0d5b9e9088c12d953d88694a993dea9c7","db1abd712c1ed1e6b2bb792f5e692ee9b0129cf73936dcf5b624beec7896556a","e87fc62b845eca5bbbb06b5f557256fe3a220ecfeb0f1c29e56c24f2b9d81119","6028af44bd0ac1230af63d9096d1fbc708ba96dbbaa4db5e658caea3be67af6f","7811047fbdd97b3c118538b22880ba2bcedca12ce780dc6c6c2e061a5a7ed041","b30be4c9806b9cf2118c5858f733c1171ae8d60858d42380de0c36a6c355fdbf","010fc10394df8543703c9b4cf5da0912ad050ad033a3ef77bf649cd743266e6a")),
    Map.entry("9132162985aa1c81839e2ab9b5e52fc8ebacbd6fbeb3d0ef60865e709f9aa5c9",Set.of("760660b9dc0a37aa76af2f0da601834ee7ef6cd5f8e4a0e32dc92a8376417bec","603ab5c6eb02e3eca8b4f1bba1b57d43b260dfae7114df25b698d16255e46fe5","b8877e0ab786fd72670122e32e86bcf92a6fd682aca602724c8aca87c9a219c4","e112e7276544420b73be9d816dd11fa6c3e5389a1fabdad085edb4030a9065d5","deb8cd2bbb1df8033cdb2df0ba40e78b2faa9e5eae440349e349aaf4db9d33ad","99241840940e1a7f71fec162081f03e87f2d5d36557357f0c98ef0a6f6940d03","cd9a411d58c5bcfdf31e27ad22034edb456be378a740618d34cd661c7bca1ce0","f5cc33d7ec28dcd306b98a72aed87ba9066e8f1d76c06ef56a85b69f91dc92bb")),
    Map.entry("677f1ea0d362db496ee3b6cb1e811a2ed1fe2a8af8533697bcc69c82bc108c83",Set.of("684e0b6ef96bd7079e20d31645d3e1dfb356bfde267bd6ccc099a746973e3497","5269825327e9d34ad293d9112949af0b155982ed9193dc5dc8c976ff3536d3ec","d756e9d30fa686241b818091cf05480576b20fe3035c3f2be72e908b068fa2a3","6ab72443d3edeb4a4627df1572aa09f8b588baf5e5720c45eaf3c6c1cdcf9f7c")),
    Map.entry("08e0c885c6044cd14faf931a74fb316836a2273b2126a5e6c084ef5a62f6273a",Set.of("e4a97052437a78039fb6d0ea7c5927d4db583bd8bd10803d6f9381073cabd612")),
    Map.entry("ab2ad037fbbe9f49ad6baa0de879b89def62ef161a08fa6819474957f4745893",Set.of("48a71331d8f053db5f9f47a5f3e6c0f2e9892cc0babf86f23e88a1f27deb3489","cb05ae0e72dd6a6ae9b6064934fbc8c506a80e31edca72a0dc355d3277e2759b","3258d95ed25d00cf5576923dfe39f947138c70c86fedb2fe84866be15b92ef3a","7f0c68aab066fba2703658c046bb2806a962f81e8f7fc7025e7418eab81e8e1a","1f24a9ca21f66522c1dda62a0b3a4aa53c7c565456e4e79128b715666384d1f3","ebac112a4eb9b6e70be144457db0966343a49c48b58e4733f802ebd3a0bab848","8dc88119d80da753af9a856720c977679a8dd56768fd1b29910634aa77b5989d","cae13958a9c9d017e8a9e7862d4cdaf03f3a26f5995c6114625185f0b1fac1fe")),
    Map.entry("172ccb2fe2f851d8a641b0bbfdf6c005ca202daed52ee979d2bcb607b5be6f4e",Set.of("59f86919823c70c9eba9c8c5c95668143294310690b4b611c93f51f06272df1c","4c7a6536acbb1ba9d1dc0331bc84401273bffac33437c08ea65a4219170396c3","f78b03e90e846482b53f08a083c1a9355aa2f843f5ce04ba95808edaf1803d3d","2e63bde32bb9d46907cd922219a29608a2c719e4da8bd2136970c75ca9b3fac5","7dcec31d04f3eafe379446114a4a3ceb70b12acc8d9b5a3b75f18a606522b340","e3fb99dde59ba4c04a8da969726f85b1b0a6f7b0402806387e064d6ab9bf2877","e27d575b1fd2e5498a15c80b7e4e3f5e80ac5ec6620117897d1010e7c0d473e9","ccde93d727fe656f65eac08f896ec8a48783651374e5305cd9f291d6e8597aaa")),
    Map.entry("7476b642d2ac2c07e65c39ddecd532dd13ff25b8450b379ec65b7ad30ec4a19b",Set.of("f95a668354f045a7835fb77e28e33dbe7d5279f2bf67c1933c36a6e1956736c4")),
    Map.entry("d1a3171624c52ffb404d15b82b159a42576b565edead248b97dfcb2957152013",Set.of("410df97bfee5529595135374878ce5b1db396b9132980d77254de9f821ab2a1b")),
    Map.entry("4f3f74eddb26030eae2389812f0d25321a0c938d6bf06f6727ee3bb96e663f5f",Set.of("c830322f23ad23706270a589910bf5c9e6554dd7235abbe994024da577562d9a","978cdadf543e17abc3e8cfcc700399f0f015fa82825df0f764dc18c857df677e","50161429fd0ca0ef01a089609b0759ee1a2dad1656c78c5de4f7a3e3be527821","324c5b33bfdb528220fa9451a6152f78fe61bcdc2fafa945806a981eccc662cd","41d566f1b4308220d3f9c22b9d84848959a9bf33227eda77f645bdf1885318e2","2a9203a562d22dff29675fd50e4f5f41651fd1ec65ff508c7f35f49552ba0ecf","23831ed76b643cba7a98ad96205cab20bf96d5ca1f0b81fe8f174b4aba0bc61d","df02765ddbd45bab171001b4a03920d5a311da8729015ca97bfc244c463005a4","a623d64518980b4e4727e97ace8e12b06aedf9197034b2f405f07abcef673730","c1b00bc577a8fecf5fcc22470018a2fa3c1170d077ecab9af0216a92700e2f92","54b032b5b260b72f558d7bdc9bc5d9078e44836b1f197e0b85ba3c1db050cfd5","281a39b3b2841d0ae80252b1a55f5a6cadf72fddcb0ddc94197eed510e361961","414b5ba3549f99321e91e83a88a44677e3a1151cb01a13bada17ad711cc63a3d","f10317321cf38e5aaa04d69aeaa4c0560c93120f9eecd714f29b4cf482bb7d05","348283a6d7802f5b27ece8697411ac686073774efd765c8ab10c0a30265f5787","7b3888aabd56b38f2cdd48b0324372c76a45dbdbe520062377c44ff754e3c77f")),
    Map.entry("926ab09b6845d965fc03f6eb3db9cd96d9a504e8f6c9a9b46c0b8f2a3c7492f8",Set.of("c7ee2cbc9bbde092f75d598808adc106b23472af7808441aeff8e539c9ffe819")),
    Map.entry("f45047f2b562da16698cb6daf8f0a34d9bc8afa3eac706aef5795bca43377488",Set.of("af0227505b0d466f894c7608e41e1d260a1a3267cad5a5ab68c50933cc09fece","d6205b1556ed912f201332f3a7f9099ed67ca7bf0d34e4090e6a47b4331601c8","419b5b09f20146ba5b91b48306514f5c9ff866a28034eade1f55876aff945984","70ab18b4da3495a09cc6cab37aacdc65ad41d8bc93d30f4b9c029005f6edeec7","2b16a4046d85b1fd5327e6f2ac0403ac3afd46fb7318d137c272ed4dd1846b61","5dc430ac9ee91cf489ff3a969da6d5befa715071bfbce115a4c35a696db797bb","38e6dc87e29dce774ac5f074fd17dced3c2b0c8e870f56671ff8ee202369d504","41e822795f098d83af9edb73b0e1e74ba82f68cd87b45bdee762af5b3daa615c","661f689595fb79d70f6d2387799eb92618dd2831d157779496fdbe7a3d629138","d68881e0bdbe39b540b6e8bc2b932c03b788ebae707f4a7993b6dcae170d5d19","351e9f9def43b0620a8dd85ca5ad5999172e81dced9253e1973a5deb74b02478","638cf9d1bffe1f73c43c4647a770c61ac65caf389304892c8d5666bbd211aeef","e6560cbf9b5fa21178ac9ad98de2a03ba0b6a16f7ea31e23a092a4905d02d065","d63455db06649464a7d0bd7fad4412c5bb6abce425fe8f392e0d003e377e9938","ae930d9d06631e349b9fd94f83c9b8ee5c25a5af708ff893433a9e8c9521cf40","85d997896c6309737ddc23f14558cf465caedb88a3c6064890b45495b090927c")),
    Map.entry("e15d8f886878f7593875ea0d2d11a46dc0dddd11f01ce7bef06fc9b46397a473",Set.of("e85fe93fe7bbffa6ac2ed350e946b98ad9d39ade900bef12d32528dd0ae694f4")),
    Map.entry("f8eac0d41075c74fb30655bae06245e0e723decb0b72f80432075814da30a662",Set.of("37232d1a2ba0221bc3fa7467c8d4bd09d8eb11cb996bf4bcba0da64f748ab07d","6b420ff0a072eec9aeb3df13fcfcbfdd9f7f92b1e2a059fa34b3b869045e24d5","bb19473b5189b8508522cf0088cf91875107ef202919aea147bcc4e44aac2c0f","da291b60e3a52b37c89b95f493a1a58c7f2204e07b3caeb686c6f12db9a0c2aa","5b0996e6a72b8ee78931efe04efaa9fc80f926d667f1a3b9fbfcc940010d978a","ddfb130674886844c9e195b59297b164ffbcec6729b2d0e31e4f9ba55c0d399d","2da3f6d6bc5a509f87385296a0505f08d2e1b82b23e3f364ebd7b83a7bba3bb1","725a708ee5eadb25ea486604065985c7c48ab89e554f6eff1a63442fbff11d0f")),
    Map.entry("f470e69ba06521509007a46c46eef83b0b89af09f683da7146627c824c7e48ce",Set.of("0492da40cc077f76f9293c149749f47dbe5c268edc94ab988f6d7a10ab8287af","39dcf844a8947e77605bdfc372a3cd5bd7015cf07c3ab9dc278c09b4180baf9a")),
    Map.entry("83d3643eaa7e4a729899c927b4b0495954f39aa17909782efd1fba0ea398dd77",Set.of("594bf6cabc0cf3eec5016dc1136c1d0d4c6dfc9de5a46d77a17dc910a2e1a514","7e22925ce3142907a62027b529add96ddc11a312914023ac0ae52565979b7ac8")),
    Map.entry("0bc141cc3340a22e6e776418f0122b76e8fdf2ba58b04f70e709821c9ffd2e5c",Set.of("1c46a2b9a16972d29830769a2bf3eb2d6e62dc14ad2e6d5d3436c00b1e7526d6"))
  );
  static String id(String s){if(s==null||!s.matches("[A-Za-z][A-Za-z0-9_]{0,127}"))throw new IllegalArgumentException();return s;}
  static List<Map<String,Object>> query(Connection c,String sql,Object...args)throws SQLException {
    List<Map<String,Object>> out=new ArrayList<>();
    try(PreparedStatement p=c.prepareStatement(sql)){for(int n=0;n<args.length;n++)p.setObject(n+1,args[n]);p.setQueryTimeout(30);
      try(ResultSet r=p.executeQuery()){ResultSetMetaData m=r.getMetaData();while(r.next()){Map<String,Object> row=new TreeMap<>();for(int n=1;n<=m.getColumnCount();n++){Object v=r.getObject(n);if(v instanceof Clob)v=r.getString(n);if(v instanceof Long && Math.abs((Long)v)>9007199254740991L)v=v.toString();if(v!=null && !(v instanceof Number) && !(v instanceof Boolean) && !(v instanceof String) && !(v instanceof byte[]))v=v.toString();if(v instanceof byte[])v=Base64.getEncoder().encodeToString((byte[])v);row.put(m.getColumnLabel(n),v);}out.add(row);}}}
    out.sort(Comparator.comparing(x->{try{return JSON.writeValueAsString(x);}catch(Exception e){throw new RuntimeException(e);}}));return out;
  }
  static List<Map<String,Object>> metadata(ResultSet r)throws SQLException {List<Map<String,Object>> out=new ArrayList<>();try(r){ResultSetMetaData m=r.getMetaData();while(r.next()){Map<String,Object> row=new TreeMap<>();for(int n=1;n<=m.getColumnCount();n++)row.put(m.getColumnLabel(n),r.getString(n));out.add(row);}}out.sort(Comparator.comparing(Object::toString));return out;}
  static Map<String,Object> rowSummary(Connection c,String schema,String table)throws Exception {
    List<String> hashes=new ArrayList<>();long total=0;
    try(Statement p=c.createStatement();ResultSet r=p.executeQuery("SELECT * FROM \""+schema+"\".\""+table+"\"")) {
      ResultSetMetaData m=r.getMetaData();while(r.next()) {
        Map<String,Object> row=new TreeMap<>();
        for(int n=1;n<=m.getColumnCount();n++){Object v=r.getObject(n);
          if(v instanceof Blob){Blob blob=(Blob)v;if(blob.length()>8*1024*1024)throw new SQLException("capacity","LIMIT",-1);v=blob.getBytes(1,(int)blob.length());}
          if(v instanceof Clob){Clob clob=(Clob)v;if(clob.length()>8*1024*1024)throw new SQLException("capacity","LIMIT",-1);v=clob.getSubString(1,(int)clob.length());}
          if(v instanceof byte[])v=Base64.getEncoder().encodeToString((byte[])v);
          if(v!=null && !(v instanceof Boolean) && !(v instanceof String))v=v.toString();
          row.put(m.getColumnName(n),v);
        }
        byte[] bytes=JSON.writeValueAsBytes(row);total+=bytes.length;if(total>128*1024*1024)throw new SQLException("capacity","LIMIT",-1);
        hashes.add(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
      }
    }
    Collections.sort(hashes);String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(hashes)));
    return Map.of("table",table,"count",hashes.size(),"contentSha256",digest,"digestMethod","SORTED_TYPED_COLUMN_ROW_SHA256/v1");
  }
  static Map<String,Object> inventory(Connection c,JsonNode request)throws Exception {
    boolean dm=c.getMetaData().getDatabaseProductName().toLowerCase().contains("dm");
    List<Object> schemas=new ArrayList<>(),accounts=new ArrayList<>(),errors=new ArrayList<>();
    for(JsonNode s:request.get("schemas")) {String schema=id(s.asText());Map<String,Object> v=new LinkedHashMap<>();v.put("schema",schema);schemas.add(v);
      for(String category:List.of("objects","columns","constraints","foreignKeys","indexes","sequences","views","triggers","dependencies","rows"))v.put(category,new ArrayList<>());
      try {
        if((dm?query(c,"SELECT USERNAME FROM DBA_USERS WHERE USERNAME=?",schema):query(c,"SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME=?",schema)).size()!=1)throw new SQLException("schema absent","SCHEMA",-1);
        List<Map<String,Object>> objects=new ArrayList<>();
        if(dm) for(Map<String,Object> row:query(c,"SELECT OBJECT_NAME,OBJECT_TYPE FROM DBA_OBJECTS WHERE OWNER=? ORDER BY OBJECT_TYPE,OBJECT_NAME",schema)) objects.add(Map.of("name",row.get("OBJECT_NAME"),"type",row.get("OBJECT_TYPE")));
        else {
          for(Map<String,Object> row:query(c,"SELECT TABLE_NAME,TABLE_TYPE FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=?",schema))objects.add(Map.of("name",row.get("TABLE_NAME"),"type",row.get("TABLE_TYPE")));
          for(String[] spec:new String[][]{{"SEQUENCES","SEQUENCE_NAME","SEQUENCE_SCHEMA","SEQUENCE"},{"TRIGGERS","TRIGGER_NAME","TRIGGER_SCHEMA","TRIGGER"},{"INDEXES","INDEX_NAME","INDEX_SCHEMA","INDEX"},{"DOMAINS","DOMAIN_NAME","DOMAIN_SCHEMA","DOMAIN"},{"ROUTINES","SPECIFIC_NAME","ROUTINE_SCHEMA","ROUTINE"},{"SYNONYMS","SYNONYM_NAME","SYNONYM_SCHEMA","SYNONYM"},{"CONSTANTS","CONSTANT_NAME","CONSTANT_SCHEMA","CONSTANT"}})
            for(Map<String,Object> row:query(c,"SELECT "+spec[1]+" FROM INFORMATION_SCHEMA."+spec[0]+" WHERE "+spec[2]+"=?",schema))objects.add(Map.of("name",row.get(spec[1]),"type",spec[3]));
        }
        objects.sort(Comparator.comparing(row->row.get("name").toString()+":"+row.get("type")));v.put("objects",objects);
        v.put("jdbcColumns",metadata(c.getMetaData().getColumns(null,schema,"%","%")));
        v.put("columns",dm?query(c,"SELECT * FROM DBA_TAB_COLUMNS WHERE OWNER=?",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=?",schema));
        v.put("constraints",dm?query(c,"SELECT * FROM DBA_CONSTRAINTS WHERE OWNER=?",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=?",schema));
        v.put("indexes",dm?query(c,"SELECT * FROM DBA_INDEXES WHERE OWNER=?",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_SCHEMA=?",schema));
        v.put("indexColumns",dm?query(c,"SELECT * FROM DBA_IND_COLUMNS WHERE INDEX_OWNER=?",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.INDEX_COLUMNS WHERE INDEX_SCHEMA=?",schema));
        v.put("checkConstraints",dm?query(c,"SELECT CONSTRAINT_NAME,SEARCH_CONDITION FROM DBA_CONSTRAINTS WHERE OWNER=? AND CONSTRAINT_TYPE='C'",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=?",schema));
        v.put("constraintColumns",dm?query(c,"SELECT * FROM DBA_CONS_COLUMNS WHERE OWNER=?",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE WHERE CONSTRAINT_SCHEMA=?",schema));
        v.put("sequences",dm?query(c,"SELECT * FROM DBA_SEQUENCES WHERE SEQUENCE_OWNER=?",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA=?",schema));
        v.put("views",dm?query(c,"SELECT * FROM DBA_VIEWS WHERE OWNER=?",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA=?",schema));
        v.put("triggers",dm?query(c,"SELECT * FROM DBA_TRIGGERS WHERE OWNER=?",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA=?",schema));
        // DM dependency catalog must succeed; missing catalogs are PARTIAL, never guessed empty.
        v.put("dependencies",dm?query(c,"SELECT * FROM DBA_DEPENDENCIES WHERE OWNER=?",schema):query(c,"SELECT * FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=?",schema));
        List<Object> fks=new ArrayList<>(),rows=new ArrayList<>();
        for(Map<String,Object> t:objects)if(List.of("TABLE","BASE TABLE").contains(t.get("type"))) {
          String table=id(t.get("name").toString());fks.addAll(metadata(c.getMetaData().getImportedKeys(null,schema,table)));
          rows.add(rowSummary(c,schema,table));
        }
        v.put("foreignKeys",fks);v.put("rows",rows);
      }catch(SQLException e){errors.add(Map.of("schema",schema,"sqlState",String.valueOf(e.getSQLState()),"vendorCode",e.getErrorCode()));}
    }
    for(JsonNode a:request.get("accounts")){String name=id(a.asText());Map<String,Object> v=new LinkedHashMap<>();v.put("name",name);accounts.add(v);
      try {
        List<Map<String,Object>> users=dm?query(c,"SELECT USERNAME,ACCOUNT_STATUS FROM DBA_USERS WHERE USERNAME=?",name):query(c,"SELECT USER_NAME,IS_ADMIN FROM INFORMATION_SCHEMA.USERS WHERE USER_NAME=?",name);
        v.put("exists",users.size()==1);v.put("account",users);
        v.put("systemPrivileges",dm?query(c,"SELECT * FROM DBA_SYS_PRIVS WHERE GRANTEE=?",name):List.of());
        v.put("roleGrants",dm?query(c,"SELECT * FROM DBA_ROLE_PRIVS WHERE GRANTEE=?",name):query(c,"SELECT * FROM INFORMATION_SCHEMA.RIGHTS WHERE GRANTEE=? AND GRANTEDROLE IS NOT NULL",name));
        v.put("tableGrants",dm?query(c,"SELECT * FROM DBA_TAB_PRIVS WHERE GRANTEE=?",name):query(c,"SELECT * FROM INFORMATION_SCHEMA.RIGHTS WHERE GRANTEE=? AND GRANTEDROLE IS NULL",name));
        v.put("columnGrants",dm?query(c,"SELECT * FROM DBA_COL_PRIVS WHERE GRANTEE=?",name):query(c,"SELECT * FROM INFORMATION_SCHEMA.COLUMN_PRIVILEGES WHERE GRANTEE=?",name));
        Set<String> subjects=new TreeSet<>(List.of(name,"PUBLIC"));
        if(dm){boolean changed=true;while(changed){changed=false;for(String subject:new ArrayList<>(subjects))for(Map<String,Object> grant:query(c,"SELECT GRANTED_ROLE FROM DBA_ROLE_PRIVS WHERE GRANTEE=?",subject))changed|=subjects.add(grant.get("GRANTED_ROLE").toString());}}
        List<Object> effective=new ArrayList<>();for(String subject:subjects){effective.add(Map.of("subject",subject,"system",dm?query(c,"SELECT * FROM DBA_SYS_PRIVS WHERE GRANTEE=?",subject):List.of(),"roles",dm?query(c,"SELECT * FROM DBA_ROLE_PRIVS WHERE GRANTEE=?",subject):query(c,"SELECT * FROM INFORMATION_SCHEMA.RIGHTS WHERE GRANTEE=?",subject),"tables",dm?query(c,"SELECT * FROM DBA_TAB_PRIVS WHERE GRANTEE=?",subject):query(c,"SELECT * FROM INFORMATION_SCHEMA.TABLE_PRIVILEGES WHERE GRANTEE=?",subject),"columns",dm?query(c,"SELECT * FROM DBA_COL_PRIVS WHERE GRANTEE=?",subject):query(c,"SELECT * FROM INFORMATION_SCHEMA.COLUMN_PRIVILEGES WHERE GRANTEE=?",subject)));}
        v.put("effectiveGrantSubjects",subjects);v.put("effectiveGrants",effective);
      }catch(SQLException e){errors.add(Map.of("account",name,"sqlState",String.valueOf(e.getSQLState()),"vendorCode",e.getErrorCode()));}
    }
    return Map.of("format","SINGLE_DATABASE_LIVE_INVENTORY/v1","targetId",request.get("targetId").asText(),"databaseProduct",c.getMetaData().getDatabaseProductName(),"actualJdbc",true,"schemas",schemas,"accounts",accounts,"errors",errors,"complete",errors.isEmpty(),"nativePrivileges",dm?nativePrivileges(c):Map.of());
  }
  static Map<String,Object> nativePrivileges(Connection c)throws Exception {
    Map<String,Object> definitions=new TreeMap<>();
    for(String subject:List.of("PUBLIC","SVI","SOI"))definitions.put(subject,Map.of(
      "system",query(c,"SELECT PRIVILEGE,ADMIN_OPTION FROM DBA_SYS_PRIVS WHERE GRANTEE=?",subject),
      "roles",query(c,"SELECT GRANTED_ROLE,ADMIN_OPTION FROM DBA_ROLE_PRIVS WHERE GRANTEE=?",subject),
      "tables",query(c,"SELECT OWNER,TABLE_NAME,PRIVILEGE,GRANTABLE FROM DBA_TAB_PRIVS WHERE GRANTEE=?",subject),
      "columns",query(c,"SELECT OWNER,TABLE_NAME,COLUMN_NAME,PRIVILEGE,GRANTABLE FROM DBA_COL_PRIVS WHERE GRANTEE=?",subject)));
    return Map.of("format","DM8_NATIVE_PRIVILEGES/v1","definitions",definitions);
  }
  static Map<String,Object> perform(Connection c,JsonNode r)throws Exception {
    if(!c.getMetaData().getDatabaseProductName().toLowerCase().contains("dm") || !c.getMetaData().getURL().startsWith("jdbc:dm://"))throw new IllegalArgumentException();
    String action=r.path("action").asText();
    if(action.equals("inventory"))return inventory(c,r);
    if(action.equals("bootstrap-inventory")) {
      List<Object> accounts=new ArrayList<>();
      for(JsonNode a:r.get("accounts")) {String name=id(a.asText());accounts.add(Map.of("name",name,
        "users",query(c,"SELECT USERNAME,ACCOUNT_STATUS FROM DBA_USERS WHERE USERNAME=?",name),
        "systemPrivileges",query(c,"SELECT PRIVILEGE,ADMIN_OPTION FROM DBA_SYS_PRIVS WHERE GRANTEE=?",name),
        "roles",query(c,"SELECT GRANTED_ROLE,ADMIN_OPTION FROM DBA_ROLE_PRIVS WHERE GRANTEE=?",name),
        "tableGrants",query(c,"SELECT OWNER,TABLE_NAME,PRIVILEGE,GRANTABLE FROM DBA_TAB_PRIVS WHERE GRANTEE=?",name),
        "columnGrants",query(c,"SELECT OWNER,TABLE_NAME,COLUMN_NAME,PRIVILEGE,GRANTABLE FROM DBA_COL_PRIVS WHERE GRANTEE=?",name),
        "objects",query(c,"SELECT OBJECT_NAME,OBJECT_TYPE FROM DBA_OBJECTS WHERE OWNER=?",name)));
      }
      return Map.of("format","SINGLE_DATABASE_BOOTSTRAP_INVENTORY/v1","targetId",r.get("targetId").asText(),"actualJdbc",true,"accounts",accounts,
        "publicSystemPrivileges",query(c,"SELECT PRIVILEGE,ADMIN_OPTION FROM DBA_SYS_PRIVS WHERE GRANTEE='PUBLIC'"),
        "publicRoles",query(c,"SELECT GRANTED_ROLE,ADMIN_OPTION FROM DBA_ROLE_PRIVS WHERE GRANTEE='PUBLIC'"),"nativePrivileges",nativePrivileges(c));
    }
    if(action.equals("bootstrap-step")) {
      if(!c.getMetaData().getUserName().equalsIgnoreCase("SYSDBA"))throw new IllegalArgumentException();
      JsonNode step=r.get("step");String account=id(step.get("account").asText()),kind=step.get("kind").asText(),sql;
      if(kind.equals("create-user")) {
        String ref=step.get("passwordEnv").asText();if(!ref.matches("[A-Z][A-Z0-9_]{0,127}"))throw new IllegalArgumentException();
        String password=System.getenv(ref);
        // Avoid quoting/statement injection, never echo SQL or driver diagnostics.
        if(password==null||!password.matches("[A-Za-z0-9!#$%&*+.,:;=?@^_|~-]{12,128}"))throw new IllegalArgumentException();
        sql="CREATE USER "+account+" IDENTIFIED BY \""+password+"\"";
      } else if(kind.equals("revoke-native-role")) {
        if(!step.path("role").asText().equals("SOI")||!List.of("CONPROD","CONPROD_SVC","CONAUTH","CONAUTH_SVC","CONAUTH_BAK","ENFPROD","ENFPROD_SVC","ENFAUTH","ENFAUTH_SVC","ENFAUTH_BAK","ADJPROD","ADJPROD_SVC","ADJAUTH","ADJAUTH_SVC","ADJAUTH_BAK").contains(account))throw new IllegalArgumentException();
        if(query(c,"SELECT GRANTED_ROLE FROM DBA_ROLE_PRIVS WHERE GRANTEE=? AND GRANTED_ROLE='SOI'",account).size()!=1)throw new IllegalArgumentException();
        sql="REVOKE SOI FROM "+account;
      } else if(kind.equals("grant-system")) {
        String privilege=step.get("privilege").asText();
        if(!List.of("CREATE SESSION","CREATE TABLE","CREATE VIEW","CREATE SEQUENCE","CREATE INDEX").contains(privilege))throw new IllegalArgumentException();
        sql="GRANT "+privilege+" TO "+account;
      } else throw new IllegalArgumentException();
      try(Statement s=c.createStatement()){s.setQueryTimeout(30);s.execute(sql);}return Map.of("actualJdbc",true,"executedSteps",1);
    }
    if(action.equals("grant-object")) {
      String schema=id(r.path("schema").asText()),object=id(r.path("object").asText()),grantee=id(r.path("grantee").asText()),privilege=r.path("privilege").asText();
      String user=c.getMetaData().getUserName();
      if(!user.equalsIgnoreCase(schema)||List.of("SYSDBA","SYSSSO","SYSAUDITOR","PUBLIC").contains(grantee.toUpperCase())||user.equalsIgnoreCase(grantee)
          ||!r.path("objectType").asText().equals("TABLE")||!r.has("grantOption")||!r.get("grantOption").isBoolean()||r.get("grantOption").asBoolean()
          ||!List.of("SELECT","INSERT","UPDATE","DELETE","REFERENCES").contains(privilege))throw new IllegalArgumentException();
      if(query(c,"SELECT OBJECT_NAME FROM USER_OBJECTS WHERE OBJECT_NAME=? AND OBJECT_TYPE='TABLE'",object.toUpperCase()).size()!=1)throw new IllegalArgumentException();
      c.setSchema(schema);c.setAutoCommit(true);
      try(Statement statement=c.createStatement()){statement.setQueryTimeout(30);statement.execute("GRANT "+privilege+" ON \""+schema+"\".\""+object+"\" TO \""+grantee+"\"");}
      return Map.of("actualJdbc",true,"executedSteps",1,"grantOption",false);
    }
    if(!action.equals("execute"))throw new IllegalArgumentException();
    String schema=id(r.get("schema").asText()),user=c.getMetaData().getUserName();
    if(!user.equalsIgnoreCase(schema)||List.of("SYSDBA","SYSSSO","SYSAUDITOR").contains(user.toUpperCase()))throw new IllegalArgumentException();
    c.setSchema(schema);c.setAutoCommit(true);int done=0;
    for(JsonNode value:r.get("statements")) {
      String sql=value.asText().strip();
      if(r.path("controlledInitializationId").asText().equals("A001")&&sql.matches("(?i)^INSERT\\s+INTO\\s+adj2_source_generation\\s+VALUES\\s*\\(\\s*1\\s*,\\s*0\\s*,\\s*'READY'\\s*\\)$")) {
        try(Statement statement=c.createStatement()){statement.setQueryTimeout(30);statement.execute(sql);done++;}continue;
      }
      String statementSha=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      if(FORWARD_PINS.getOrDefault(r.path("controlledOriginalSha256").asText(),Set.of()).contains(statementSha)) {
        try(Statement statement=c.createStatement()){statement.setQueryTimeout(30);statement.execute(sql);done++;}continue;
      }
      if(!sql.matches("(?is)^(CREATE\\s+(TABLE|INDEX|UNIQUE\\s+INDEX|SEQUENCE|VIEW)\\b|ALTER\\s+TABLE\\b.*\\bADD\\b).*"))throw new IllegalArgumentException();
      String bare=sql.replaceAll("'(?:''|[^'])*'","''");
      if(bare.matches("(?is).*\\b(DROP|TRUNCATE|DELETE|UPDATE|MERGE|GRANT|REVOKE|EXECUTE|CALL)\\b.*"))throw new IllegalArgumentException();
      try(Statement s=c.createStatement()){s.setQueryTimeout(30);s.execute(sql);done++;}
    }
    return Map.of("actualJdbc",true,"executedStatements",done);
  }
  public static void main(String[] args) {
    try {
      if(args.length!=0)throw new IllegalArgumentException();
      byte[] bytes=System.in.readNBytes(8*1024*1024+1);if(bytes.length>8*1024*1024)throw new IllegalArgumentException();
      JsonNode request=JSON.readTree(bytes);String url=System.getenv("LIFECYCLE_JDBC_URL"),user=System.getenv("LIFECYCLE_JDBC_USER"),password=System.getenv("LIFECYCLE_JDBC_PASSWORD");
      if(url==null||!url.matches("jdbc:dm://[A-Za-z0-9.-]+:[0-9]{1,5}")||user==null||password==null)throw new IllegalArgumentException();
      try(Connection c=DriverManager.getConnection(url,user,password)){System.out.println(JSON.writeValueAsString(perform(c,request)));}
    }catch(Exception e){
      String state=e instanceof SQLException?((SQLException)e).getSQLState():"GUARD";
      if(state==null||!state.matches("[A-Z0-9]{5}"))state="UNKNOWN";
      int vendor=e instanceof SQLException?((SQLException)e).getErrorCode():0;
      System.out.println("{\"error\":\"JDBC_OPERATION_REFUSED_OR_FAILED\",\"sqlState\":\""+state+"\",\"vendorCode\":"+vendor+"}");System.exit(1);
    }
  }
}
