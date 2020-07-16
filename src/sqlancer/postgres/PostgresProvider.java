package sqlancer.postgres;

import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.AbstractAction;
import sqlancer.CompositeTestOracle;
import sqlancer.IgnoreMeException;
import sqlancer.ProviderAdapter;
import sqlancer.Query;
import sqlancer.QueryAdapter;
import sqlancer.QueryProvider;
import sqlancer.Randomly;
import sqlancer.StateToReproduce;
import sqlancer.StateToReproduce.PostgresStateToReproduce;
import sqlancer.StatementExecutor;
import sqlancer.TestOracle;
import sqlancer.postgres.PostgresSchema.PostgresColumn;
import sqlancer.postgres.PostgresSchema.PostgresTable;
import sqlancer.postgres.ast.PostgresExpression;
import sqlancer.postgres.gen.PostgresAlterTableGenerator;
import sqlancer.postgres.gen.PostgresAnalyzeGenerator;
import sqlancer.postgres.gen.PostgresClusterGenerator;
import sqlancer.postgres.gen.PostgresCommentGenerator;
import sqlancer.postgres.gen.PostgresDeleteGenerator;
import sqlancer.postgres.gen.PostgresDiscardGenerator;
import sqlancer.postgres.gen.PostgresDropIndexGenerator;
import sqlancer.postgres.gen.PostgresIndexGenerator;
import sqlancer.postgres.gen.PostgresInsertGenerator;
import sqlancer.postgres.gen.PostgresNotifyGenerator;
import sqlancer.postgres.gen.PostgresQueryCatalogGenerator;
import sqlancer.postgres.gen.PostgresReindexGenerator;
import sqlancer.postgres.gen.PostgresSequenceGenerator;
import sqlancer.postgres.gen.PostgresSetGenerator;
import sqlancer.postgres.gen.PostgresStatisticsGenerator;
import sqlancer.postgres.gen.PostgresTableGenerator;
import sqlancer.postgres.gen.PostgresTransactionGenerator;
import sqlancer.postgres.gen.PostgresTruncateGenerator;
import sqlancer.postgres.gen.PostgresUpdateGenerator;
import sqlancer.postgres.gen.PostgresVacuumGenerator;
import sqlancer.postgres.gen.PostgresViewGenerator;
import sqlancer.sqlite3.gen.SQLite3Common;

// EXISTS
// IN
public final class PostgresProvider extends ProviderAdapter<PostgresGlobalState, PostgresOptions> {

    public static boolean generateOnlyKnown;

    private PostgresGlobalState globalState;

    public PostgresProvider() {
        super(PostgresGlobalState.class, PostgresOptions.class);
    }

    public enum Action implements AbstractAction<PostgresGlobalState> {
        ANALYZE(PostgresAnalyzeGenerator::create), //
        ALTER_TABLE(g -> PostgresAlterTableGenerator.create(g.getSchema().getRandomTable(t -> !t.isView()), g,
                generateOnlyKnown)), //
        CLUSTER(PostgresClusterGenerator::create), //
        COMMIT(g -> {
            Query query;
            if (Randomly.getBoolean()) {
                query = new QueryAdapter("COMMIT", true);
            } else if (Randomly.getBoolean()) {
                query = PostgresTransactionGenerator.executeBegin();
            } else {
                query = new QueryAdapter("ROLLBACK", true);
            }
            return query;
        }), //
        CREATE_STATISTICS(PostgresStatisticsGenerator::insert), //
        DROP_STATISTICS(PostgresStatisticsGenerator::remove), //
        DELETE(PostgresDeleteGenerator::create), //
        DISCARD(PostgresDiscardGenerator::create), //
        DROP_INDEX(PostgresDropIndexGenerator::create), //
        INSERT(PostgresInsertGenerator::insert), //
        UPDATE(PostgresUpdateGenerator::create), //
        TRUNCATE(PostgresTruncateGenerator::create), //
        VACUUM(PostgresVacuumGenerator::create), //
        REINDEX(PostgresReindexGenerator::create), //
        SET(PostgresSetGenerator::create), //
        CREATE_INDEX(PostgresIndexGenerator::generate), //
        SET_CONSTRAINTS((g) -> {
            StringBuilder sb = new StringBuilder();
            sb.append("SET CONSTRAINTS ALL ");
            sb.append(Randomly.fromOptions("DEFERRED", "IMMEDIATE"));
            return new QueryAdapter(sb.toString());
        }), //
        RESET_ROLE((g) -> new QueryAdapter("RESET ROLE")), //
        COMMENT_ON(PostgresCommentGenerator::generate), //
        RESET((g) -> new QueryAdapter("RESET ALL") /*
                                                    * https://www.postgresql.org/docs/devel/sql-reset.html TODO: also
                                                    * configuration parameter
                                                    */), //
        NOTIFY(PostgresNotifyGenerator::createNotify), //
        LISTEN((g) -> PostgresNotifyGenerator.createListen()), //
        UNLISTEN((g) -> PostgresNotifyGenerator.createUnlisten()), //
        CREATE_SEQUENCE(PostgresSequenceGenerator::createSequence), //
        CREATE_VIEW(PostgresViewGenerator::create), //
        QUERY_CATALOG((g) -> PostgresQueryCatalogGenerator.query());

        private final QueryProvider<PostgresGlobalState> queryProvider;

        Action(QueryProvider<PostgresGlobalState> queryProvider) {
            this.queryProvider = queryProvider;
        }

        @Override
        public Query getQuery(PostgresGlobalState state) throws SQLException {
            return queryProvider.getQuery(state);
        }
    }

    private static int mapActions(PostgresGlobalState globalState, Action a) {
        Randomly r = globalState.getRandomly();
        int nrPerformed;
        switch (a) {
        case CREATE_INDEX:
        case CLUSTER:
            nrPerformed = r.getInteger(0, 3);
            break;
        case CREATE_STATISTICS:
            nrPerformed = r.getInteger(0, 5);
            break;
        case DISCARD:
        case DROP_INDEX:
            nrPerformed = r.getInteger(0, 5);
            break;
        case COMMIT:
            nrPerformed = r.getInteger(0, 0);
            break;
        case ALTER_TABLE:
            nrPerformed = r.getInteger(0, 5);
            break;
        case REINDEX:
        case RESET:
            nrPerformed = r.getInteger(0, 3);
            break;
        case DELETE:
        case RESET_ROLE:
        case SET:
        case QUERY_CATALOG:
            nrPerformed = r.getInteger(0, 5);
            break;
        case ANALYZE:
            nrPerformed = r.getInteger(0, 3);
            break;
        case VACUUM:
        case SET_CONSTRAINTS:
        case COMMENT_ON:
        case NOTIFY:
        case LISTEN:
        case UNLISTEN:
        case CREATE_SEQUENCE:
        case DROP_STATISTICS:
        case TRUNCATE:
            nrPerformed = r.getInteger(0, 2);
            break;
        case CREATE_VIEW:
            nrPerformed = r.getInteger(0, 2);
            break;
        case UPDATE:
            nrPerformed = r.getInteger(0, 10);
            break;
        case INSERT:
            nrPerformed = r.getInteger(0, globalState.getOptions().getMaxNumberInserts());
            break;
        default:
            throw new AssertionError(a);
        }
        return nrPerformed;

    }

    @Override
    public void generateDatabase(PostgresGlobalState globalState) throws SQLException {
        while (globalState.getSchema().getDatabaseTables().size() < Randomly.fromOptions(1, 2)) {
            try {
                String tableName = SQLite3Common.createTableName(globalState.getSchema().getDatabaseTables().size());
                Query createTable = PostgresTableGenerator.generate(tableName, globalState.getSchema(),
                        generateOnlyKnown, globalState);
                globalState.executeStatement(createTable);
		// TODO: distribute tables
		// 80% distributed, 10% local, 10% reference
		// query that outputs fields (+ filter by primary key
		// random pick from any eligible distribution column
		// sql query to distirbute it + add to log
            } catch (IgnoreMeException e) {

            }
        }

        StatementExecutor<PostgresGlobalState, Action> se = new StatementExecutor<>(globalState, Action.values(),
                PostgresProvider::mapActions, (q) -> {
                    if (globalState.getSchema().getDatabaseTables().isEmpty()) {
                        throw new IgnoreMeException();
                    }
                });
        // TODO: transactions broke during refactoring
        // catch (Throwable t) {
        // if (t.getMessage().contains("current transaction is aborted")) {
        // manager.execute(new QueryAdapter("ABORT"));
        // globalState.setSchema(PostgresSchema.fromConnection(con, databaseName));
        // } else {
        // System.err.println(query.getQueryString());
        // throw t;
        // }
        // }
        se.executeStatements();
        globalState.executeStatement(new QueryAdapter("COMMIT", true));
        globalState.executeStatement(new QueryAdapter("SET SESSION statement_timeout = 5000;\n"));
    }

    @Override
    protected TestOracle getTestOracle(PostgresGlobalState globalState) throws SQLException {
        List<TestOracle> oracles = globalState.getDmbsSpecificOptions().oracle.stream().map(o -> {
            try {
                return o.create(globalState);
            } catch (SQLException e1) {
                throw new AssertionError(e1);
            }
        }).collect(Collectors.toList());
        return new CompositeTestOracle(oracles);
    }

    @Override
    public Connection createDatabase(PostgresGlobalState globalState) throws SQLException {
        String url = "jdbc:postgresql://localhost:5432/test";
        // TODO: make database creation citus-compatible
        // connect to 9700/postgres (make this optional argument)
        // get worker nodes from this database
        // add citus extension to these nodes
        // after each database added to coordinator node, add those databases to worker nodes as well (from existing database)
        // switch to new database, add workers to coordinator node
        // user: sqlancer? hardcode v-naugur for now, make user optional too
        String databaseName = globalState.getDatabaseName();
        Connection con = DriverManager.getConnection(url, globalState.getOptions().getUserName(),
                globalState.getOptions().getPassword());
        globalState.getState().statements.add(new QueryAdapter("\\c test;"));
        globalState.getState().statements.add(new QueryAdapter("DROP DATABASE IF EXISTS " + databaseName));
        String createDatabaseCommand = getCreateDatabaseCommand(databaseName, con, globalState);
        globalState.getState().statements.add(new QueryAdapter(createDatabaseCommand));
        globalState.getState().statements.add(new QueryAdapter("\\c " + databaseName));
        try (Statement s = con.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + databaseName);
        }
        try (Statement s = con.createStatement()) {
            s.execute(createDatabaseCommand);
        }
        con.close();
        con = DriverManager.getConnection("jdbc:postgresql://localhost:5432/" + databaseName,
                globalState.getOptions().getUserName(), globalState.getOptions().getPassword());
        List<String> statements = Arrays.asList(
                // "CREATE EXTENSION IF NOT EXISTS btree_gin;",
                // "CREATE EXTENSION IF NOT EXISTS btree_gist;", // TODO: undefined symbol: elog_start
                "CREATE EXTENSION IF NOT EXISTS pg_prewarm;", "SET max_parallel_workers_per_gather=16");
        for (String s : statements) {
            QueryAdapter query = new QueryAdapter(s);
            globalState.getState().statements.add(query);
            query.execute(con);
        }
        // new QueryAdapter("set jit_above_cost = 0; set jit_inline_above_cost = 0; set jit_optimize_above_cost =
        // 0;").execute(con);
        return con;
    }

    private String getCreateDatabaseCommand(String databaseName, Connection con, PostgresGlobalState state) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE DATABASE " + databaseName + " ");
        if (Randomly.getBoolean() && ((PostgresOptions) state.getDmbsSpecificOptions()).testCollations) {
            if (Randomly.getBoolean()) {
                sb.append("WITH ENCODING '");
                sb.append(Randomly.fromOptions("utf8"));
                sb.append("' ");
            }
            for (String lc : Arrays.asList("LC_COLLATE", "LC_CTYPE")) {
                if (Randomly.getBoolean()) {
                    globalState = new PostgresGlobalState();
                    globalState.setConnection(con);
                    sb.append(String.format(" %s = '%s'", lc, Randomly.fromList(globalState.getCollates())));
                }
            }
            sb.append(" TEMPLATE template0");
        }
        return sb.toString();
    }

    @Override
    public String getDBMSName() {
        return "postgres";
    }

    @Override
    public void printDatabaseSpecificState(FileWriter writer, StateToReproduce state) {
        StringBuilder sb = new StringBuilder();
        PostgresStateToReproduce specificState = (PostgresStateToReproduce) state;
        if (specificState.getRandomRowValues() != null) {
            List<PostgresColumn> columnList = specificState.getRandomRowValues().keySet().stream()
                    .collect(Collectors.toList());
            List<PostgresTable> tableList = columnList.stream().map(c -> c.getTable()).distinct().sorted()
                    .collect(Collectors.toList());
            for (PostgresTable t : tableList) {
                sb.append("-- " + t.getName() + "\n");
                List<PostgresColumn> columnsForTable = columnList.stream().filter(c -> c.getTable().equals(t))
                        .collect(Collectors.toList());
                for (PostgresColumn c : columnsForTable) {
                    sb.append("--\t");
                    sb.append(c);
                    sb.append("=");
                    sb.append(specificState.getRandomRowValues().get(c));
                    sb.append("\n");
                }
            }
            sb.append("expected values: \n");
            PostgresExpression whereClause = ((PostgresStateToReproduce) state).getWhereClause();
            if (whereClause != null) {
                sb.append(PostgresVisitor.asExpectedValues(whereClause).replace("\n", "\n-- "));
            }
        }
        try {
            writer.write(sb.toString());
            writer.flush();
        } catch (IOException e) {
            throw new AssertionError();
        }
    }

    @Override
    public StateToReproduce getStateToReproduce(String databaseName) {
        return new PostgresStateToReproduce(databaseName);
    }

}
