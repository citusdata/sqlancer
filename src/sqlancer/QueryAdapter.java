package sqlancer;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class QueryAdapter extends Query {

    private final String query;
    private final Collection<String> expectedErrors;
    private final boolean couldAffectSchema;

    public QueryAdapter(String query) {
        this(query, new ArrayList<>());
    }

    public QueryAdapter(String query, boolean couldAffectSchema) {
        this(query, new ArrayList<>(), couldAffectSchema);
    }

    public QueryAdapter(String query, Collection<String> expectedErrors) {
        this.query = canonicalizeString(query);
        this.expectedErrors = expectedErrors;
        this.couldAffectSchema = false;
        checkQueryString();
    }

    public QueryAdapter(String query, Collection<String> expectedErrors, boolean couldAffectSchema) {
        this.query = canonicalizeString(query);
        this.expectedErrors = expectedErrors;
        this.couldAffectSchema = couldAffectSchema;
        checkQueryString();
    }

    private String canonicalizeString(String s) {
        if (s.endsWith(";")) {
            return s;
        } else if (!s.contains("--")) {
            return s + ";";
        } else {
            // query contains a comment
            return s;
        }
    }

    private void checkQueryString() {
        if (query.contains("CREATE TABLE") && !couldAffectSchema) {
            throw new AssertionError("CREATE TABLE statements should set couldAffectSchema to true");
        }
    }

    @Override
    public String getQueryString() {
        return query;
    }

    @Override
    public boolean execute(GlobalState<?, ?> globalState) throws SQLException {
        try (Statement s = globalState.getConnection().createStatement()) {
            s.execute(query);
            Main.nrSuccessfulActions.addAndGet(1);
            return true;
        } catch (Exception e) {
            Main.nrUnsuccessfulActions.addAndGet(1);
            checkException(e);
            return false;
        }
    }

    public void checkException(Exception e) throws AssertionError {
        boolean isExcluded = false;
        for (String expectedError : expectedErrors) {
            if (e.getMessage().contains(expectedError)) {
                isExcluded = true;
                break;
            }
        }
        if (!isExcluded) {
            throw new AssertionError(query, e);
        }
    }

    @Override
    public ResultSet executeAndGet(GlobalState<?, ?> globalState) throws SQLException {
        Statement s = globalState.getConnection().createStatement();
        ResultSet result = null;
        try {
            result = s.executeQuery(query);
            Main.nrSuccessfulActions.addAndGet(1);
            return result;
        } catch (Exception e) {
            s.close();
            boolean isExcluded = false;
            Main.nrUnsuccessfulActions.addAndGet(1);
            for (String expectedError : expectedErrors) {
                if (e.getMessage().contains(expectedError)) {
                    isExcluded = true;
                    break;
                }
            }
            if (!isExcluded) {
                throw e;
            }
        }
        return null;
    }

    @Override
    public boolean couldAffectSchema() {
        return couldAffectSchema;
    }

    @Override
    public Collection<String> getExpectedErrors() {
        return expectedErrors;
    }

    @Override
    public boolean fillAndExecute(GlobalState<?, ?> globalState, String template, List<String> fills) throws SQLException {
        try (PreparedStatement s = globalState.getConnection().prepareStatement(template)) {
            for (int i = 1; i < fills.size() + 1; i++) {
                s.setString(i, fills.get(i - 1));
            }
            s.execute();
            Main.nrSuccessfulActions.addAndGet(1);
            return true;
        } catch (Exception e) {
            Main.nrUnsuccessfulActions.addAndGet(1);
            checkException(e);
            return false;
        }
    }

    @Override
    public ResultSet fillAndExecuteAndGet(GlobalState<?, ?> globalState, String template, List<String> fills) throws SQLException {
        ResultSet result = null;
        PreparedStatement s = globalState.getConnection().prepareStatement(template);
        try {
            for (int i = 1; i < fills.size() + 1; i++) {
                s.setString(i, fills.get(i - 1));
            }
            result = s.executeQuery();
            Main.nrSuccessfulActions.addAndGet(1);
            return result;
        } catch (Exception e) {
            s.close();
            boolean isExcluded = false;
            Main.nrUnsuccessfulActions.addAndGet(1);
            for (String expectedError : expectedErrors) {
                if (e.getMessage().contains(expectedError)) {
                    isExcluded = true;
                    break;
                }
            }
            if (!isExcluded) {
                    throw e;
            }
        }
        return null;
    }

}
