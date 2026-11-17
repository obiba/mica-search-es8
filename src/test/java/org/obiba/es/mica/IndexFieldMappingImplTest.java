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

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.ReadContext;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class IndexFieldMappingImplTest {

  /**
   * Study index mapping, in the shape ESIndexer reads it: a localized acronym with the sort sub-field, a localized
   * description without it, a non-localized id, and populations with their own localized name.
   */
  private static final String MAPPING = """
      {
        "acronym": {"properties": {
          "en": {"type": "keyword", "fields": {"analyzed": {"type": "text"}, "sort": {"type": "keyword"}}},
          "fr": {"type": "keyword", "fields": {"analyzed": {"type": "text"}, "sort": {"type": "keyword"}}}
        }},
        "description": {"properties": {
          "en": {"type": "keyword", "fields": {"analyzed": {"type": "text"}}}
        }},
        "id": {"type": "keyword"},
        "populations": {"properties": {
          "name": {"properties": {
            "en": {"type": "keyword", "fields": {"analyzed": {"type": "text"}}}
          }}
        }}
      }
      """;

  private static ReadContext context() {
    return JsonPath.using(Configuration.defaultConfiguration().addOptions(Option.ALWAYS_RETURN_LIST)).parse(MAPPING);
  }

  @Test
  public void test_field_with_sort_sub_field_is_sortable() {
    ESIndexFieldMapping mapping = new ESIndexer.IndexFieldMappingImpl(context(), true);
    assertThat(mapping.isSortable("acronym.en")).isTrue();
    assertThat(mapping.isSortable("acronym.fr")).isTrue();
    assertThat(mapping.getSortField("acronym.en")).isEqualTo("acronym.en.sort");
  }

  @Test
  public void test_field_without_sort_sub_field_is_not_sortable() {
    ESIndexFieldMapping mapping = new ESIndexer.IndexFieldMappingImpl(context(), true);
    assertThat(mapping.isSortable("description.en")).isFalse();
    assertThat(mapping.isSortable("id")).isFalse();
    assertThat(mapping.isSortable("populations.name.en")).isFalse();
    assertThat(mapping.isSortable("acronym.de")).isFalse();
    assertThat(mapping.isSortable("unknown")).isFalse();
    assertThat(mapping.getSortField("description.en")).isEqualTo("description.en");
  }

  @Test
  public void test_not_sortable_when_disabled() {
    ESIndexFieldMapping mapping = new ESIndexer.IndexFieldMappingImpl(context(), false);
    assertThat(mapping.isSortable("acronym.en")).isFalse();
    assertThat(mapping.getSortField("acronym.en")).isEqualTo("acronym.en");
  }

  @Test
  public void test_not_sortable_without_index() {
    ESIndexFieldMapping mapping = new ESIndexer.IndexFieldMappingImpl(null, true);
    assertThat(mapping.isSortable("acronym.en")).isFalse();
    assertThat(mapping.isSortable("")).isFalse();
    assertThat(mapping.isSortable(null)).isFalse();
  }

  @Test
  public void test_analyzed_unchanged() {
    ESIndexFieldMapping mapping = new ESIndexer.IndexFieldMappingImpl(context(), true);
    assertThat(mapping.isAnalyzed("acronym.en")).isTrue();
    assertThat(mapping.isAnalyzed("id")).isFalse();
  }
}
