/*
 * Copyright (c) 2026 OBiBa. All rights reserved.
 *
 * This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.obiba.es.mica.query;

import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.junit.Test;
import org.obiba.es.mica.ESIndexFieldMapping;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class RQLQuerySortTest {

  /**
   * Mapping where only the given fields have the sort sub-field.
   */
  private static ESIndexFieldMapping mappingWithSort(String... sortableFields) {
    Set<String> sortable = Set.of(sortableFields);
    return new ESIndexFieldMapping() {
      @Override
      public boolean isSortable(String fieldName) {
        return sortable.contains(fieldName);
      }

      @Override
      public boolean isAnalyzed(String fieldName) {
        return false;
      }
    };
  }

  private static List<String> sortFields(RQLQuery query) {
    return query.getSortBuilders().stream()
        .map(sb -> ((FieldSortBuilder) sb).getFieldName())
        .collect(Collectors.toList());
  }

  @Test
  public void test_sort_uses_sort_sub_field_when_mapped() {
    RQLQuery query = new RQLQuery("variable(sort(name))", () -> mappingWithSort("name"));
    assertThat(sortFields(query)).containsExactly("name.sort");
  }

  @Test
  public void test_sort_keeps_order_and_prefixes() {
    RQLQuery query = new RQLQuery("variable(sort(-name,+datasetId))", () -> mappingWithSort("name"));
    assertThat(sortFields(query)).containsExactly("name.sort", "datasetId");
    assertThat(query.getSortBuilders().get(0).order()).isEqualTo(SortOrder.DESC);
    assertThat(query.getSortBuilders().get(1).order()).isEqualTo(SortOrder.ASC);
  }

  @Test
  public void test_sort_falls_back_when_not_sortable() {
    // index not re-indexed yet, or case-insensitive sort disabled
    RQLQuery query = new RQLQuery("variable(sort(name))", () -> mappingWithSort());
    assertThat(sortFields(query)).containsExactly("name");
  }

  @Test
  public void test_sort_without_mapping() {
    assertThat(sortFields(new RQLQuery("variable(sort(name))"))).containsExactly("name");
    assertThat(sortFields(new RQLQuery("variable(sort(name))", () -> null))).containsExactly("name");
  }

  @Test
  public void test_mapping_not_fetched_without_sort() {
    AtomicInteger calls = new AtomicInteger();
    Supplier<ESIndexFieldMapping> supplier = () -> {
      calls.incrementAndGet();
      return mappingWithSort("name");
    };
    new RQLQuery("variable(eq(datasetId,ds1),limit(0,10))", supplier);
    assertThat(calls.get()).isZero();
  }

  @Test
  public void test_get_sort_field() {
    ESIndexFieldMapping mapping = mappingWithSort("acronym.en");
    assertThat(mapping.getSortField("acronym.en")).isEqualTo("acronym.en.sort");
    assertThat(mapping.getSortField("acronym.fr")).isEqualTo("acronym.fr");
  }
}
