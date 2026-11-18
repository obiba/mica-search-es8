/*
 * Copyright (c) 2026 OBiBa. All rights reserved.
 *
 * This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.obiba.es.mica;

import co.elastic.clients.elasticsearch._types.FieldSort;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.mapping.FieldType;
import org.junit.Test;
import org.obiba.es.mica.query.RQLQuery;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

public class ESSearcherSortTest {

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

  private static List<FieldSort> sorts(String rql, ESIndexFieldMapping mapping) {
    return ESSearcher.toSortOptions(new RQLQuery("variable(" + rql + ")").getSortBuilders(), () -> mapping).stream()
        .map(SortOptions::field).toList();
  }

  @Test
  public void test_sort_sub_field_then_raw_field_as_tiebreaker() {
    List<FieldSort> sorts = sorts("sort(-acronym.en,id)", mappingWithSort("acronym.en"));
    assertThat(sorts).extracting(FieldSort::field).containsExactly("acronym.en.sort", "acronym.en", "id");
    assertThat(sorts).extracting(FieldSort::order).containsExactly(SortOrder.Desc, SortOrder.Desc, SortOrder.Asc);
  }

  @Test
  public void test_unmapped_type_and_missing_are_kept() {
    FieldSort sort = sorts("sort(acronym.en)", mappingWithSort("acronym.en")).get(0);
    assertThat(sort.unmappedType()).isEqualTo(FieldType.Keyword);
    assertThat(sort.missing().stringValue()).isEqualTo("_last");
  }

  @Test
  public void test_raw_field_without_mapping() {
    assertThat(sorts("sort(acronym.en)", null)).extracting(FieldSort::field).containsExactly("acronym.en");
  }

  @Test
  public void test_mapping_not_fetched_for_score() {
    AtomicInteger calls = new AtomicInteger();
    ESSearcher.toSortOptions(new RQLQuery("variable(sort(_score))").getSortBuilders(), () -> {
      calls.incrementAndGet();
      return null;
    });
    assertThat(calls.get()).isZero();
  }
}
