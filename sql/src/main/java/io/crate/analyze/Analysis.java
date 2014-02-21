package io.crate.analyze;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import io.crate.metadata.*;
import io.crate.metadata.table.TableInfo;
import io.crate.planner.RowGranularity;
import io.crate.planner.symbol.*;
import org.apache.lucene.util.BytesRef;
import org.cratedb.DataType;
import org.cratedb.sql.TableUnknownException;
import org.cratedb.sql.ValidationException;
import org.elasticsearch.common.Preconditions;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Holds information the analyzer has gathered about a statement.
 */
public abstract class Analysis {

    protected final EvaluatingNormalizer normalizer;

    public static enum Type {
        SELECT,
        INSERT,
        UPDATE
    }

    public abstract Type type();

    protected final ReferenceInfos referenceInfos;
    private final Functions functions;
    protected final Object[] parameters;
    protected TableInfo table;

    private Map<Function, Function> functionSymbols = new HashMap<>();

    protected Map<ReferenceIdent, Reference> referenceSymbols = new HashMap<>();

    protected List<String> outputNames = ImmutableList.of();
    protected List<Symbol> outputSymbols = ImmutableList.of();

    protected List<Literal> primaryKeyLiterals;
    protected Literal clusteredByLiteral;

    private boolean isDelete = false;
    protected WhereClause whereClause = WhereClause.MATCH_ALL;
    protected RowGranularity rowGranularity;
    protected boolean hasAggregates = false;

    protected Long version;

    public List<Literal> primaryKeyLiterals() {
        return primaryKeyLiterals;
    }

    public void primaryKeyLiterals(List<Literal> primaryKeyLiterals) {
        this.primaryKeyLiterals = primaryKeyLiterals;
    }

    @Nullable
    public Literal clusteredByLiteral() {
        return clusteredByLiteral;
    }

    public void clusteredByLiteral(Literal clusteredByLiteral) {
        this.clusteredByLiteral = clusteredByLiteral;
    }

    public Analysis(ReferenceInfos referenceInfos, Functions functions,
                    Object[] parameters,
                    ReferenceResolver referenceResolver) {
        this.referenceInfos = referenceInfos;
        this.functions = functions;
        this.parameters = parameters;
        this.normalizer = new EvaluatingNormalizer(functions, RowGranularity.CLUSTER, referenceResolver);
    }

    public void table(TableIdent tableIdent) {
        TableInfo tableInfo = referenceInfos.getTableInfo(tableIdent);
        if (tableInfo == null) {
            throw new TableUnknownException(tableIdent.name());
        }
        table = tableInfo;
        updateRowGranularity(table.rowGranularity());
    }

    public TableInfo table() {
        return this.table;
    }

    public void version(Long version) {
        this.version = version;
    }

    public Optional<Long> version() {
        return Optional.fromNullable(this.version);
    }

    private Reference allocateReference(ReferenceIdent ident, boolean unique) {
        Reference reference = referenceSymbols.get(ident);
        if (reference == null) {
            ReferenceInfo info = getReferenceInfo(ident);
            if (info == null) {
                reference = table.getDynamic(ident.columnIdent());
                info = reference.info();
            } else {
                reference = new Reference(info);
            }
            referenceSymbols.put(info.ident(), reference);
        } else if (unique) {
            throw new IllegalArgumentException(String.format("reference '%s' repeated", ident.columnIdent().fqn()));
        }
        updateRowGranularity(reference.info().granularity());
        return reference;
    }

    /**
     * add a reference for the given ident, get from map-cache if already
     *
     * @param ident
     * @return
     */
    public Reference allocateReference(ReferenceIdent ident) {
        return allocateReference(ident, false);
    }

    /**
     * add a new reference for the given ident
     * and throw an error if this ident has already been added
     */
    public Reference allocateUniqueReference(ReferenceIdent ident) {
        return allocateReference(ident, true);
    }

    @Nullable
    public ReferenceInfo getReferenceInfo(ReferenceIdent ident) {
        return referenceInfos.getReferenceInfo(ident);
    }

    public FunctionInfo getFunctionInfo(FunctionIdent ident) {
        FunctionImplementation implementation = functions.get(ident);
        if (implementation == null) {
            throw new UnsupportedOperationException("TODO: unknown function? " + ident.toString());
        }
        return implementation.info();
    }

    public Collection<Reference> references() {
        return referenceSymbols.values();
    }

    public Collection<Function> functions() {
        return functionSymbols.values();
    }

    public Function allocateFunction(FunctionInfo info, List<Symbol> arguments) {
        if (info.isAggregate()) {
            hasAggregates = true;
        }
        Function function = new Function(info, arguments);
        Function existing = functionSymbols.get(function);
        if (existing != null) {
            return existing;
        } else {
            functionSymbols.put(function, function);
        }
        return function;
    }

    /**
     * Indicates that the statement will not match, so that there is no need to execute it
     */
    public boolean noMatch() {
        return whereClause().noMatch();
    }

    public WhereClause whereClause(WhereClause whereClause) {
        this.whereClause = whereClause.normalize(normalizer);
        return this.whereClause;
    }

    public WhereClause whereClause(Symbol whereClause) {
        this.whereClause = new WhereClause(normalizer.process(whereClause, null));
        return this.whereClause;
    }

    public WhereClause whereClause() {
        return whereClause;
    }

    /**
     * Updates the row granularity of this query if it is higher than the current row granularity.
     *
     * @param granularity the row granularity as seen by a reference
     * @return
     */
    protected RowGranularity updateRowGranularity(RowGranularity granularity) {
        if (rowGranularity == null || rowGranularity.ordinal() < granularity.ordinal()) {
            rowGranularity = granularity;
        }
        return rowGranularity;
    }

    public RowGranularity rowGranularity() {
        return rowGranularity;
    }

    public Object[] parameters() {
        return parameters;
    }

    public Object parameterAt(int idx) {
        Preconditions.checkElementIndex(idx, parameters.length);
        return parameters[idx];
    }

    public void addOutputName(String s) {
        this.outputNames.add(s);
    }

    public void outputNames(List<String> outputNames) {
        this.outputNames = outputNames;
    }

    public List<String> outputNames() {
        return outputNames;
    }

    public List<Symbol> outputSymbols() {
        return outputSymbols;
    }

    public void outputSymbols(List<Symbol> symbols) {
        this.outputSymbols = symbols;
    }

    public boolean isDelete() {
        return isDelete;
    }

    public void isDelete(boolean isDelete) {
        this.isDelete = isDelete;
    }

    /**
     * normalize and validate given value according to the corresponding {@link io.crate.planner.symbol.Reference}
     *
     * @param inputValue the value to normalize, might be anything from {@link io.crate.metadata.Scalar} to {@link io.crate.planner.symbol.Literal}
     * @param reference  the reference to which the value has to comply in terms of type-compatibility
     * @return the normalized Symbol, should be a literal
     * @throws org.cratedb.sql.ValidationException
     */
    public Literal normalizeInputValue(Symbol inputValue, Reference reference) {
        Literal normalized;

        // 1. evaluate
        try {
            // everything that is allowed for input should evaluate to Literal
            normalized = (Literal) normalizer.process(inputValue, null);
        } catch (ClassCastException e) {
            throw new ValidationException(
                    reference.info().ident().columnIdent().name(),
                    String.format("Invalid value of type '%s'", inputValue.symbolType().name()));
        }

        if (reference instanceof DynamicReference) {
            // guess type for dynamic column
            DataType type = DataType.forValue(normalized.value(),
                    reference.info().objectType() == ReferenceInfo.ObjectType.IGNORED);
            ((DynamicReference) reference).valueType(type);
        }

        // 2. convert if necessary (detect wrong types)
        try {
            normalized = normalized.convertTo(reference.info().type());
        } catch (Exception e) {  // UnsupportedOperationException, NumberFormatException ...
            throw new ValidationException(
                    reference.info().ident().columnIdent().name(),
                    String.format("wrong type '%s'. expected: '%s'",
                            normalized.valueType().getName(),
                            reference.info().type().getName()));
        }

        // 3. if reference is of type object - do special validation
        if (reference.info().type() == DataType.OBJECT
                && normalized instanceof ObjectLiteral) {
            Map<String, Object> value = ((ObjectLiteral) normalized).value();
            if (value == null) {
                return Null.INSTANCE;
            }
            normalized = new ObjectLiteral(normalizeObjectValue(value, reference.info()));
        }

        return normalized;
    }


    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeObjectValue(Map<String, Object> value, ReferenceInfo info) {
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            ColumnIdent nestedIdent = ColumnIdent.getChild(info.ident().columnIdent(), entry.getKey());
            ReferenceInfo nestedInfo = table.getColumnInfo(nestedIdent);
            if (nestedInfo == null) {
                if (info.objectType() == ReferenceInfo.ObjectType.IGNORED) {
                    continue;
                }
                DynamicReference dynamicReference = table.getDynamic(nestedIdent);
                DataType type = DataType.forValue(entry.getValue(), false);
                if (type == null) {
                    throw new ValidationException(info.ident().columnIdent().fqn(), "Invalid value");
                }
                dynamicReference.valueType(type);
                nestedInfo = dynamicReference.info();
            } else {
                if (entry.getValue() == null) {
                    continue;
                }
            }
            if (info.type() == DataType.OBJECT && entry.getValue() instanceof Map) {
                value.put(entry.getKey(), normalizeObjectValue((Map<String, Object>) entry.getValue(), nestedInfo));
            } else {
                value.put(entry.getKey(), normalizePrimitiveValue(entry.getValue(), nestedInfo));
            }
        }
        return value;
    }

    private Object normalizePrimitiveValue(Object primitiveValue, ReferenceInfo info) {
        try {
            // try to convert to correctly typed literal
            Literal l = Literal.forValue(primitiveValue);
            Object primitiveNormalized = l.convertTo(info.type()).value();
            if (primitiveNormalized instanceof BytesRef) {
                // no BytesRefs in maps
                primitiveNormalized = ((BytesRef) primitiveNormalized).utf8ToString();
            }
            return primitiveNormalized;
        } catch (Exception e) {
            throw new ValidationException(info.ident().columnIdent().fqn(),
                    String.format("Invalid %s",
                            info.type().getName()
                    )
            );
        }
    }

    public void normalize() {
        for (int i = 0; i < outputSymbols().size(); i++) {
            outputSymbols.set(i, normalizer.normalize(outputSymbols.get(i)));
        }
        if (whereClause().hasQuery()) {
            whereClause.normalize(normalizer);
        }
    }
}
