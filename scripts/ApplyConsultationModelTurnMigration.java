import java.sql.*;
import java.nio.file.*;

/** Deployment-only migration executor; never a business identity or API bypass. */
public final class ApplyConsultationModelTurnMigration {
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("Exact migration file required");
        Path file=Path.of(args[0]).toAbsolutePath().normalize();
        if(!file.getFileName().toString().equals("V089__product_consultation_model_turn.sql"))
            throw new IllegalArgumentException("Exact model migration required");
        try(var db=DriverManager.getConnection(System.getenv("MODEL_MIGRATION_URL"),
                System.getenv("MODEL_MIGRATION_USER"),System.getenv("MODEL_MIGRATION_PASSWORD"))) {
            if(!"CONPROD".equalsIgnoreCase(db.getMetaData().getUserName()))
                throw new IllegalStateException("Exact product schema required");
            long before=count(db,"SELECT COUNT(*) FROM con3_task");
            if(count(db,"SELECT COUNT(*) FROM USER_TABLES WHERE TABLE_NAME='CON3_MODEL_TURN'")!=0)
                throw new IllegalStateException("Preserve existing migration");
            for(String sql:Files.readString(file).split(";")) if(!sql.isBlank())
                try(var statement=db.createStatement()){statement.execute(sql);}
            if(count(db,"SELECT COUNT(*) FROM USER_TAB_COLUMNS WHERE TABLE_NAME='CON3_MODEL_TURN'")!=19
                    || count(db,"SELECT COUNT(*) FROM con3_model_turn")!=0
                    || before!=count(db,"SELECT COUNT(*) FROM con3_task"))
                throw new IllegalStateException("Migration verification failed");
            try(var statement=db.createStatement()) {
                statement.execute("GRANT SELECT ON CONPROD.CON3_MODEL_TURN TO CONPROD_BKP");
            }
            System.out.println("V089_DM8=passed columns=19 new_turns=0 existing_tasks_unchanged=true backup=SELECT_ONLY");
        }
    }
    private static long count(Connection db,String sql)throws Exception {
        try(var statement=db.createStatement();var rows=statement.executeQuery(sql)){rows.next();return rows.getLong(1);}
    }
}
