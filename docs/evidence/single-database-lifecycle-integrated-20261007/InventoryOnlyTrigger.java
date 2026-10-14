import java.sql.Connection;
import org.h2.api.Trigger;
/** Inventory-only engineering object; never invoked and never writes domain data. */
public final class InventoryOnlyTrigger implements Trigger {
 public void fire(Connection connection,Object[] oldRow,Object[] newRow) {}
}
