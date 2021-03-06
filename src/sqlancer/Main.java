package sqlancer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.JCommander.Builder;

import sqlancer.clickhouse.ClickHouseProvider;
import sqlancer.cockroachdb.CockroachDBProvider;
import sqlancer.duckdb.DuckDBProvider;
import sqlancer.mariadb.MariaDBProvider;
import sqlancer.mysql.MySQLProvider;
import sqlancer.postgres.PostgresProvider;
import sqlancer.sqlite3.SQLite3Provider;
import sqlancer.tidb.TiDBProvider;

public final class Main {

    public static final File LOG_DIRECTORY = new File("logs");
    public static volatile AtomicLong nrQueries = new AtomicLong();
    public static volatile AtomicLong nrDatabases = new AtomicLong();
    public static volatile AtomicLong nrSuccessfulActions = new AtomicLong();
    public static volatile AtomicLong nrUnsuccessfulActions = new AtomicLong();
    static int threadsShutdown;

    static {
        System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "ERROR");
        if (!LOG_DIRECTORY.exists()) {
            LOG_DIRECTORY.mkdir();
        }
    }

    private Main() {
    }

    public static final class StateLogger {

        private final File loggerFile;
        private File curFile;
        private FileWriter logFileWriter;
        public FileWriter currentFileWriter;
        private static final List<String> INITIALIZED_PROVIDER_NAMES = new ArrayList<>();
        private final boolean logEachSelect;
        private final DatabaseProvider<?, ?> provider;

        private static final class AlsoWriteToConsoleFileWriter extends FileWriter {

            AlsoWriteToConsoleFileWriter(File file) throws IOException {
                super(file);
            }

            @Override
            public Writer append(CharSequence arg0) throws IOException {
                System.err.println(arg0);
                return super.append(arg0);
            }

            @Override
            public void write(String str) throws IOException {
                System.err.println(str);
                super.write(str);
            }
        }

        public StateLogger(String databaseName, DatabaseProvider<?, ?> provider, MainOptions options) {
            this.provider = provider;
            File dir = new File(LOG_DIRECTORY, provider.getDBMSName());
            if (dir.exists() && !dir.isDirectory()) {
                throw new AssertionError(dir);
            }
            ensureExistsAndIsEmpty(dir, provider);
            loggerFile = new File(dir, databaseName + ".log");
            logEachSelect = options.logEachSelect();
            if (logEachSelect) {
                curFile = new File(dir, databaseName + "-cur.log");
            }
        }

        private synchronized void ensureExistsAndIsEmpty(File dir, DatabaseProvider<?, ?> provider) {
            if (INITIALIZED_PROVIDER_NAMES.contains(provider.getDBMSName())) {
                return;
            }
            if (!dir.exists()) {
                try {
                    Files.createDirectories(dir.toPath());
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
            File[] listFiles = dir.listFiles();
            assert listFiles != null : "directory was just created, so it should exist";
            for (File file : listFiles) {
                if (!file.isDirectory()) {
                    file.delete();
                }
            }
            INITIALIZED_PROVIDER_NAMES.add(provider.getDBMSName());
        }

        private FileWriter getLogFileWriter() {
            if (logFileWriter == null) {
                try {
                    logFileWriter = new AlsoWriteToConsoleFileWriter(loggerFile);
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
            return logFileWriter;
        }

        public FileWriter getCurrentFileWriter() {
            if (!logEachSelect) {
                throw new UnsupportedOperationException();
            }
            if (currentFileWriter == null) {
                try {
                    currentFileWriter = new FileWriter(curFile, false);
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
            return currentFileWriter;
        }

        public void writeCurrent(StateToReproduce state) {
            if (!logEachSelect) {
                throw new UnsupportedOperationException();
            }
            printState(getCurrentFileWriter(), state);
            try {
                currentFileWriter.flush();

            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void writeCurrent(String queryString) {
            if (!logEachSelect) {
                throw new UnsupportedOperationException();
            }
            try {
                getCurrentFileWriter().write(queryString + ";\n");
                currentFileWriter.flush();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        public void logRowNotFound(StateToReproduce state) {
            printState(getLogFileWriter(), state);
            try {
                getLogFileWriter().flush();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        public void logException(Throwable reduce, StateToReproduce state) {
            String stackTrace = getStackTrace(reduce);
            FileWriter logFileWriter2 = getLogFileWriter();
            try {
                logFileWriter2.write(stackTrace);
                printState(logFileWriter2, state);
            } catch (IOException e) {
                throw new AssertionError(e);
            } finally {
                try {
                    logFileWriter2.flush();
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }

        private String getStackTrace(Throwable e1) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e1.printStackTrace(pw);
            return "--" + sw.toString().replace("\n", "\n--");
        }

        private void printState(FileWriter writer, StateToReproduce state) {
            StringBuilder sb = new StringBuilder();
            DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
            Date date = new Date();
            sb.append("-- Time: " + dateFormat.format(date) + "\n");
            sb.append("-- Database: " + state.getDatabaseName() + "\n");
            sb.append("-- Database version: " + state.getDatabaseVersion() + "\n");
            sb.append("-- seed value: " + state.getSeedValue() + "\n");
            for (Query s : state.getStatements()) {
                if (s.getQueryString().endsWith(";")) {
                    sb.append(s.getQueryString());
                } else {
                    sb.append(s.getQueryString() + ";");
                }
                sb.append('\n');
            }
            if (state.getQueryString() != null) {
                sb.append(state.getQueryString() + ";\n");
            }
            try {
                writer.write(sb.toString());
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            provider.printDatabaseSpecificState(writer, state);
        }

    }

    public static class QueryManager {

        private final GlobalState<?> globalState;

        QueryManager(GlobalState<?> globalState) {
            this.globalState = globalState;
        }

        public boolean execute(Query q) throws SQLException {
            globalState.getState().statements.add(q);
            boolean success = q.execute(globalState);
            Main.nrSuccessfulActions.addAndGet(1);
            return success;
        }

        public void incrementSelectQueryCount() {
            Main.nrQueries.addAndGet(1);
        }

        public void incrementCreateDatabase() {
            Main.nrDatabases.addAndGet(1);
        }

    }

    public static void printArray(Object... arr) {
        for (Object o : arr) {
            System.out.println(o);
        }
    }

    public static void main(String[] args) {
        System.exit(executeMain(args));
    }

    public static class DBMSExecutor<G extends GlobalState<O>, O> {

        private final DatabaseProvider<G, O> provider;
        private final MainOptions options;
        private final O command;
        private final String databaseName;
        private final long seed;
        private StateLogger logger;
        private StateToReproduce stateToRepro;

        public DBMSExecutor(DatabaseProvider<G, O> provider, MainOptions options, O dbmsSpecificOptions,
                String databaseName, long seed) {
            this.provider = provider;
            this.options = options;
            this.databaseName = databaseName;
            this.seed = seed;
            this.command = dbmsSpecificOptions;
        }

        private G createGlobalState() {
            try {
                return provider.getGlobalStateClass().getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        public O getCommand() {
            return command;
        }

        public void run() throws SQLException {
            G state = createGlobalState();
            stateToRepro = provider.getStateToReproduce(databaseName);
            stateToRepro.seedValue = seed;
            state.setState(stateToRepro);
            logger = new StateLogger(databaseName, provider, options);
            Randomly r = new Randomly(seed);
            state.setRandomly(r);
            state.setDatabaseName(databaseName);
            state.setMainOptions(options);
            state.setDmbsSpecificOptions(command);
            try (Connection con = provider.createDatabase(state)) {
                QueryManager manager = new QueryManager(state);
                try {
                    java.sql.DatabaseMetaData meta = con.getMetaData();
                    stateToRepro.databaseVersion = meta.getDatabaseProductVersion();
                } catch (SQLFeatureNotSupportedException e) {
                    // ignore
                }
                state.setConnection(con);
                state.setStateLogger(logger);
                state.setManager(manager);
                provider.generateAndTestDatabase(state);
            }
        }

        public StateLogger getLogger() {
            return logger;
        }

        public StateToReproduce getStateToReproduce() {
            return stateToRepro;
        }
    }

    public static class DBMSExecutorFactory<G extends GlobalState<O>, O> {

        private final DatabaseProvider<G, O> provider;
        private final MainOptions options;
        private final O command;

        public DBMSExecutorFactory(DatabaseProvider<G, O> provider, MainOptions options) {
            this.provider = provider;
            this.options = options;
            this.command = createCommand();
        }

        private O createCommand() {
            try {
                return provider.getOptionClass().getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        public O getCommand() {
            return command;
        }

        @SuppressWarnings("unchecked")
        public DBMSExecutor<G, O> getDBMSExecutor(String databaseName, long seed) {
            try {
                return new DBMSExecutor<G, O>(provider.getClass().getDeclaredConstructor().newInstance(), options,
                        command, databaseName, seed);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

    }

    public static int executeMain(String... args) throws AssertionError {
        List<DatabaseProvider<?, ?>> providers = getDBMSProviders();
        Map<String, DBMSExecutorFactory<?, ?>> nameToProvider = new HashMap<>();
        MainOptions options = new MainOptions();
        Builder commandBuilder = JCommander.newBuilder().addObject(options);
        for (DatabaseProvider<?, ?> provider : providers) {
            String name = provider.getDBMSName();
            if (!name.toLowerCase().equals(name)) {
                throw new AssertionError(name + " should be in lowercase!");
            }
            DBMSExecutorFactory<?, ?> executorFactory = new DBMSExecutorFactory<>(provider, options);
            commandBuilder = commandBuilder.addCommand(name, executorFactory.getCommand());
            nameToProvider.put(name, executorFactory);
        }
        JCommander jc = commandBuilder.programName("SQLancer").build();
        jc.parse(args);

        if (jc.getParsedCommand() == null) {
            jc.usage();
            return options.getErrorExitCode();
        }

        if (options.printProgressInformation()) {
            startProgressMonitor();
        }

        ExecutorService execService = Executors.newFixedThreadPool(options.getNumberConcurrentThreads());
        DBMSExecutorFactory<?, ?> executorFactory = nameToProvider.get(jc.getParsedCommand());
        for (int i = 0; i < options.getTotalNumberTries(); i++) {
            final String databaseName = "database" + i;
            final long seed;
            if (options.getRandomSeed() == -1) {
                seed = System.currentTimeMillis() + i;
            } else {
                seed = options.getRandomSeed() + i;
            }

            execService.execute(new Runnable() {

                @Override
                public void run() {
                    Thread.currentThread().setName(databaseName);
                    runThread(databaseName);
                }

                private void runThread(final String databaseName) {
                    while (true) {
                        DBMSExecutor<?, ?> executor = executorFactory.getDBMSExecutor(databaseName, seed);
                        try {
                            executor.run();
                        } catch (IgnoreMeException e) {
                            continue;
                        } catch (Throwable reduce) {
                            reduce.printStackTrace();
                            executor.getStateToReproduce().exception = reduce.getMessage();
                            executor.getLogger().logFileWriter = null;
                            executor.getLogger().logException(reduce, executor.getStateToReproduce());
                            threadsShutdown++;
                            break;
                        } finally {
                            try {
                                if (options.logEachSelect()) {
                                    if (executor.getLogger().currentFileWriter != null) {
                                        executor.getLogger().currentFileWriter.close();
                                    }
                                    executor.getLogger().currentFileWriter = null;
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            if (threadsShutdown == options.getTotalNumberTries()) {
                                execService.shutdown();
                            }
                        }
                    }
                }
            });
        }
        try {
            if (options.getTimeoutSeconds() == -1) {
                execService.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
            } else {
                execService.awaitTermination(options.getTimeoutSeconds(), TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return threadsShutdown == 0 ? 0 : options.getErrorExitCode();
    }

    static List<DatabaseProvider<?, ?>> getDBMSProviders() {
        List<DatabaseProvider<?, ?>> providers = new ArrayList<>();
        providers.add(new SQLite3Provider());
        providers.add(new CockroachDBProvider());
        providers.add(new MySQLProvider());
        providers.add(new MariaDBProvider());
        providers.add(new TiDBProvider());
        providers.add(new PostgresProvider());
        providers.add(new ClickHouseProvider());
        providers.add(new DuckDBProvider());
        return providers;
    }

    private static void startProgressMonitor() {
        final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(new Runnable() {

            private long timeMillis = System.currentTimeMillis();
            private long lastNrQueries;
            private long lastNrDbs;

            {
                timeMillis = System.currentTimeMillis();
            }

            @Override
            public void run() {
                long elapsedTimeMillis = System.currentTimeMillis() - timeMillis;
                long currentNrQueries = nrQueries.get();
                long nrCurrentQueries = currentNrQueries - lastNrQueries;
                double throughput = nrCurrentQueries / (elapsedTimeMillis / 1000d);
                long currentNrDbs = nrDatabases.get();
                long nrCurrentDbs = currentNrDbs - lastNrDbs;
                double throughputDbs = nrCurrentDbs / (elapsedTimeMillis / 1000d);
                long successfulStatementsRatio = (long) (100.0 * nrSuccessfulActions.get()
                        / (nrSuccessfulActions.get() + nrUnsuccessfulActions.get()));
                DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
                Date date = new Date();
                System.out.println(String.format(
                        "[%s] Executed %d queries (%d queries/s; %.2f/s dbs, successful statements: %2d%%). Threads shut down: %d.",
                        dateFormat.format(date), currentNrQueries, (int) throughput, throughputDbs,
                        successfulStatementsRatio, threadsShutdown));
                timeMillis = System.currentTimeMillis();
                lastNrQueries = currentNrQueries;
                lastNrDbs = currentNrDbs;
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

}
