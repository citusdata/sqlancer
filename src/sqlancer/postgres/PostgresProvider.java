package sqlancer.postgres;

import java.io.FileWriter;
import java.sql.ResultSet;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.AbstractAction;
import sqlancer.CompositeTestOracle;
import sqlancer.DatabaseProvider;
import sqlancer.GlobalState;
import sqlancer.IgnoreMeException;
import sqlancer.Main.QueryManager;
import sqlancer.Main.StateLogger;
import sqlancer.MainOptions;
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
public final class PostgresProvider implements DatabaseProvider<PostgresGlobalState, PostgresOptions> {

    public static boolean generateOnlyKnown = false;

    private PostgresGlobalState globalState;

    public enum Action implements AbstractAction<PostgresGlobalState> {
        ANALYZE(PostgresAnalyzeGenerator::create), //
        ALTER_TABLE(g -> PostgresAlterTableGenerator.create(g.getSchema().getRandomTable(t -> !t.isView()), g,
                generateOnlyKnown)), //
        CLUSTER(PostgresClusterGenerator::create), //
        COMMIT(g -> {
            Query query;
            if (Randomly.getBoolean()) {
                query = new QueryAdapter("COMMIT") {
                    @Override
                    public boolean couldAffectSchema() {
                        return true;
                    }
                };
            } else if (Randomly.getBoolean()) {
                query = PostgresTransactionGenerator.executeBegin();
            } else {
                query = new QueryAdapter("ROLLBACK") {
                    @Override
                    public boolean couldAffectSchema() {
                        return true;
                    }
                };
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
        RESET((g) -> new QueryAdapter(
                "RESET ALL") /*
                              * https://www.postgresql.org/docs/devel/sql-reset.html TODO: also configuration parameter
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

    // FIXME: static or not?
    private class PostgresColumnInfo{

        private final String name;
        private final String type;
        private final String constraint;

        public PostgresColumnInfo(String col_name, String col_type, String col_constraint) {
            this.name = col_name;
            this.type = col_type; 
            this.constraint = col_constraint;
        }

        public String get_name() {
            return this.name;
        }

        public String get_type() {
            return this.type;
        }

        public String get_constraint() {
            return this.constraint;
        }

    }

    // FIXME: static or not?
    private class WorkerNode{

        private final String name;
        private final int port;

        public WorkerNode(String node_name, int node_port) {
            this.name = node_name;
            this.port = node_port; 
        }

        public String get_name() {
            return this.name;
        }

        public int get_port() {
            return this.port;
        }

    }

    // FIXME: static or not?
    private final void distributeTable(List<PostgresColumnInfo> columnInfos, String tableName, PostgresGlobalState globalState, Connection con) throws SQLException {
        if (columnInfos.size() != 0) {
            PostgresColumnInfo columnToDistributeInfo = Randomly.fromList(columnInfos);
            globalState.getState().statements.add(new QueryAdapter("SELECT create_distributed_table('" + tableName + "', '" + columnToDistributeInfo.get_name() + "');"));
            try (Statement s = con.createStatement()) {
                s.execute("SELECT create_distributed_table('" + tableName + "', '" + columnToDistributeInfo.get_name() + "');");
            } 
        }
    }

    // FIXME: static or not?
    private final void createDistributedTable(String tableName, PostgresGlobalState globalState, Connection con) throws SQLException {
        List<PostgresColumnInfo> columnInfos = new ArrayList<>();
        int numDistributionConstraints = 0;
        try (Statement s = con.createStatement()) {
            ResultSet rs = s.executeQuery(" SELECT * FROM information_schema.table_constraints WHERE table_name = '" + tableName + "' AND (constraint_type = 'PRIMARY KEY' OR constraint_type = 'UNIQUE' or constraint_type = 'EXCLUDE');");
            while (rs.next()) {
                numDistributionConstraints ++;
            }
        }
        if (numDistributionConstraints == 0) {
            try (Statement s = con.createStatement()) {
                ResultSet rs = s.executeQuery("SELECT column_name, data_type FROM information_schema.columns WHERE table_name = '" + tableName + "';");
                while (rs.next()) {
                    String column_name = rs.getString("column_name");
                    String data_type = rs.getString("data_type");
                    PostgresColumnInfo cInfo = new PostgresColumnInfo(column_name, data_type, null);
                    columnInfos.add(cInfo);
                }
            }
            distributeTable(columnInfos, tableName, globalState, con);
        } else {
            try (Statement s = con.createStatement()) {
                ResultSet rs = s.executeQuery("SELECT c.column_name, c.data_type, tc.constraint_type FROM information_schema.table_constraints tc JOIN information_schema.constraint_column_usage AS ccu USING (constraint_schema, constraint_name) JOIN information_schema.columns AS c ON c.table_schema = tc.constraint_schema AND tc.table_name = c.table_name AND ccu.column_name = c.column_name WHERE (constraint_type = 'PRIMARY KEY' OR constraint_type = 'UNIQUE' OR constraint_type = 'EXCLUDE') AND c.table_name = '" + tableName + "';");
                while (rs.next()) {
                    String column_name = rs.getString("column_name");
                    String data_type = rs.getString("data_type");
                    String constraint_type = rs.getString("constraint_type");
                    PostgresColumnInfo cInfo = new PostgresColumnInfo(column_name, data_type, constraint_type);
                    columnInfos.add(cInfo);
                }
            }
            // TODO: figure out how to use EXCLUDE
            distributeTable(columnInfos, tableName, globalState, con);
        }
        if (Randomly.getBooleanWithRatherLowProbability()) {
            // upgrade distributed table to reference table
            globalState.getState().statements.add(new QueryAdapter("SELECT upgrade_to_reference_table('" + tableName + "');"));
            try (Statement s = con.createStatement()) {
                s.execute("SELECT upgrade_to_reference_table('" + tableName + "');");
            }
        }
    }

    @Override
    public void generateAndTestDatabase(PostgresGlobalState globalState) throws SQLException {
        MainOptions options = globalState.getOptions();
        StateLogger logger = globalState.getLogger();
        StateToReproduce state = globalState.getState();
        String databaseName = globalState.getDatabaseName();
        Connection con = globalState.getConnection();
        QueryManager manager = globalState.getManager();
        if (options.logEachSelect()) {
            logger.writeCurrent(state);
        }
        globalState.setSchema(PostgresSchema.fromConnection(con, databaseName));
        while (globalState.getSchema().getDatabaseTables().size() < 1) {
            try {
                String tableName = SQLite3Common.createTableName(globalState.getSchema().getDatabaseTables().size());
                Query createTable = PostgresTableGenerator.generate(tableName, globalState.getSchema(),
                        generateOnlyKnown, globalState);
                if (options.logEachSelect()) {
                    logger.writeCurrent(createTable.getQueryString());
                }
                manager.execute(createTable);
                if (Randomly.getBooleanWithRatherLowProbability()) {
                    // create local table
                } else if (Randomly.getBooleanWithRatherLowProbability()) {
                    // create reference table
                    globalState.getState().statements.add(new QueryAdapter("SELECT create_reference_table('" + tableName + "');"));
                        try (Statement s = con.createStatement()) {
                            s.execute("SELECT create_reference_table('" + tableName + "');");
                        }
                } else {
                    // create distributed table
                    createDistributedTable(tableName, globalState, con);
                }
                globalState.setSchema(PostgresSchema.fromConnection(con, databaseName));
            } catch (IgnoreMeException e) {

            }
        }

        StatementExecutor<PostgresGlobalState, Action> se = new StatementExecutor<>(globalState, Action.values(),
                PostgresProvider::mapActions, (q) -> {
                    if (q.couldAffectSchema()) {
                        globalState.setSchema(PostgresSchema.fromConnection(con, databaseName));
                    }
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
        manager.incrementCreateDatabase();
        manager.execute(new QueryAdapter("COMMIT"));
        globalState.setSchema(PostgresSchema.fromConnection(con, databaseName));

        manager.execute(new QueryAdapter("SET SESSION statement_timeout = 5000;\n"));

        List<TestOracle> oracles = globalState.getDmbsSpecificOptions().oracle.stream().map(o -> {
            try {
                return o.create(globalState);
            } catch (SQLException e1) {
                throw new AssertionError(e1);
            }
        }).collect(Collectors.toList());
        CompositeTestOracle oracle = new CompositeTestOracle(oracles);

        for (int i = 0; i < options.getNrQueries(); i++) {
            try {
                oracle.check();
            } catch (IgnoreMeException e) {
                continue;
            }
            manager.incrementSelectQueryCount();
        }

    }

    @Override
    public Connection createDatabase(GlobalState<?> globalState) throws SQLException {
        // FYI: commented out original
        // String url = "jdbc:postgresql://localhost:5432/test";
        // TODO: make port and initial user and database name optional with default as hard-coded
        String url = "jdbc:postgresql://localhost:9700/postgres";
        String databaseName = globalState.getDatabaseName();
        // FYI: commented out original
        // Connection con = DriverManager.getConnection(url, globalState.getOptions().getUserName(),
                // globalState.getOptions().getPassword());
        Connection con = DriverManager.getConnection(url, "v-naugur",
                "v-naugur");
        // FYI: commented out original
        // globalState.getState().statements.add(new QueryAdapter("\\c test;"));
        globalState.getState().statements.add(new QueryAdapter("\\c postgres;"));
        globalState.getState().statements.add(new QueryAdapter("SELECT * FROM master_get_active_worker_nodes()"));
        globalState.getState().statements.add(new QueryAdapter("DROP DATABASE IF EXISTS " + databaseName));
        String createDatabaseCommand = getCreateDatabaseCommand(databaseName, con);
        globalState.getState().statements.add(new QueryAdapter(createDatabaseCommand));
        // FIXME: why do some executed statements have ; and some don't?
        List<WorkerNode> worker_nodes = new ArrayList<>();
        // get info about all servers hosting worker nodes
        try (Statement s = con.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT * FROM master_get_active_worker_nodes();");
            while (rs.next()) {
                String node_name = rs.getString("node_name");
                int node_port = rs.getInt("node_port");
                WorkerNode w = new WorkerNode(node_name, node_port);
                worker_nodes.add(w);
            }
        }
        try (Statement s = con.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + databaseName);
        }
        try (Statement s = con.createStatement()) {
            s.execute(createDatabaseCommand);
        }
        con.close();
        for (int i = 0; i < worker_nodes.size(); i++) {
            WorkerNode w = worker_nodes.get(i);
            // TODO: find a way to add port change to log (since port change can't be done from inside postgres)
            // create database with given databaseName at each server hosting worker node
            con = DriverManager.getConnection("jdbc:postgresql://localhost:" + w.get_port() + "/postgres",
                "v-naugur", "v-naugur");
            globalState.getState().statements.add(new QueryAdapter("DROP DATABASE IF EXISTS " + databaseName));
            createDatabaseCommand = getCreateDatabaseCommand(databaseName, con);
            globalState.getState().statements.add(new QueryAdapter(createDatabaseCommand));
            try (Statement s = con.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS " + databaseName);
            }
            try (Statement s = con.createStatement()) {
                s.execute(createDatabaseCommand);
            }
            con.close();
            // TODO: find a way to add port change to log (since port change can't be done from inside postgres)
            // FYI: commented out original
            // con = DriverManager.getConnection("jdbc:postgresql://localhost:" + w.get_port() + "/" + databaseName,
                // globalState.getOptions().getUserName(), globalState.getOptions().getPassword()); 
            // add citus extension to database with given databaseName at each server hosting worker nodes
            con = DriverManager.getConnection("jdbc:postgresql://localhost:" + w.get_port() + "/" + databaseName,
                "v-naugur", "v-naugur"); 
            globalState.getState().statements.add(new QueryAdapter("CREATE EXTENSION citus;"));
            try (Statement s = con.createStatement()) {
                s.execute("CREATE EXTENSION citus;");
            }
            // TODO: find a way to add port change to log (since port change can't be done from inside postgres)
            con.close();
        }
        globalState.getState().statements.add(new QueryAdapter("\\c " + databaseName));
        globalState.getState().statements.add(new QueryAdapter("CREATE EXTENSION citus;"));
        // FYI: commented out original
        // con = DriverManager.getConnection("jdbc:postgresql://localhost:5432/" + databaseName,
        con = DriverManager.getConnection("jdbc:postgresql://localhost:9700/" + databaseName,
                "v-naugur", "v-naugur");
        // add citus extension to database with given databaseName at server hosting coordinator node
        try (Statement s = con.createStatement()) {
            s.execute("CREATE EXTENSION citus;");
        }
        // add all servers hosting worker nodes as worker nodes to coordinator node for database with given databaseName
        for (int i = 0; i < worker_nodes.size(); i++) {
            WorkerNode w = worker_nodes.get(i);
            globalState.getState().statements.add(new QueryAdapter("SELECT * from master_add_node('" + w.get_name() + "', " + w.get_port() + ");"));
            try (Statement s = con.createStatement()) {
                s.execute("SELECT * from master_add_node('" + w.get_name() + "', " + w.get_port() + ");");
            }
        }
        List<String> statements = Arrays.asList(
                // "CREATE EXTENSION IF NOT EXISTS btree_gin;",
                // "CREATE EXTENSION IF NOT EXISTS btree_gist;", // TODO: undefined symbol: elog_start
                // FYI: commented out original
                // "CREATE EXTENSION IF NOT EXISTS pg_prewarm;", "SET max_parallel_workers_per_gather=16");
                "CREATE EXTENSION IF NOT EXISTS pg_prewarm;", "SET max_parallel_workers_per_gather=0");
                // TODO: ^^ make max parallel workers optional with default 0 for citus
        for (String s : statements) {
            QueryAdapter query = new QueryAdapter(s);
            globalState.getState().statements.add(query);
            query.execute(con);
        }
        // new QueryAdapter("set jit_above_cost = 0; set jit_inline_above_cost = 0; set jit_optimize_above_cost =
        // 0;").execute(con);
        return con;
    }

    private String getCreateDatabaseCommand(String databaseName, Connection con) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE DATABASE " + databaseName + " ");
        if (Randomly.getBoolean()) {
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

    @Override
    public PostgresGlobalState generateGlobalState() {
        return new PostgresGlobalState();
    }

    @Override
    public PostgresOptions getCommand() {
        return new PostgresOptions();
    }

}
