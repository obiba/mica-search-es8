/*
 * Copyright (c) 2024 OBiBa. All rights reserved.
 *
 * This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.obiba.es.mica.query;

import co.elastic.clients.elasticsearch._types.query_dsl.TermRangeQuery;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import net.jazdw.rql.parser.ASTNode;
import net.jazdw.rql.parser.RQLParser;
import net.jazdw.rql.parser.SimpleASTVisitor;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.obiba.es.mica.ESQuery;
import org.obiba.mica.spi.search.rql.RQLFieldResolver;
import org.obiba.mica.spi.search.rql.RQLNode;
import org.obiba.mica.spi.search.support.AttributeKey;
import org.obiba.opal.core.domain.taxonomy.Taxonomy;
import org.obiba.opal.core.domain.taxonomy.TaxonomyEntity;
import org.obiba.opal.core.domain.taxonomy.Vocabulary;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.ExistsQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchAllQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.QueryStringQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TermsQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TermsQueryField;
import co.elastic.clients.elasticsearch._types.query_dsl.WildcardQuery;
import co.elastic.clients.json.JsonData;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

public class RQLQuery implements ESQuery {

  private final RQLFieldResolver rqlFieldResolver;

  private int from = 0;

  private int size = 0;

  private boolean withLimit = false;

  private ASTNode node;

  private Query queryBuilder;

  private List<SortBuilder> sortBuilders = Lists.newArrayList();

  private List<String> aggregations = Lists.newArrayList();

  private List<String> aggregationBuckets = Lists.newArrayList();

  private List<String> queryAggregationBuckets = Lists.newArrayList();

  private List<String> sourceFields = Lists.newArrayList();

  private final Map<String, Map<String, List<String>>> taxonomyTermsMap = Maps.newHashMap();

  private Query filterQuery;

  public RQLQuery(String rql) {
    this(new RQLParser(new RQLConverter()).parse(rql), new RQLFieldResolver(null, Collections.emptyList(), "en",
        null));
  }

  public RQLQuery(ASTNode node, RQLFieldResolver rqlFieldResolver) {
    this.rqlFieldResolver = rqlFieldResolver;
    parseNode(node);
  }

  @Override
  public boolean isEmpty() {
    return false;
  }

  @Override
  public boolean hasIdCriteria() {
    // TODO
    return false;
  }

  @Override
  public int getFrom() {
    return from;
  }

  @Override
  public int getSize() {
    return size;
  }

  @Override
  public boolean hasLimit() {
    return withLimit;
  }

  @Override
  public List<String> getSourceFields() {
    return sourceFields;
  }

  @Override
  public List<String> getAggregationBuckets() {
    return aggregationBuckets;
  }

  @Override
  public List<String> getQueryAggregationBuckets() {
    return queryAggregationBuckets;
  }

  @Override
  public void ensureAggregationBuckets(List<String> additionalAggregationBuckets) {
    aggregationBuckets.addAll(queryAggregationBuckets);
    for (String agg : additionalAggregationBuckets) {
      if (!aggregationBuckets.contains(agg))
        aggregationBuckets.add(agg);
    }
  }

  @Nullable
  @Override
  public List<String> getAggregations() {
    return aggregations;
  }

  @Override
  public Map<String, Map<String, List<String>>> getTaxonomyTermsMap() {
    return taxonomyTermsMap;
  }

  @Override
  public boolean hasQueryBuilder() {
    return queryBuilder != null;
  }

  @Override
  public Query getQueryBuilder() {
    return queryBuilder;
  }

  @Override
  public boolean hasSortBuilders() {
    return sortBuilders != null && !sortBuilders.isEmpty();
  }

  @Override
  public List<SortBuilder> getSortBuilders() {
    return sortBuilders;
  }

  //
  // Private methods
  //

  @VisibleForTesting
  ASTNode getNode() {
    return node;
  }

  private void parseNode(ASTNode node) {
    try {
      RQLNode type = RQLNode.getType(node.getName());
      switch (type) {
        case VARIABLE:
        case DATASET:
        case STUDY:
        case NETWORK:
        case GENERIC:
          node.getArguments().stream().map(a -> (ASTNode) a).forEach(n -> {
            switch (RQLNode.valueOf(n.getName().toUpperCase())) {
              case LIMIT:
                parseLimit(n);
                break;
              case SORT:
                parseSort(n);
                break;
              case AGGREGATE:
                parseAggregate(n);
                break;
              case SELECT:
              case FIELDS:
                parseFields(n);
                break;
              case FILTER: // for now will only have one argument
                if (n.getArgumentsSize() > 0 && n.getArgument(0) instanceof ASTNode) {
                  Object argument = n.getArgument(0);
                  parseFilterQuery((ASTNode) argument);
                }
                break;
              default:
                parseQuery(n);
            }
          });

          addFilterQueryIfPresent();
          break;
        default:
          parseQuery(node);
      }
    } catch (IllegalArgumentException e) {

    }
  }

  private void parseQuery(ASTNode node) {
    this.node = node;
    RQLQueryBuilder builder = new RQLQueryBuilder(rqlFieldResolver);
    queryBuilder = node.accept(builder);
  }

  private void parseFilterQuery(ASTNode node) {
    RQLQueryBuilder builder = new RQLQueryBuilder(rqlFieldResolver);
    filterQuery = node.accept(builder);
  }

  private void parseLimit(ASTNode node) {
    this.node = node;
    RQLLimitBuilder limit = new RQLLimitBuilder();
    boolean result = node.accept(limit);
    if (result) {
      withLimit = true;
      from = limit.getFrom();
      size = limit.getSize();
    }
  }

  private void parseSort(ASTNode node) {
    this.node = node;
    RQLSortBuilder sort = new RQLSortBuilder(rqlFieldResolver);
    sortBuilders = node.accept(sort);
  }

  private void parseAggregate(ASTNode node) {
    this.node = node;
    RQLAggregateBuilder aggregate = new RQLAggregateBuilder(rqlFieldResolver);
    if (node.accept(aggregate)) {
      aggregations = aggregate.getAggregations();
      queryAggregationBuckets = aggregate.getAggregationBuckets();
    }
  }

  private void parseFields(ASTNode node) {
    sourceFields = Lists.newArrayList();

    if (node.getArgumentsSize() > 0) {
      if (node.getArgument(0) instanceof ArrayList) {
        ArrayList<Object> fields = (ArrayList<Object>) node.getArgument(0);
        fields.stream().map(Object::toString).forEach(sourceFields::add);
      } else {
        node.getArguments().stream().map(Object::toString).forEach(sourceFields::add);
      }
    }
  }

  private void addFilterQueryIfPresent() {
    if (filterQuery != null) {
      queryBuilder = queryBuilder == null
          ? filterQuery
          : BoolQuery.of(q -> q.must(filterQuery, queryBuilder))._toQuery();
      filterQuery = null;
    }
  }

  //
  // Inner classes
  //

  private abstract class RQLBuilder<T> implements SimpleASTVisitor<T> {

    protected final RQLFieldResolver rqlFieldResolver;

    RQLBuilder(RQLFieldResolver rqlFieldResolver) {
      this.rqlFieldResolver = rqlFieldResolver;
    }

    protected RQLFieldResolver.FieldData resolveField(String rqlField) {
      return rqlFieldResolver.resolveField(rqlField);
    }

    protected RQLFieldResolver.FieldData resolveFieldUnanalyzed(String rqlField) {
      return rqlFieldResolver.resolveFieldUnanalyzed(rqlField);
    }

    protected Vocabulary getVocabulary(String taxonomyName, String vocabularyName) {
      Optional<Taxonomy> taxonomy = rqlFieldResolver.getTaxonomies().stream()
          .filter(t -> t.getName().equals(taxonomyName)).findFirst();
      if (taxonomy.isPresent() && taxonomy.get().hasVocabularies()) {
        Optional<Vocabulary> vocabulary = taxonomy.get().getVocabularies().stream()
            .filter(v -> v.getName().equals(vocabularyName)).findFirst();
        if (vocabulary.isPresent()) {
          return vocabulary.get();
        }
      }
      return null;
    }

  }

  private class RQLQueryBuilder extends RQLBuilder<Query> {
    RQLQueryBuilder(RQLFieldResolver rqlFieldResolver) {
      super(rqlFieldResolver);
    }

    @Override
    public Query visit(ASTNode node) {
      try {
        RQLNode type = RQLNode.getType(node.getName());
        switch (type) {
          case FILTER:
          case AND:
            return visitAnd(node);
          case NAND:
            return visitNand(node);
          case OR:
            return visitOr(node);
          case NOR:
            return visitNor(node);
          case CONTAINS:
            return visitContains(node);
          case IN:
            return visitIn(node);
          case OUT:
            return visitOut(node);
          case NOT:
            return visitNot(node);
          case EQ:
            return visitEq(node);
          case LE:
            return visitLe(node);
          case LT:
            return visitLt(node);
          case GE:
            return visitGe(node);
          case GT:
            return visitGt(node);
          case BETWEEN:
            return visitBetween(node);
          case MATCH:
            return visitMatch(node);
          case LIKE:
            return visitLike(node);
          case EXISTS:
            return visitExists(node);
          case MISSING:
            return visitMissing(node);
          case QUERY:
            return visitQuery(node);
          default:
        }
      } catch (IllegalArgumentException e) {
        // ignore
      }
      return null;
    }

    private Query visitAnd(ASTNode node) {
      BoolQuery.Builder builder = new BoolQuery.Builder();
      for (int i = 0; i < node.getArgumentsSize(); i++) {
        builder.must(visit((ASTNode) node.getArgument(i)));
      }
      return builder.build()._toQuery();
    }

    private Query visitNand(ASTNode node) {
      return BoolQuery.of(q -> q.mustNot(visitAnd(node)))._toQuery();
    }

    private Query visitOr(ASTNode node) {
      BoolQuery.Builder builder = new BoolQuery.Builder();
      for (int i = 0; i < node.getArgumentsSize(); i++) {
        builder.should(visit((ASTNode) node.getArgument(i)));
      }
      return builder.build()._toQuery();
    }

    private Query visitNor(ASTNode node) {
      return BoolQuery.of(q -> q.mustNot(visitOr(node)))._toQuery();
    }

    private Query visitContains(ASTNode node) {
      // if there is only one argument, all the terms of this argument are to be
      // matched on the default fields
      if (node.getArgumentsSize() == 1) {
        return QueryStringQuery.of(q -> q.query(toStringQuery(node.getArgument(0), " AND ")))._toQuery();
      }

      RQLFieldResolver.FieldData data = resolveField(node.getArgument(0).toString());
      String field = data.getField();
      Object args = node.getArgument(1);
      Collection<String> terms;
      terms = args instanceof Collection ? ((Collection<Object>) args).stream().map(Object::toString)
          .collect(Collectors.toList()) : Collections.singleton(args.toString());
      visitField(field, terms);

      BoolQuery.Builder builder = new BoolQuery.Builder();
      terms.forEach(t -> builder.must(TermQuery.of(q -> q.field(field).value(t))._toQuery()));
      return builder.build()._toQuery();
    }

    private Query visitIn(ASTNode node) {
      RQLFieldResolver.FieldData data = resolveField(node.getArgument(0).toString());
      String field = data.getField();
      if (data.isRange()) {
        return visitInRangeInternal(data, node.getArgument(1));
      }

      Object terms = node.getArgument(1);
      visitField(field, terms instanceof Collection ? ((Collection<Object>) terms).stream().map(Object::toString)
          .collect(Collectors.toList()) : Collections.singleton(terms.toString()));
      if (terms instanceof Collection) {
        Collection termList = (Collection<?>) terms;

        List<FieldValue> fieldValues = new ArrayList<>();
        ((Collection<?>) terms).forEach(t -> fieldValues.add(FieldValue.of(t.toString())));

        return TermsQuery.of(q -> q.field(field).terms(TermsQueryField.of(tqf -> tqf.value(fieldValues))))._toQuery();
      }

      List<FieldValue> fieldValues = new ArrayList<>();
      fieldValues.add(FieldValue.of(terms.toString()));
      return TermsQuery.of(q -> q.field(field).terms(TermsQueryField.of(tqf -> tqf.value(fieldValues))))._toQuery();
    }

    private Query visitInRangeInternal(RQLFieldResolver.FieldData data, Object rangesArgument) {
      Collection<String> ranges = rangesArgument instanceof Collection ? ((Collection<Object>) rangesArgument).stream()
          .map(Object::toString).collect(Collectors.toList()) : Collections.singleton(rangesArgument.toString());

      BoolQuery.Builder boolQueryBuilder = new BoolQuery.Builder();
      ranges.forEach(range -> {
        String[] values = range.split(":");
        if (values.length < 2) {
          throw new IllegalArgumentException("Invalid range format: " + range);
        }

        Query rangeQuery = null;

        if (!"*".equals(values[0]) || !"*".equals(values[1])) {
          if ("*".equals(values[0])) {
            rangeQuery = Query.of(q -> q
              .range(r -> r.term(TermRangeQuery.of(t -> t.field(data.getField()).lt(values[1]))))
            );
          } else if ("*".equals(values[1])) {
            rangeQuery = Query.of(q -> q
              .range(r -> r.term(TermRangeQuery.of(t -> t.field(data.getField()).gte(values[0]))))
            );
          } else {
            rangeQuery = Query.of(q -> q
              .range(r -> r.term(TermRangeQuery.of(t -> t.field(data.getField()).gte(values[0]).lt(values[1]))))
            );
          }
        }

        boolQueryBuilder.should(rangeQuery);
      });

      return boolQueryBuilder.build()._toQuery();
    }

    private Query visitOut(ASTNode node) {
      String field = resolveField(node.getArgument(0).toString()).getField();
      Object terms = node.getArgument(1);
      if (terms instanceof Collection) {
        List<FieldValue> fieldValues = new ArrayList<>();
        ((Collection<?>) terms).forEach(t -> fieldValues.add(FieldValue.of(t.toString())));

        return BoolQuery
            .of(q -> q.mustNot(TermsQuery
                .of(tq -> tq.field(field).terms(TermsQueryField.of(tqf -> tqf.value(fieldValues))))._toQuery()))
            ._toQuery();
      }

      List<FieldValue> fieldValues = new ArrayList<>();
      fieldValues.add(FieldValue.of(terms.toString()));

      return BoolQuery
          .of(q -> q.mustNot(
              TermsQuery.of(tq -> tq.field(field).terms(TermsQueryField.of(tqf -> tqf.value(fieldValues))))._toQuery()))
          ._toQuery();
    }

    private Query visitNot(ASTNode node) {
      Query expr = visit((ASTNode) node.getArgument(0));
      return BoolQuery.of(q -> q.mustNot(expr))._toQuery();
    }

    private Query visitEq(ASTNode node) {
      String field = resolveField(node.getArgument(0).toString()).getField();
      Object term = node.getArgument(1);
      visitField(field, Collections.singleton(term.toString()));

      return TermQuery.of(q -> q.field(field).value(term.toString()))._toQuery();
    }

    private Query visitLe(ASTNode node) {
      String field = resolveField(node.getArgument(0).toString()).getField();
      Object value = node.getArgument(1);
      visitField(field);

      return Query.of(q -> q
        .range(r -> r.term(TermRangeQuery.of(t -> t.field(field).lte(value.toString()))))
      );
    }

    private Query visitLt(ASTNode node) {
      String field = resolveField(node.getArgument(0).toString()).getField();
      Object value = node.getArgument(1);
      visitField(field);

      return Query.of(q -> q
        .range(r -> r.term(TermRangeQuery.of(t -> t.field(field).lt(value.toString()))))
      );
    }

    private Query visitGe(ASTNode node) {
      String field = resolveField(node.getArgument(0).toString()).getField();
      Object value = node.getArgument(1);
      visitField(field);

      return Query.of(q -> q
        .range(r -> r.term(TermRangeQuery.of(t -> t.field(field).gte(value.toString()))))
      );
    }

    private Query visitGt(ASTNode node) {
      String field = resolveField(node.getArgument(0).toString()).getField();
      Object value = node.getArgument(1);
      visitField(field);

      return Query.of(q -> q
        .range(r -> r.term(TermRangeQuery.of(t -> t.field(field).gt(value.toString()))))
      );
    }

    private Query visitBetween(ASTNode node) {
      String field = resolveField(node.getArgument(0).toString()).getField();
      visitField(field);
      ArrayList<Object> values = (ArrayList<Object>) node.getArgument(1);

      return Query.of(q -> q
        .range(r -> r.term(TermRangeQuery.of(t -> t.field(field).gte(values.get(0).toString()).lte(values.get(1).toString()))))
      );
    }

    private Query visitMatch(ASTNode node) {
      if (node.getArgumentsSize() == 0)
        return new MatchAllQuery.Builder().build()._toQuery();
      String stringQuery = toStringQuery(node.getArgument(0), " OR ");
      // if there is only one argument, the fields to be matched are the default ones
      // otherwise, the following argument can be the field name or a list of field
      // names

      QueryStringQuery.Builder builder = new QueryStringQuery.Builder().query(stringQuery);

      if (node.getArgumentsSize() > 1) {
        if (node.getArgument(1) instanceof List) {
          List<Object> fields = (List<Object>) node.getArgument(1);
          List<String> resolvedFields = fields.stream().map(Object::toString).map(f -> resolveField(f).getField())
              .collect(Collectors.toList());

          builder.fields(resolvedFields);
        } else {
          List<String> resolvedFields = new ArrayList<>();
          resolvedFields.add(resolveField(node.getArgument(1).toString()).getField());

          builder.fields(resolvedFields);
        }
      } else if (node.getArgumentsSize() == 1) {
        // make sure that it's a full text search but add more weight to the analyzed
        // fields
        List<String> resolvedFields = new ArrayList<>();
        resolvedFields.add("_all");

        for (String analyzedField : rqlFieldResolver.getAnalzedFields()) {
          resolvedFields.add(resolveField(analyzedField).getField());
        }

        builder.fields(resolvedFields).boost(5F);
      }

      return builder.build()._toQuery();
    }

    private Query visitLike(ASTNode node) {
      String field = resolveField(node.getArgument(0).toString()).getField();
      Object value = node.getArgument(1);
      visitField(field);

      return WildcardQuery.of(q -> q.field(field).value(value.toString()))._toQuery();
    }

    private Query visitExists(ASTNode node) {
      String field = resolveField(node.getArgument(0).toString()).getField();
      visitField(field);

      return ExistsQuery.of(q -> q.field(field))._toQuery();
    }

    private Query visitMissing(ASTNode node) {
      String field = resolveField(node.getArgument(0).toString()).getField();
      visitField(field);

      return BoolQuery.of(q -> q.mustNot(visitExists(node)))._toQuery();
    }

    private Query visitQuery(ASTNode node) {
      String query = node.getArgument(0).toString().replaceAll("\\+", " ");
      return QueryStringQuery.of(q -> q.query(query))._toQuery();
    }

    private void visitField(String field) {
      visitField(field, null);
    }

    private void visitField(String field, Collection<String> terms) {
      if (!isAttributeField(field))
        return;
      AttributeKey key = AttributeKey.from(field.replaceAll("^attributes\\.", "").replaceAll("\\.und$", ""));
      if (!key.hasNamespace())
        return;

      if (!taxonomyTermsMap.containsKey(key.getNamespace())) {
        taxonomyTermsMap.put(key.getNamespace(), Maps.newHashMap());
      }
      Map<String, List<String>> vocMap = taxonomyTermsMap.get(key.getNamespace());
      if (!vocMap.containsKey(key.getName())) {
        vocMap.put(key.getName(), Lists.newArrayList());
      }

      if (terms != null) {
        vocMap.get(key.getName()).addAll(terms);
      } else {
        // add all terms from taxonomy vocabulary
        Vocabulary vocabulary = getVocabulary(key.getNamespace(), key.getName());
        if (vocabulary != null && vocabulary.hasTerms()) {
          vocMap.get(key.getName())
              .addAll(vocabulary.getTerms().stream().map(TaxonomyEntity::getName).collect(Collectors.toList()));
        }
      }
    }

    private String toStringQuery(Object argument, String joiner) {
      String stringQuery;
      if (argument instanceof Collection) {
        Collection<Object> terms = (Collection<Object>) argument;

        long countOfUnclosedDoubleQuotes = terms.stream().map(t -> t.toString())
            .filter(t -> (t.startsWith("\"") && !t.endsWith("\"")) || (!t.startsWith("\"") && t.endsWith("\"")))
            .count();

        stringQuery = terms.stream().map(t -> t.toString()).map(t -> {
          String res = t;
          if (t.startsWith("\"") && !t.endsWith("\"")) {
            res = t + "\"";
          } else if (!t.startsWith("\"") && t.endsWith("\"")) {
            res = "\"" + t;
          }

          return res;
        }).collect(Collectors.joining(countOfUnclosedDoubleQuotes > 0 ? "AND" : joiner));
      } else {
        String res = argument.toString();
        if (res.startsWith("\"") && !res.endsWith("\"")) {
          res = res + "\"";
        } else if (!res.startsWith("\"") && res.endsWith("\"")) {
          res = "\"" + res;
        }

        stringQuery = res;
      }
      return stringQuery;
    }

    private boolean isAttributeField(String field) {
      return field.startsWith("attributes.") && field.endsWith(".und");
    }
  }

  private static class RQLLimitBuilder implements SimpleASTVisitor<Boolean> {
    private int from = DEFAULT_FROM;

    private int size = DEFAULT_SIZE;

    public int getFrom() {
      return from;
    }

    public int getSize() {
      return size;
    }

    @Override
    public Boolean visit(ASTNode node) {
      try {
        RQLNode type = RQLNode.getType(node.getName());
        switch (type) {
          case LIMIT:
            from = (Integer) node.getArgument(0);
            if (node.getArgumentsSize() > 1)
              size = (Integer) node.getArgument(1);
            return Boolean.TRUE;
          default:
        }
      } catch (IllegalArgumentException e) {
        // ignore
      }
      return Boolean.FALSE;
    }
  }

  private class RQLSortBuilder extends RQLBuilder<List<SortBuilder>> {
    RQLSortBuilder(RQLFieldResolver rqlFieldResolver) {
      super(rqlFieldResolver);
    }

    @Override
    public List<SortBuilder> visit(ASTNode node) {
      try {
        RQLNode type = RQLNode.getType(node.getName());
        switch (type) {
          case SORT:
            List<SortBuilder> sortBuilders = Lists.newArrayList();
            if (node.getArgumentsSize() >= 1) {
              for (int i = 0; i < node.getArgumentsSize(); i++) {
                String sortKey = node.getArgument(i).toString();
                SortBuilder sortBuilder = processArgument(sortKey);
                if (!"_score".equals(((FieldSortBuilder) sortBuilder).getFieldName())) {
                  ((FieldSortBuilder) sortBuilder).unmappedType("string");
                  ((FieldSortBuilder) sortBuilder).missing("_last");
                }
                sortBuilders.add(sortBuilder);
              }
            }
            return sortBuilders;
        }
      } catch (IllegalArgumentException e) {
        // ignore
      }
      return null;
    }

    private SortBuilder processArgument(String arg) {
      if (arg.startsWith("-"))
        return SortBuilders.fieldSort(resolveFieldUnanalyzed(arg.substring(1)).getField()).order(SortOrder.DESC);
      else if (arg.startsWith("+"))
        return SortBuilders.fieldSort(resolveFieldUnanalyzed(arg.substring(1)).getField()).order(SortOrder.ASC);
      else
        return SortBuilders.fieldSort(resolveFieldUnanalyzed(arg).getField()).order(SortOrder.ASC);
    }

  }

  private class RQLAggregateBuilder extends RQLBuilder<Boolean> {
    RQLAggregateBuilder(RQLFieldResolver rqlFieldResolver) {
      super(rqlFieldResolver);
    }

    private List<String> aggregations = Lists.newArrayList();

    private List<String> aggregationBuckets = Lists.newArrayList();

    public List<String> getAggregations() {
      return aggregations.stream().map(a -> resolveField(a).getField()).collect(Collectors.toList());
    }

    public List<String> getAggregationBuckets() {
      return aggregationBuckets.stream().map(a -> resolveField(a).getField()).collect(Collectors.toList());
    }

    @Override
    public Boolean visit(ASTNode node) {
      try {
        RQLNode type = RQLNode.getType(node.getName());
        switch (type) {
          case AGGREGATE:
            if (node.getArgumentsSize() == 0)
              return Boolean.TRUE;
            node.getArguments().stream().filter(a -> a instanceof String).map(Object::toString)
                .forEach(aggregations::add);
            node.getArguments().stream().filter(a -> a instanceof ASTNode).map(a -> (ASTNode) a)
                .forEach(a -> {
                  switch (RQLNode.getType(a.getName())) {
                    case BUCKET:
                      a.getArguments().stream().map(Object::toString).forEach(aggregationBuckets::add);
                      break;
                    case RE:
                      a.getArguments().stream().map(Object::toString).forEach(aggregations::add);
                      break;
                  }
                });
            return Boolean.TRUE;
          default:
        }
      } catch (IllegalArgumentException e) {
        // ignore
      }
      return Boolean.FALSE;
    }
  }

}
