package sqlancer.postgres.gen;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import sqlancer.IgnoreMeException;
import sqlancer.Query;
import sqlancer.QueryAdapter;
import sqlancer.Randomly;
import sqlancer.postgres.PostgresGlobalState;
import sqlancer.postgres.PostgresSchema.PostgresColumn;
import sqlancer.postgres.PostgresSchema.PostgresDataType;
import sqlancer.postgres.PostgresSchema.PostgresTable;
import sqlancer.postgres.PostgresVisitor;
import sqlancer.postgres.ast.PostgresExpression;

public final class PostgresUpdateGenerator {

    private PostgresUpdateGenerator() {
    }

    public static Query create(PostgresGlobalState globalState) {
        PostgresTable randomTable = globalState.getSchema().getRandomTable(t -> t.isInsertable());
        StringBuilder sb = new StringBuilder();
        sb.append("UPDATE ");
        sb.append(randomTable.getName());
        sb.append(" SET ");
        Set<String> errors = new HashSet<>(Arrays.asList("conflicting key value violates exclusion constraint",
                "reached maximum value of sequence", "violates foreign key constraint", "violates not-null constraint",
                "violates unique constraint", "out of range", "cannot cast", "must be type boolean", "is not unique",
                " bit string too long", "can only be updated to DEFAULT", "division by zero",
                "You might need to add explicit type casts.", "invalid regular expression",
                "View columns that are not columns of their base relation are not updatable"));
        errors.add("multiple assignments to same column"); // view whose columns refer to a column in the referenced
                                                           // table multiple times
        List<PostgresColumn> columns = randomTable.getRandomNonEmptyColumnSubset();
        PostgresCommon.addCommonInsertUpdateErrors(errors);

        // UPDATE must not include partition column if table is distributed
        if (randomTable.getDistributionColumn() != null) {
            // remove column whose name equals to name of distribution column
            columns.removeIf(c -> (c.getName()).equals(randomTable.getDistributionColumn().getName()));
        }
        if (columns.isEmpty()) {
            throw new IgnoreMeException();
        }
        for (int i = 0; i < columns.size(); i++) {
            if (i != 0) {
                sb.append(", ");
            }
            PostgresColumn column = columns.get(i);
            sb.append(column.getName());
            sb.append(" = ");
            if (!Randomly.getBoolean()) {
                PostgresExpression constant = PostgresExpressionGenerator.generateConstant(globalState.getRandomly(),
                        column.getType());
                sb.append(PostgresVisitor.asString(constant));
            } else if (Randomly.getBoolean()) {
                sb.append("DEFAULT");
            } else {
                sb.append("(");
                PostgresExpression expr = PostgresExpressionGenerator.generateExpression(globalState,
                        randomTable.getColumns(), column.getType());
                // caused by casts
                sb.append(PostgresVisitor.asString(expr));
                sb.append(")");
            }
        }
        errors.add("invalid input syntax for ");
        errors.add("operator does not exist: text = boolean");
        errors.add("violates check constraint");
        errors.add("could not determine which collation to use for string comparison");
        errors.add("but expression is of type");
        PostgresCommon.addCommonExpressionErrors(errors);
        if (!Randomly.getBooleanWithSmallProbability()) {
            sb.append(" WHERE ");
            // functions used in UPDATE queries on distributed tables must not be VOLATILE
            // STABLE functions used in UPDATE queries cannot be called with column references
            globalState.setAllowedFunctionTypes(Arrays.asList('i'));
            // TODO: new errors?
            errors.add("must not be VOLATILE");
            errors.add("STABLE functions");
            PostgresExpression where = PostgresExpressionGenerator.generateExpression(globalState,
                    randomTable.getColumns(), PostgresDataType.BOOLEAN);
            globalState.setDefaultAllowedFunctionTypes();
            sb.append(PostgresVisitor.asString(where));
        }

        return new QueryAdapter(sb.toString(), errors, true);
    }

}
