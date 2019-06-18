package lama.sqlite3.gen;

import java.sql.Connection;

import lama.Query;
import lama.QueryAdapter;
import lama.StateToReproduce;

/**
 * @see https://www.sqlite.org/lang_reindex.html
 */
public class SQLite3ReindexGenerator {

	// only works for the main schema
	public static Query executeReindex(Connection con, StateToReproduce state) {
		return new QueryAdapter("REINDEX;");
	}
}
