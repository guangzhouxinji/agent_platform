import java.sql.*;
import java.util.UUID;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
public final class InspectConsultationModelEvidence {
    public static void main(String[] args)throws Exception {
        if(args.length!=2 && args.length!=3)throw new IllegalArgumentException("Exact Task, Worker and optional new audit directory required");
        UUID.fromString(args[0]);UUID.fromString(args[1]);
        try(var db=DriverManager.getConnection(System.getenv("MODEL_MIGRATION_URL"),
                System.getenv("MODEL_MIGRATION_USER"),System.getenv("MODEL_MIGRATION_PASSWORD"))) {
            read(db,"worker_current","SELECT status,expires_at FROM id2_workload WHERE subject_id=?",args[1]);
            read(db,"task_current","SELECT version,state FROM con3_task WHERE task_id=?",args[0]);
            read(db,"model_turns","SELECT turn_id,task_version,kind,model_id,prompt_version,created_at,expires_at "
                    +"FROM con3_model_turn WHERE task_id=? ORDER BY created_at",args[0]);
            read(db,"explanation_audit","SELECT raw_output,output_json FROM con3_model_turn "
                    +"WHERE task_id=? AND kind='EXPLANATION' ORDER BY created_at",args[0]);
            if(args.length==3)export(db,args[0],Path.of(args[2]).toAbsolutePath().normalize());
        }
    }
    static void export(Connection db,String task,Path directory)throws Exception {
        Files.createDirectory(directory);
        try(var statement=db.prepareStatement("SELECT turn_id,request_json,raw_output,output_json "
                +"FROM con3_model_turn WHERE task_id=? ORDER BY created_at")) {
            statement.setString(1,task);
            try(var rows=statement.executeQuery()) {
                while(rows.next()) {
                    String turn=UUID.fromString(rows.getString(1)).toString();
                    String[] suffixes={"request","provider","output"};
                    for(int i=0;i<suffixes.length;i++)Files.writeString(
                            directory.resolve(turn+"-"+suffixes[i]+".json"),rows.getString(i+2),
                            StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                }
            }
        }
    }
    static void read(Connection db,String kind,String sql,String id)throws Exception {
        try(var statement=db.prepareStatement(sql)){
            statement.setString(1,id);try(var rows=statement.executeQuery()){
                while(rows.next()){
                    StringBuilder text=new StringBuilder(kind);
                    for(int i=1;i<=rows.getMetaData().getColumnCount();i++)
                        text.append(" ").append(rows.getMetaData().getColumnName(i)).append("=").append(rows.getString(i));
                    System.out.println(text);
                }
            }
        }
    }
}
