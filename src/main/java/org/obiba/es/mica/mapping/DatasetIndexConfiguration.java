/*
 * Copyright (c) 2024 OBiBa. All rights reserved.
 *
 * This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.obiba.es.mica.mapping;

import com.google.common.collect.Lists;
import org.elasticsearch.common.Strings;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.obiba.mica.spi.search.ConfigurationProvider;
import org.obiba.mica.spi.search.Indexer;
import org.obiba.mica.spi.search.SearchEngineService;
import org.obiba.mica.spi.search.TaxonomyTarget;
import org.obiba.opal.core.domain.taxonomy.Taxonomy;

import co.elastic.clients.elasticsearch.indices.PutMappingRequest;

import java.io.IOException;
import java.io.StringReader;

public class DatasetIndexConfiguration extends AbstractIndexConfiguration {

  public DatasetIndexConfiguration(ConfigurationProvider configurationProvider) {
    super(configurationProvider);
  }

  @Override
  public void onIndexCreated(SearchEngineService searchEngineService, String indexName) {

    if (Indexer.DRAFT_DATASET_INDEX.equals(indexName) ||
        Indexer.PUBLISHED_DATASET_INDEX.equals(indexName)) {
      try {
        XContentBuilder properties = createMappingProperties();
        getClient(searchEngineService)
            .indices()
            .putMapping(
                PutMappingRequest.of(r -> r.index(indexName).withJson(new StringReader(Strings.toString(properties)))));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private XContentBuilder createMappingProperties() throws IOException {
    XContentBuilder mapping = XContentFactory.jsonBuilder().startObject();
    mapping.startObject("properties");
    Taxonomy taxonomy = getTaxonomy();
    addStaticVocabularies(taxonomy, "studyTable.studyId", "studyTables.studyId", "harmonizationTable.studyId",
        "harmonizationTables.studyId");
    // TODO use DATASET_LOCALIZED_ANALYZED_FIELDS
    addLocalizedVocabularies(taxonomy, "name", "acronym", "description");
    addTaxonomyFields(mapping, taxonomy, Lists.newArrayList());
    mapping.endObject().endObject();

    return mapping;
  }

  @Override
  protected TaxonomyTarget getTarget() {
    return TaxonomyTarget.DATASET;
  }

}
