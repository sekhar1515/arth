package db.migration;

import com.expensetracker.categorisation.NoteDictionary;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

/**
 * Note keys now keep only the words that say what a payment was for ("chitti payment nov" → "chitti"), so learning
 * carries over from one month to the next. Recomputes existing keys with the same Java logic the app uses.
 */
public class V5__core_note_keys extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement select = context.getConnection().createStatement();
             ResultSet rs = select.executeQuery("SELECT id, notes FROM transactions WHERE notes IS NOT NULL");
             PreparedStatement update = context.getConnection().prepareStatement("UPDATE transactions SET note_key = ? WHERE id = ?")) {
            int pending = 0;
            while (rs.next()) {
                update.setString(1, NoteDictionary.coreKey(rs.getString("notes")));
                update.setObject(2, rs.getObject("id", UUID.class));
                update.addBatch();
                if (++pending == 1000) {
                    update.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) update.executeBatch();
        }
    }
}
