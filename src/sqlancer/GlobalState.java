package sqlancer;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.util.List;

import sqlancer.Main.QueryManager;
import sqlancer.Main.StateLogger;

/**
 * Represents a global state that is valid for a testing session on a given database.
 *
 * @param <O>
 *            the option parameter.
 */
public abstract class GlobalState<O, S> {

    private Connection con;
    private Randomly r;
    private MainOptions options;
    private O dmbsSpecificOptions;
    private S schema;
    private StateLogger logger;
    private StateToReproduce state;
    private QueryManager manager;
    private String databaseName;

    public void setConnection(Connection con) {
        this.con = con;
    }

    @SuppressWarnings("unchecked")
    public void setDmbsSpecificOptions(Object dmbsSpecificOptions) {
        this.dmbsSpecificOptions = (O) dmbsSpecificOptions;
    }

    public O getDmbsSpecificOptions() {
        return dmbsSpecificOptions;
    }

    public Connection getConnection() {
        return con;
    }

    public void setRandomly(Randomly r) {
        this.r = r;
    }

    public Randomly getRandomly() {
        return r;
    }

    public MainOptions getOptions() {
        return options;
    }

    public void setMainOptions(MainOptions options) {
        this.options = options;
    }

    public void setStateLogger(StateLogger logger) {
        this.logger = logger;
    }

    public StateLogger getLogger() {
        return logger;
    }

    public void setState(StateToReproduce state) {
        this.state = state;
    }

    public StateToReproduce getState() {
        return state;
    }

    public QueryManager getManager() {
        return manager;
    }

    public void setManager(QueryManager manager) {
        this.manager = manager;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public boolean executeStatement(Query q) throws SQLException {
        boolean logExecutionTime = getOptions().logExecutionTime();
        ExecutionTimer timer = null;
        if (logExecutionTime) {
            timer = new ExecutionTimer().start();
        }
        if (getOptions().printAllStatements()) {
            System.out.println(q.getQueryString());
        }
        if (getOptions().logEachSelect()) {
            if (logExecutionTime) {
                getLogger().writeCurrentNoLineBreak(q.getQueryString());
            } else {
                getLogger().writeCurrent(q.getQueryString());
            }
        }
        boolean success = manager.execute(q);
        if (success && getOptions().printSucceedingStatements()) {
            System.out.println(q.getQueryString());
        }
        if (logExecutionTime) {
            getLogger().writeCurrent(" -- " + timer.end().asString());
        }
        if (q.couldAffectSchema()) {
            updateSchema();
        }
        return success;
    }

    public ResultSet executeStatementAndGet(Query q) throws SQLException {
        boolean logExecutionTime = getOptions().logExecutionTime();
        ExecutionTimer timer = null;
        if (logExecutionTime) {
            timer = new ExecutionTimer().start();
        }
        if (getOptions().printAllStatements()) {
            System.out.println(q.getQueryString());
        }
        if (getOptions().logEachSelect()) {
            if (logExecutionTime) {
                getLogger().writeCurrentNoLineBreak(q.getQueryString());
            } else {
                getLogger().writeCurrent(q.getQueryString());
            }
        }
        ResultSet result = manager.executeAndGet(q);
        if ((result != null) && getOptions().printSucceedingStatements()) {
            System.out.println(q.getQueryString());
        }
        if (logExecutionTime) {
            getLogger().writeCurrent(" -- " + timer.end().asString());
        }
        if (q.couldAffectSchema()) {
            updateSchema();
        }
        return result;
    }
    
    public boolean fillAndExecuteStatement(Query q, String template, List<String> fills) throws SQLException {
        boolean logExecutionTime = getOptions().logExecutionTime();
        ExecutionTimer timer = null;
        if (logExecutionTime) {
            timer = new ExecutionTimer().start();
        }
        if (getOptions().printAllStatements()) {
            System.out.println(q.getQueryString());
        }
        if (getOptions().logEachSelect()) {
            if (logExecutionTime) {
                getLogger().writeCurrentNoLineBreak(q.getQueryString());
            } else {
                getLogger().writeCurrent(q.getQueryString());
            }
        }
        boolean success = manager.fillAndExecute(q, template, fills);
        if (success && getOptions().printSucceedingStatements()) {
            System.out.println(q.getQueryString());
        }
        if (logExecutionTime) {
            getLogger().writeCurrent(" -- " + timer.end().asString());
        }
        if (q.couldAffectSchema()) {
            updateSchema();
        }
        return success;
    }

    public ResultSet fillAndExecuteStatementAndGet(Query q, String template, List<String> fills) throws SQLException {
        boolean logExecutionTime = getOptions().logExecutionTime();
        ExecutionTimer timer = null;
        if (logExecutionTime) {
            timer = new ExecutionTimer().start();
        }
        if (getOptions().printAllStatements()) {
            System.out.println(q.getQueryString());
        }
        if (getOptions().logEachSelect()) {
            if (logExecutionTime) {
                getLogger().writeCurrentNoLineBreak(q.getQueryString());
            } else {
                getLogger().writeCurrent(q.getQueryString());
            }
        }
        ResultSet result = manager.fillAndExecuteAndGet(q, template, fills);
        if ((result != null) && getOptions().printSucceedingStatements()) {
            System.out.println(q.getQueryString());
        }
        if (logExecutionTime) {
            getLogger().writeCurrent(" -- " + timer.end().asString());
        }
        if (q.couldAffectSchema()) {
            updateSchema();
        }
        return result;
    }

    public S getSchema() {
        if (schema == null) {
            try {
                updateSchema();
            } catch (SQLException e) {
                throw new AssertionError();
            }
        }
        return schema;
    }

    protected void setSchema(S schema) {
        this.schema = schema;
    }

    protected abstract void updateSchema() throws SQLException;

}
