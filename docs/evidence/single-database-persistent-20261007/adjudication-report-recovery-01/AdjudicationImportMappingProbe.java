package cn.xinji.agentplatform.adjudication.casework;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public final class AdjudicationImportMappingProbe {
 public static void main(String[] args)throws Exception{
  AdjudicationPersistentHarness.out=Path.of(System.getenv("ADJ_OUTPUT"));AdjudicationEnvelope.out=AdjudicationPersistentHarness.out;
  var runtime=AdjudicationPersistentHarness.db("ADJENGMAIN_SVC","ADJENGMAIN");var before=AdjudicationPersistentHarness.snapshot(runtime,AdjudicationPersistentHarness.TABLES);
  var protectedBefore=AdjudicationPersistentHarness.protectedSnapshot();
  AdjudicationPersistentHarness.write("before-mapping-native-source-and-protected-check.json",Map.of("source",before,"protected",protectedBefore));
  if(!AdjudicationPersistentHarness.JSON.valueToTree(before).equals(AdjudicationPersistentHarness.JSON.readTree(Files.readAllBytes(AdjudicationPersistentHarness.out.resolve("main-active-baseline.json")))))throw new IllegalStateException("Failed import changed source rows");
  var target=AdjudicationPersistentHarness.db("ADJENGREST","ADJENGREST");int targetTables=target.queryForObject("SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER='ADJENGREST'",Integer.class);
  if(targetTables!=0)throw new IllegalStateException("REST not empty; do not rerun native CREATE or overwrite");
  String secret=System.getenv("ADJ_ADJENGREST_PASSWORD");String script="IFS= read -r p; p=$(printf %s \"$p\" | tr -d '\\r'); /opt/dmdbms/bin/dimp \"ADJENGREST/$p@127.0.0.1:5236\" SCHEMAS=ADJENGMAIN REMAP_SCHEMA=ADJENGMAIN:ADJENGREST FILE=authenticated-restore-v1.dmp DIRECTORY="+AdjudicationEnvelope.DIR+" GRANTS=N IGNORE=N NOLOGFILE=Y";
  var result=AdjudicationEnvelope.exec(AdjudicationEnvelope.docker("/bin/sh","-c",script),(secret+"\n").getBytes(StandardCharsets.UTF_8));String text=new String(result.output(),StandardCharsets.UTF_8).replace(secret,"[REDACTED]");Files.writeString(AdjudicationPersistentHarness.out.resolve("native-import-explicit-remap.log"),text,StandardOpenOption.CREATE_NEW);
  boolean sourceUnchanged=before.equals(AdjudicationPersistentHarness.snapshot(runtime,AdjudicationPersistentHarness.TABLES));boolean protectedUnchanged=protectedBefore.equals(AdjudicationPersistentHarness.protectedSnapshot());int afterTables=target.queryForObject("SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER='ADJENGREST'",Integer.class);
  AdjudicationPersistentHarness.write("native-explicit-remap-probe-result.json",Map.of("nativeExit",result.code(),"targetTablesBefore",targetTables,"targetTablesAfter",afterTables,"sourceUnchanged",sourceUnchanged,"protectedHistoriesUnchanged",protectedUnchanged,"explicitRemapArg","REMAP_SCHEMA=ADJENGMAIN:ADJENGREST","targetLogin","ADJENGREST","noPermissionExpansion",true));
  System.out.println("explicit remap nativeExit="+result.code()+" targetTables="+afterTables+" sourceUnchanged="+sourceUnchanged+" protectedUnchanged="+protectedUnchanged);if(!sourceUnchanged||!protectedUnchanged||result.code()!=0||afterTables!=6)System.exit(2);
 }
}
